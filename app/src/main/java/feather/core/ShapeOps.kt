package feather.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Edit operations on [Shape]s. Everything stays in integer µm; results are rounded once per point. */
object ShapeOps {

    private fun pt(p: PointUm, x: Long, y: Long) = p.copy(x = Micrometers(x), y = Micrometers(y))

    fun translate(s: Shape, dxUm: Long, dyUm: Long): Shape = when (s) {
        is Shape.Line -> Shape.Line(shift(s.a, dxUm, dyUm), shift(s.b, dxUm, dyUm), s.colorArgb, s.hidden)
        is Shape.Rect -> Shape.Rect(shift(s.topLeft, dxUm, dyUm), s.widthUm, s.heightUm, s.colorArgb, s.hidden)
        is Shape.Circle -> Shape.Circle(shift(s.center, dxUm, dyUm), s.radiusUm, s.colorArgb, s.hidden)
        is Shape.Polyline -> Shape.Polyline(s.points.map { shift(it, dxUm, dyUm) }, s.colorArgb, s.hidden)
    }

    private fun shift(p: PointUm, dx: Long, dy: Long) = pt(p, Math.addExact(p.x.raw, dx), Math.addExact(p.y.raw, dy))

    /** Uniform scale about ([cx],[cy]). */
    fun scale(s: Shape, cx: Long, cy: Long, factor: Double): Shape {
        require(factor.isFinite() && factor > 0.0) { "scale factor must be positive" }
        fun sc(p: PointUm) = pt(
            p,
            cx + Math.round((p.x.raw - cx) * factor),
            cy + Math.round((p.y.raw - cy) * factor),
        )
        return when (s) {
            is Shape.Line -> Shape.Line(sc(s.a), sc(s.b), s.colorArgb, s.hidden)
            is Shape.Rect -> {
                val a = sc(s.topLeft)
                Shape.Rect(a, Micrometers(Math.round(s.widthUm.raw * factor)), Micrometers(Math.round(s.heightUm.raw * factor)), s.colorArgb, s.hidden)
            }
            is Shape.Circle -> Shape.Circle(sc(s.center), Micrometers(Math.round(s.radiusUm.raw * factor)), s.colorArgb, s.hidden)
            is Shape.Polyline -> Shape.Polyline(s.points.map { sc(it) }, s.colorArgb, s.hidden)
        }
    }

    /** Rotate counter-clockwise (world is Y-up) about ([cx],[cy]). Rectangles become polylines. */
    fun rotate(s: Shape, cx: Long, cy: Long, degrees: Double): Shape {
        val rad = Math.toRadians(degrees)
        val c = cos(rad)
        val sn = sin(rad)
        fun rot(p: PointUm): PointUm {
            val dx = (p.x.raw - cx).toDouble()
            val dy = (p.y.raw - cy).toDouble()
            return pt(p, cx + Math.round(dx * c - dy * sn), cy + Math.round(dx * sn + dy * c))
        }
        return when (s) {
            is Shape.Line -> Shape.Line(rot(s.a), rot(s.b), s.colorArgb, s.hidden)
            is Shape.Circle -> Shape.Circle(rot(s.center), s.radiusUm, s.colorArgb, s.hidden)
            is Shape.Polyline -> Shape.Polyline(s.points.map { rot(it) }, s.colorArgb, s.hidden)
            is Shape.Rect -> Shape.Polyline(rectCorners(s).map { rot(it) }, s.colorArgb, s.hidden)
        }
    }

    /** Mirror across a vertical line at [axisX] (horizontal = true) or a horizontal line at [axisY]. */
    fun mirror(s: Shape, axisX: Long, axisY: Long, horizontal: Boolean): Shape {
        fun mp(p: PointUm) =
            if (horizontal) pt(p, 2 * axisX - p.x.raw, p.y.raw) else pt(p, p.x.raw, 2 * axisY - p.y.raw)
        return when (s) {
            is Shape.Line -> Shape.Line(mp(s.a), mp(s.b), s.colorArgb, s.hidden)
            is Shape.Circle -> Shape.Circle(mp(s.center), s.radiusUm, s.colorArgb, s.hidden)
            is Shape.Polyline -> Shape.Polyline(s.points.map { mp(it) }, s.colorArgb, s.hidden)
            is Shape.Rect -> {
                val a = mp(s.topLeft)
                val b = mp(PointUm(Micrometers(s.topLeft.x.raw + s.widthUm.raw), Micrometers(s.topLeft.y.raw + s.heightUm.raw)))
                Shape.Rect(
                    PointUm(Micrometers(minOf(a.x.raw, b.x.raw)), Micrometers(minOf(a.y.raw, b.y.raw))),
                    s.widthUm,
                    s.heightUm,
                    s.colorArgb,
                    s.hidden,
                )
            }
        }
    }

    /** Simplify polylines only; other shapes are already minimal. */
    fun simplify(s: Shape, toleranceUm: Long): Shape =
        if (s is Shape.Polyline) {
            val out = Geometry.simplify(s.points, toleranceUm)
            if (out.size >= 2) Shape.Polyline(out, s.colorArgb, s.hidden) else s
        } else s

    fun vertexCount(s: Shape): Int = when (s) {
        is Shape.Line -> 2
        is Shape.Rect -> 4
        is Shape.Circle -> 1
        is Shape.Polyline -> s.points.size
    }

    private fun rectCorners(r: Shape.Rect): List<PointUm> {
        val tl = r.topLeft
        val tr = tl.copy(x = Micrometers(tl.x.raw + r.widthUm.raw))
        val br = tr.copy(y = Micrometers(tl.y.raw + r.heightUm.raw))
        val bl = tl.copy(y = Micrometers(tl.y.raw + r.heightUm.raw))
        return listOf(tl, tr, br, bl, tl)
    }

    // ---- Hit testing ---------------------------------------------------------------

    /** Distance in µm from [p] to the outline of [s]. */
    fun distanceUm(s: Shape, p: PointUm): Double = when (s) {
        is Shape.Line -> segmentDistance(p, s.a, s.b)
        is Shape.Circle -> Math.abs(hypot((p.x.raw - s.center.x.raw).toDouble(), (p.y.raw - s.center.y.raw).toDouble()) - s.radiusUm.raw)
        is Shape.Rect -> pathDistance(p, rectCorners(s))
        is Shape.Polyline -> pathDistance(p, s.points)
    }

    private fun pathDistance(p: PointUm, pts: List<PointUm>): Double {
        if (pts.isEmpty()) return Double.MAX_VALUE
        if (pts.size == 1) return hypot((p.x.raw - pts[0].x.raw).toDouble(), (p.y.raw - pts[0].y.raw).toDouble())
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val d = segmentDistance(p, pts[i], pts[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun segmentDistance(p: PointUm, a: PointUm, b: PointUm): Double {
        val abx = (b.x.raw - a.x.raw).toDouble()
        val aby = (b.y.raw - a.y.raw).toDouble()
        val apx = (p.x.raw - a.x.raw).toDouble()
        val apy = (p.y.raw - a.y.raw).toDouble()
        val len2 = abx * abx + aby * aby
        val t = if (len2 == 0.0) 0.0 else ((apx * abx + apy * aby) / len2).coerceIn(0.0, 1.0)
        return hypot(apx - t * abx, apy - t * aby)
    }

    /** Index of the shape whose outline is nearest [p] within [toleranceUm], or -1. Later shapes win ties (drawn on top).
     *  Hidden shapes (Layers panel) are skipped — you can't select what you can't see. */
    fun hitTest(shapes: List<Shape>, p: PointUm, toleranceUm: Double): Int {
        var best = -1
        var bestDist = toleranceUm
        for (i in shapes.indices) {
            if (shapes[i].hidden) continue
            val d = distanceUm(shapes[i], p)
            if (d <= bestDist) { best = i; bestDist = d }
        }
        return best
    }
}
