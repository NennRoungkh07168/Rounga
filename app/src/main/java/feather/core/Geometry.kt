package feather.core

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** Axis-aligned bounds in µm. */
data class Bounds(val minX: Long, val minY: Long, val maxX: Long, val maxY: Long) {
    val width: Long get() = maxX - minX
    val height: Long get() = maxY - minY
}

/** Two-point measurement, exact in µm. Y-up, so angle is counter-clockwise from +X. */
data class Measurement(val a: PointUm, val b: PointUm) {
    val dxUm: Long get() = b.x.raw - a.x.raw
    val dyUm: Long get() = b.y.raw - a.y.raw
    val lengthUm: Long get() = Math.round(hypot(dxUm.toDouble(), dyUm.toDouble()))
    val angleDegrees: Double get() = Math.toDegrees(atan2(dyUm.toDouble(), dxUm.toDouble()))
}

object Geometry {

    const val MIN_CIRCLE_SEGMENTS = 12
    const val MAX_CIRCLE_SEGMENTS = 2000

    fun distanceUm(a: PointUm, b: PointUm): Long =
        Math.round(hypot((b.x.raw - a.x.raw).toDouble(), (b.y.raw - a.y.raw).toDouble()))

    /**
     * Segments needed so a polygon inscribed in a circle of [radiusUm] never
     * deviates from it by more than [toleranceUm] (chord sagitta ≤ tolerance).
     */
    fun circleSegmentCount(radiusUm: Long, toleranceUm: Long): Int {
        if (radiusUm <= toleranceUm) return MIN_CIRCLE_SEGMENTS
        val halfStep = acos(1.0 - toleranceUm.toDouble() / radiusUm.toDouble())
        val n = ceil(Math.PI / halfStep).toInt()
        return n.coerceIn(MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS)
    }

    /**
     * Ramer–Douglas–Peucker simplification (iterative, so a 10,000-point
     * freehand stroke cannot overflow the stack). End points are always kept.
     */
    fun simplify(points: List<PointUm>, toleranceUm: Long): List<PointUm> {
        if (points.size < 3 || toleranceUm <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeAt(stack.size - 1)
            val lo = range[0]
            val hi = range[1]
            var maxDist = -1.0
            var maxIdx = -1
            for (i in lo + 1 until hi) {
                val d = perpendicularDistance(points[i], points[lo], points[hi])
                if (d > maxDist) { maxDist = d; maxIdx = i }
            }
            if (maxIdx != -1 && maxDist > toleranceUm) {
                keep[maxIdx] = true
                stack.add(intArrayOf(lo, maxIdx))
                stack.add(intArrayOf(maxIdx, hi))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicularDistance(p: PointUm, a: PointUm, b: PointUm): Double {
        val dx = (b.x.raw - a.x.raw).toDouble()
        val dy = (b.y.raw - a.y.raw).toDouble()
        val px = (p.x.raw - a.x.raw).toDouble()
        val py = (p.y.raw - a.y.raw).toDouble()
        val len = hypot(dx, dy)
        if (len == 0.0) return hypot(px, py)
        return abs(dx * py - dy * px) / len
    }

    fun bounds(shapes: List<Shape>): Bounds? {
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE
        var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        fun add(x: Long, y: Long) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        for (s in shapes) when (s) {
            is Shape.Line -> { add(s.a.x.raw, s.a.y.raw); add(s.b.x.raw, s.b.y.raw) }
            is Shape.Rect -> {
                add(s.topLeft.x.raw, s.topLeft.y.raw)
                add(s.topLeft.x.raw + s.widthUm.raw, s.topLeft.y.raw + s.heightUm.raw)
            }
            is Shape.Circle -> {
                add(s.center.x.raw - s.radiusUm.raw, s.center.y.raw - s.radiusUm.raw)
                add(s.center.x.raw + s.radiusUm.raw, s.center.y.raw + s.radiusUm.raw)
            }
            is Shape.Polyline -> s.points.forEach { add(it.x.raw, it.y.raw) }
        }
        return if (minX == Long.MAX_VALUE) null else Bounds(minX, minY, maxX, maxY)
    }
}
