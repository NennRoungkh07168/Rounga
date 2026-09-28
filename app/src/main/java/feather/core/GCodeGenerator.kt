package feather.core

import feather.model.MachineContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** Thrown by [GCodeGenerator.generate] when a job doesn't fit the target machine. */
class MachineLimitException(message: String) : Exception(message)

/**
 * Converts a [CutJob] into plain G-code (mm, absolute, XY plane).
 *
 * Toolpath strategy:
 *  - **Multi-pass depth.** Depth is split into passes of at most `stepDownUm`;
 *    every Z value is on the machine's 0.02 mm grid (enforced by
 *    [JobSettings.validationErrors]).
 *  - **Stay down between passes.** All passes of one path are cut before the
 *    tool retracts. Closed paths (rect, circle) simply repeat; open paths
 *    alternate direction each pass (zig-zag), so nothing rapids back to the
 *    start 100 times for a 2 mm engrave.
 *  - **Adaptive circles.** Circles are emitted as polygons (portable across
 *    GRBL/Marlin variants) with enough segments to stay within
 *    `arcToleranceUm` of the true circle, rather than a fixed count.
 *  - **Optional travel optimisation** (nearest-first ordering).
 *  - Output is ASCII only and locale-independent.
 */
object GCodeGenerator {

    /**
     * Machine-agnostic export: geometry + [JobSettings] only, no tool-on
     * command is emitted (kept for callers with no [MachineContext] yet, and
     * for tests). Prefer the [MachineContext] overload for anything that will
     * actually run on a device — it also validates the job fits the machine.
     */
    fun generate(job: CutJob): String {
        val s = job.settings
        val errors = s.validationErrors()
        require(errors.isEmpty()) { errors.joinToString(" ") }

        val passCount = passesFor(s.totalDepthUm, s.stepDownUm)
        var paths = job.shapes.filterNot { it.hidden }.mapNotNull { toolpathOf(it, s.arcToleranceUm) }
        if (s.optimizeTravel) paths = orderNearestFirst(paths, passCount)

        val sb = StringBuilder()
        sb.appendLine("; Feather export - units: mm, resolution: 0.001 mm (1 um)")
        sb.appendLine("; paths: ${paths.size}, passes per path: $passCount, depth: ${s.totalDepthUm.toGcodeMm()} mm")
        sb.appendLine("G21 ; millimeters")
        sb.appendLine("G90 ; absolute positioning")
        sb.appendLine("G17 ; XY plane")
        sb.appendLine("G94 ; feed per minute")
        sb.appendLine("G0 Z${s.safeHeightUm.toGcodeMm()}")

        for ((index, path) in paths.withIndex()) {
            sb.appendLine("; path ${index + 1}/${paths.size}")
            emitPath(sb, path, s, passCount)
        }

        sb.appendLine("G0 Z${s.safeHeightUm.toGcodeMm()}")
        sb.appendLine("M5 ; tool off")
        sb.appendLine("M30 ; program end")
        return sb.toString()
    }

    /**
     * Device-aware export: validates [job] against [machine]'s travel, feed,
     * and cut-depth limits (throws [MachineLimitException] instead of sending
     * a job the controller would just alarm out on), then generates the same
     * toolpaths as [generate] but with a real tool-on / tool-off pair around
     * them, driven by whichever [feather.model.ToolHead] is active on that device.
     */
    fun generate(job: CutJob, machine: MachineContext): String {
        val settingsErrors = job.settings.validationErrors()
        require(settingsErrors.isEmpty()) { settingsErrors.joinToString(" ") }
        val machineErrors = machine.validate(job)
        if (machineErrors.isNotEmpty()) throw MachineLimitException(machineErrors.joinToString(" "))

        val s = job.settings
        val passCount = passesFor(s.totalDepthUm, s.stepDownUm)
        var paths = job.shapes.filterNot { it.hidden }.mapNotNull { toolpathOf(it, s.arcToleranceUm) }
        if (s.optimizeTravel) paths = orderNearestFirst(paths, passCount)

        val sb = StringBuilder()
        sb.appendLine("; Feather export - units: mm, resolution: 0.001 mm (1 um)")
        sb.appendLine("; machine: ${machine.profile.name} (${machine.profile.machineType.displayName}), tool: ${machine.tool.name}")
        sb.appendLine("; paths: ${paths.size}, passes per path: $passCount, depth: ${s.totalDepthUm.toGcodeMm()} mm")
        sb.appendLine("G21 ; millimeters")
        sb.appendLine("G90 ; absolute positioning")
        sb.appendLine("G17 ; XY plane")
        sb.appendLine("G94 ; feed per minute")
        sb.appendLine(machine.profile.coordinateSystem)
        sb.appendLine("G0 Z${s.safeHeightUm.toGcodeMm()}")
        sb.appendLine(machine.toolOnGcode())

        for ((index, path) in paths.withIndex()) {
            sb.appendLine("; path ${index + 1}/${paths.size}")
            emitPath(sb, path, s, passCount)
        }

        sb.appendLine("G0 Z${s.safeHeightUm.toGcodeMm()}")
        sb.appendLine(machine.toolOffGcode())
        sb.appendLine(machine.programEndGcode())
        return sb.toString()
    }

    /** Ceiling division: passes needed so no pass exceeds the step-down. */
    fun passesFor(total: Micrometers, step: Micrometers): Int {
        val n = (total.raw + step.raw - 1) / step.raw
        return maxOf(1, Math.toIntExact(n))
    }

    // ---- Toolpaths ---------------------------------------------------------

    private class Toolpath(val points: List<PointUm>, val closed: Boolean)

    private fun toolpathOf(shape: Shape, arcToleranceUm: Long): Toolpath? {
        val raw: List<PointUm>
        val closed: Boolean
        when (shape) {
            is Shape.Line -> { raw = listOf(shape.a, shape.b); closed = false }
            is Shape.Rect -> {
                val tl = shape.topLeft
                val tr = tl.copy(x = tl.x + shape.widthUm)
                val br = tr.copy(y = tl.y + shape.heightUm)
                val bl = tl.copy(y = tl.y + shape.heightUm)
                raw = listOf(tl, tr, br, bl, tl); closed = true
            }
            is Shape.Circle -> {
                val r = shape.radiusUm.raw
                if (r <= 0) return null
                val n = Geometry.circleSegmentCount(r, arcToleranceUm)
                val pts = ArrayList<PointUm>(n + 1)
                for (i in 0 until n) {
                    val angle = 2.0 * Math.PI * i / n
                    pts.add(
                        PointUm(
                            Micrometers(shape.center.x.raw + (r * cos(angle)).roundToLong()),
                            Micrometers(shape.center.y.raw + (r * sin(angle)).roundToLong()),
                        ),
                    )
                }
                pts.add(pts[0]) // close exactly, no rounding gap
                raw = pts; closed = true
            }
            is Shape.Polyline -> {
                raw = shape.points
                closed = shape.points.size >= 3 && shape.points.first().sameXY(shape.points.last())
            }
        }
        // Drop zero-length moves (consecutive duplicates).
        val pts = ArrayList<PointUm>(raw.size)
        for (p in raw) if (pts.isEmpty() || !pts.last().sameXY(p)) pts.add(p)
        if (closed && pts.size >= 2 && !pts.first().sameXY(pts.last())) pts.add(pts.first())
        return if (pts.size < 2) null else Toolpath(pts, closed)
    }

    /** Greedy nearest-neighbour from the machine origin; open paths may be reversed. */
    private fun orderNearestFirst(paths: List<Toolpath>, passCount: Int): List<Toolpath> {
        val remaining = paths.toMutableList()
        val ordered = ArrayList<Toolpath>(paths.size)
        var cx = 0L
        var cy = 0L
        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            var bestReverse = false
            for ((i, p) in remaining.withIndex()) {
                val d0 = dist2(cx, cy, p.points.first())
                if (d0 < bestDist) { bestDist = d0; bestIdx = i; bestReverse = false }
                if (!p.closed) {
                    val d1 = dist2(cx, cy, p.points.last())
                    if (d1 < bestDist) { bestDist = d1; bestIdx = i; bestReverse = true }
                }
            }
            val chosen = remaining.removeAt(bestIdx)
            val next = if (bestReverse) Toolpath(chosen.points.reversed(), false) else chosen
            ordered.add(next)
            // Where the tool ends up: closed -> start; open -> end after an odd pass count, else start.
            val endPoint = if (next.closed || passCount % 2 == 0) next.points.first() else next.points.last()
            cx = endPoint.x.raw
            cy = endPoint.y.raw
        }
        return ordered
    }

    private fun dist2(cx: Long, cy: Long, p: PointUm): Double {
        val dx = (p.x.raw - cx).toDouble()
        val dy = (p.y.raw - cy).toDouble()
        return dx * dx + dy * dy
    }

    private fun emitPath(sb: StringBuilder, path: Toolpath, s: JobSettings, passCount: Int) {
        val start = path.points.first()
        sb.appendLine("G0 X${start.x.toGcodeMm()} Y${start.y.toGcodeMm()}")
        for (pass in 1..passCount) {
            val depth = minOf(s.stepDownUm * pass, s.totalDepthUm)
            sb.appendLine("G1 Z${(-depth).toGcodeMm()} F${feed(s.plungeRateMmPerMin)}")
            val forward = path.closed || pass % 2 == 1
            val pts = if (forward) path.points else path.points.asReversed()
            for (k in 1 until pts.size) {
                val p = pts[k]
                val f = if (k == 1) " F${feed(s.feedRateMmPerMin)}" else "" // F is modal
                sb.appendLine("G1 X${p.x.toGcodeMm()} Y${p.y.toGcodeMm()}$f")
            }
        }
        sb.appendLine("G0 Z${s.safeHeightUm.toGcodeMm()}")
    }

    private fun feed(mmPerMin: Double): String =
        if (mmPerMin == Math.rint(mmPerMin)) mmPerMin.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", mmPerMin)
}
