package feather.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Exact-dimension shapes that are stored as ordinary polylines (so every existing edit and export works on them). */
object Primitives {

    /**
     * Circular arc by centre and DIAMETER. [startDeg] is measured from +X, counter-clockwise (Y-up); a positive
     * [sweepDeg] goes counter-clockwise. A sweep of 360 or more closes into a full circle.
     */
    fun arc(
        cxMm: Double, cyMm: Double, diameterMm: Double, startDeg: Double, sweepDeg: Double, arcToleranceUm: Long,
    ): Shape.Polyline {
        val radiusUm = Math.round(diameterMm * 500.0)
        val sweep = sweepDeg.coerceIn(-360.0, 360.0)
        val full = Geometry.circleSegmentCount(radiusUm, arcToleranceUm)
        val n = maxOf(4, ceil(abs(sweep) / 360.0 * full).toInt())
        val r = diameterMm / 2.0
        val pts = ArrayList<PointUm>(n + 1)
        for (i in 0..n) {
            val a = Math.toRadians(startDeg + sweep * i / n)
            pts.add(pt(cxMm + r * cos(a), cyMm + r * sin(a)))
        }
        if (abs(sweep) >= 360.0) pts[n] = pts[0] // close exactly, no rounding gap
        return Shape.Polyline(pts)
    }

    /** Regular polygon; [diameterMm] is across opposite corners, [rotationDeg] turns the first corner from +X. */
    fun regularPolygon(
        cxMm: Double, cyMm: Double, diameterMm: Double, sides: Int, rotationDeg: Double,
    ): Shape.Polyline {
        require(sides in 3..64) { "A polygon needs 3 to 64 sides." }
        val r = diameterMm / 2.0
        val pts = ArrayList<PointUm>(sides + 1)
        for (i in 0 until sides) {
            val a = Math.toRadians(rotationDeg + 360.0 * i / sides)
            pts.add(pt(cxMm + r * cos(a), cyMm + r * sin(a)))
        }
        pts.add(pts[0])
        return Shape.Polyline(pts)
    }

    private fun pt(xMm: Double, yMm: Double) =
        PointUm(Micrometers.fromMillimeters(xMm), Micrometers.fromMillimeters(yMm))
}
