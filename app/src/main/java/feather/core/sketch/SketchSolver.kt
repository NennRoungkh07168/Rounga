package feather.core.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class SolveStatus {
    /** Every constraint is satisfied. */
    OK,
    /** The constraints contradict each other (for example two different lengths on one line). Geometry is left unchanged. */
    CONFLICT,
    /** The only "solution" collapses a line or circle to nothing. Geometry is left unchanged. */
    DEGENERATE,
}

/**
 * @param degreesOfFreedom how many independent movements are still free (0 = fully constrained)
 * @param redundantConstraints constraints that repeat information already given by others (harmless when consistent)
 * @param conflicting indices (in [Sketch.constraints]) of the constraints that could not be satisfied
 */
class SolveResult(
    val status: SolveStatus,
    val degreesOfFreedom: Int,
    val redundantConstraints: Int,
    val iterations: Int,
    val maxResidual: Double,
    val conflicting: List<Int>,
) {
    val ok: Boolean get() = status == SolveStatus.OK
}

/**
 * Levenberg-Marquardt solver for [Sketch] constraints. Starting from the geometry as drawn, it takes the smallest
 * movement that satisfies all constraints, so a roughly drawn shape snaps to the nearest exact one.
 * Verified against a Python prototype of the same algorithm (random crooked rectangles, conflicts, tangents, arcs).
 */
object SketchSolver {
    private const val TOLERANCE = 1e-9      // max residual at which we stop iterating
    private const val ACCEPT = 1e-6         // max residual at which we call the sketch solved
    private const val DEGENERATE_MM = 1e-6

    fun solve(sketch: Sketch, maxIterations: Int = 100): SolveResult =
        try {
            solveInternal(sketch, maxIterations)
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("A constraint refers to a point, line or circle that no longer exists.", e)
        }

    private fun solveInternal(sketch: Sketch, maxIterations: Int): SolveResult {
        val ev = Evaluator(sketch)
        val x = ev.initial()
        var r = ev.residuals(x)
        val start = x.copyOf()
        var work = x
        var cost = dot(r, r)
        var lambda = 1e-3
        var iter = 0
        var stall = 0
        val n = ev.size

        while (iter < maxIterations && maxAbs(r) > TOLERANCE && stall < 4) {
            iter++
            val j = ev.jacobian(work)
            val m = r.size
            val jtj = Array(n) { DoubleArray(n) }
            val g = DoubleArray(n)
            for (a in 0 until n) {
                for (b in 0 until n) {
                    var sum = 0.0
                    for (k in 0 until m) sum += j[k][a] * j[k][b]
                    jtj[a][b] = sum
                }
                var sg = 0.0
                for (k in 0 until m) sg += j[k][a] * r[k]
                g[a] = sg
            }
            var improved = false
            var tries = 0
            while (tries < 30) {
                tries++
                val a = Array(n) { row -> jtj[row].copyOf() }
                for (i in 0 until n) a[i][i] += lambda * (1.0 + jtj[i][i])
                val rhs = DoubleArray(n) { -g[it] }
                val d = solveLinear(a, rhs)
                if (d != null) {
                    val xn = DoubleArray(n) { work[it] + d[it] }
                    val rn = ev.residuals(xn)
                    val cn = dot(rn, rn)
                    if (cn < cost) {
                        stall = if ((cost - cn) < 1e-6 * maxOf(cost, 1e-12)) stall + 1 else 0
                        work = xn; r = rn; cost = cn
                        lambda = maxOf(lambda / 3.0, 1e-12)
                        improved = true
                        break
                    }
                }
                lambda *= 4.0
            }
            if (!improved) break
        }

        val maxRes = maxAbs(r)
        val jFinal = ev.jacobian(work)
        val rank = if (r.isEmpty()) 0 else rankOf(jFinal)
        val dof = n - rank
        val redundant = r.size - rank
        val degenerate = ev.isDegenerate(work)
        val status = when {
            degenerate -> SolveStatus.DEGENERATE
            maxRes >= ACCEPT -> SolveStatus.CONFLICT
            else -> SolveStatus.OK
        }
        val bad = ArrayList<Int>()
        if (status != SolveStatus.OK) {
            val owners = ev.owners
            for (k in r.indices) {
                val owner = if (k < owners.size) owners[k] else -1
                if (owner >= 0 && abs(r[k]) >= ACCEPT && !bad.contains(owner)) bad.add(owner)
            }
            ev.store(start) // keep the drawing as it was instead of showing a half-solved mess
        } else {
            ev.store(work)
        }
        return SolveResult(status, dof, redundant, iter, maxRes, bad)
    }

    // ---------------------------------------------------------------------------------------------------------

    private class Evaluator(val s: Sketch) {
        private val pi = HashMap<Int, Int>()
        private val ri = HashMap<Int, Int>()
        val size: Int
        /** For each residual row, the index of the constraint it belongs to (-1 for implicit arc rows). */
        var owners: IntArray = IntArray(0)

        init {
            var n = 0
            for (id in s.points.keys) { pi[id] = n; n += 2 }
            for (id in s.circles.keys) { ri[id] = n; n += 1 }
            size = n
        }

        fun initial(): DoubleArray {
            val x = DoubleArray(size)
            for ((id, p) in s.points) { val i = pi.getValue(id); x[i] = p.x; x[i + 1] = p.y }
            for ((id, c) in s.circles) x[ri.getValue(id)] = c.radius
            return x
        }

        fun store(x: DoubleArray) {
            for ((id, p) in s.points) { val i = pi.getValue(id); p.x = x[i]; p.y = x[i + 1] }
            for ((id, c) in s.circles) c.radius = x[ri.getValue(id)]
        }

        fun isDegenerate(x: DoubleArray): Boolean {
            for (l in s.lines.values) {
                val a = pi.getValue(l.a); val b = pi.getValue(l.b)
                if (hypot(x[b] - x[a], x[b + 1] - x[a + 1]) < DEGENERATE_MM) return true
            }
            for (id in s.circles.keys) if (x[ri.getValue(id)] < DEGENERATE_MM) return true
            return false
        }

        fun jacobian(x: DoubleArray): Array<DoubleArray> {
            val base = residuals(x)
            val m = base.size
            val jac = Array(m) { DoubleArray(size) }
            for (col in 0 until size) {
                val h = 1e-6 * (1.0 + abs(x[col]))
                val xp = x.copyOf(); xp[col] += h
                val xm = x.copyOf(); xm[col] -= h
                val rp = residuals(xp)
                val rm = residuals(xm)
                for (row in 0 until m) jac[row][col] = (rp[row] - rm[row]) / (2.0 * h)
            }
            return jac
        }

        fun residuals(x: DoubleArray): DoubleArray {
            val out = ArrayList<Double>()
            val own = ArrayList<Int>()
            var cur = -1
            fun put(v: Double) { out.add(v); own.add(cur) }

            fun px(id: Int): Double = x[pi.getValue(id)]
            fun py(id: Int): Double = x[pi.getValue(id) + 1]
            fun dx(l: Int): Double { val ln = s.lines.getValue(l); return px(ln.b) - px(ln.a) }
            fun dy(l: Int): Double { val ln = s.lines.getValue(l); return py(ln.b) - py(ln.a) }
            fun len(l: Int): Double = hypot(dx(l), dy(l))
            fun centreOf(e: Int): Int = s.circles[e]?.center ?: s.arcs.getValue(e).center
            fun radiusOf(e: Int): Double {
                val c = s.circles[e]
                if (c != null) return x[ri.getValue(e)]
                val a = s.arcs.getValue(e)
                return hypot(px(a.start) - px(a.center), py(a.start) - py(a.center))
            }

            for ((k, c) in s.constraints.withIndex()) {
                cur = k
                when (c) {
                    is Constraint.Coincident -> { put(px(c.p) - px(c.q)); put(py(c.p) - py(c.q)) }
                    is Constraint.Horizontal -> put(dy(c.line))
                    is Constraint.Vertical -> put(dx(c.line))
                    is Constraint.Parallel ->
                        put((dx(c.l1) * dy(c.l2) - dy(c.l1) * dx(c.l2)) / (len(c.l1) * len(c.l2) + 1e-12))
                    is Constraint.Perpendicular ->
                        put((dx(c.l1) * dx(c.l2) + dy(c.l1) * dy(c.l2)) / (len(c.l1) * len(c.l2) + 1e-12))
                    is Constraint.EqualLength -> put(len(c.l1) - len(c.l2))
                    is Constraint.Distance -> put(hypot(px(c.q) - px(c.p), py(c.q) - py(c.p)) - c.value)
                    is Constraint.DistanceX -> put((px(c.q) - px(c.p)) - c.value)
                    is Constraint.DistanceY -> put((py(c.q) - py(c.p)) - c.value)
                    is Constraint.Length -> put(len(c.line) - c.value)
                    is Constraint.Radius -> put(radiusOf(c.circle) - c.value)
                    is Constraint.Diameter -> put(2.0 * radiusOf(c.circle) - c.value)
                    is Constraint.EqualRadius -> put(radiusOf(c.c1) - radiusOf(c.c2))
                    is Constraint.Angle -> {
                        val cross = dx(c.l1) * dy(c.l2) - dy(c.l1) * dx(c.l2)
                        val dotp = dx(c.l1) * dx(c.l2) + dy(c.l1) * dy(c.l2)
                        put(wrap(atan2(cross, dotp) - Math.toRadians(c.degrees)))
                    }
                    is Constraint.PointOnLine -> {
                        val a = s.lines.getValue(c.line).a
                        put((dx(c.line) * (py(c.p) - py(a)) - dy(c.line) * (px(c.p) - px(a))) / (len(c.line) + 1e-12))
                    }
                    is Constraint.PointOnCircle -> {
                        val m = centreOf(c.circle)
                        put(hypot(px(c.p) - px(m), py(c.p) - py(m)) - radiusOf(c.circle))
                    }
                    is Constraint.Fixed -> { put(px(c.p) - c.x); put(py(c.p) - c.y) }
                    is Constraint.Midpoint -> {
                        val ln = s.lines.getValue(c.line)
                        put(px(c.p) - (px(ln.a) + px(ln.b)) / 2.0)
                        put(py(c.p) - (py(ln.a) + py(ln.b)) / 2.0)
                    }
                    is Constraint.Tangent -> {
                        val a = s.lines.getValue(c.line).a
                        val m = centreOf(c.circle)
                        val cross = dx(c.line) * (py(m) - py(a)) - dy(c.line) * (px(m) - px(a))
                        put(abs(cross) / (len(c.line) + 1e-12) - radiusOf(c.circle))
                    }
                }
            }
            cur = -1
            for (arc in s.arcs.values) { // an arc's two ends are always the same distance from its centre
                val r1 = hypot(px(arc.start) - px(arc.center), py(arc.start) - py(arc.center))
                val r2 = hypot(px(arc.end) - px(arc.center), py(arc.end) - py(arc.center))
                put(r1 - r2)
            }
            owners = own.toIntArray()
            return out.toDoubleArray()
        }

        private fun wrap(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2.0 * PI
            while (a < -PI) a += 2.0 * PI
            return a
        }
    }

    // ---- small dense linear algebra ----------------------------------------------------------------------

    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun maxAbs(a: DoubleArray): Double { var m = 0.0; for (v in a) if (abs(v) > m) m = abs(v); return m }

    /** Gaussian elimination with partial pivoting; null if the matrix is singular. */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (c in 0 until n) {
            var p = c
            for (i in c + 1 until n) if (abs(m[i][c]) > abs(m[p][c])) p = i
            if (abs(m[p][c]) < 1e-18) return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (i in c + 1 until n) {
                val f = m[i][c] / m[c][c]
                if (f != 0.0) for (j in c..n) m[i][j] -= f * m[c][j]
            }
        }
        val xs = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = m[i][n]
            for (j in i + 1 until n) sum -= m[i][j] * xs[j]
            xs[i] = sum / m[i][i]
        }
        return xs
    }

    /** Numerical rank by elimination with complete pivoting (used to count degrees of freedom). */
    private fun rankOf(jac: Array<DoubleArray>): Int {
        val m = jac.size
        if (m == 0) return 0
        val n = jac[0].size
        val mat = Array(m) { jac[it].copyOf() }
        var scale = 0.0
        for (row in mat) for (v in row) if (abs(v) > scale) scale = abs(v)
        if (scale == 0.0) return 0
        val used = BooleanArray(n)
        var rank = 0
        for (step in 0 until minOf(m, n)) {
            var best = 0.0; var bi = -1; var bj = -1
            for (i in rank until m) for (j in 0 until n) {
                if (!used[j] && abs(mat[i][j]) > best) { best = abs(mat[i][j]); bi = i; bj = j }
            }
            if (best < 1e-7 * scale) break
            val tmp = mat[rank]; mat[rank] = mat[bi]; mat[bi] = tmp
            used[bj] = true
            for (i in rank + 1 until m) {
                val f = mat[i][bj] / mat[rank][bj]
                if (f != 0.0) for (j in 0 until n) mat[i][j] -= f * mat[rank][j]
            }
            rank++
        }
        return rank
    }
}
