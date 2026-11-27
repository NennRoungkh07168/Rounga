package feather.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Geometry produced by the touch Sketcher. All lengths are millimetres. */
data class CadSketchProfile(
    val kind: CadSolid.Kind,
    val widthMm: Double,
    val depthMm: Double,
) {
    init {
        require(kind == CadSolid.Kind.BOX || kind == CadSolid.Kind.CYLINDER)
        require(widthMm > 0.0 && depthMm > 0.0)
        if (kind == CadSolid.Kind.CYLINDER) require(kotlin.math.abs(widthMm - depthMm) < 1e-6)
    }

    fun toPad(name: String, id: Long, heightMm: Double): CadSolid {
        require(heightMm > 0.0)
        return CadSolid(
            id = id,
            name = name,
            kind = kind,
            width = widthMm,
            depth = depthMm,
            height = heightMm,
            feature = "Interactive 2D sketch → Pad (${heightMm} mm)"
        )
    }
}

/** Converts a drag rectangle in screen pixels into a 1 mm-snapped CAD profile. */
object CadSketchGeometry {
    fun snapMm(value: Double, gridMm: Double = 1.0): Double {
        require(gridMm > 0.0)
        return round(value / gridMm) * gridMm
    }

    fun rectangle(widthPx: Float, heightPx: Float, pixelsPerMm: Float): CadSketchProfile? {
        require(pixelsPerMm > 0f)
        val w = snapMm(abs(widthPx / pixelsPerMm).toDouble())
        val d = snapMm(abs(heightPx / pixelsPerMm).toDouble())
        return if (w >= 1.0 && d >= 1.0) CadSketchProfile(CadSolid.Kind.BOX, w, d) else null
    }

    fun circle(widthPx: Float, heightPx: Float, pixelsPerMm: Float): CadSketchProfile? {
        require(pixelsPerMm > 0f)
        val diameter = snapMm((min(abs(widthPx), abs(heightPx)) / pixelsPerMm).toDouble())
        return if (diameter >= 1.0) CadSketchProfile(CadSolid.Kind.CYLINDER, diameter, diameter) else null
    }

    fun horizontalLineLength(widthPx: Float, pixelsPerMm: Float): Double {
        require(pixelsPerMm > 0f)
        return max(0.0, snapMm(abs(widthPx / pixelsPerMm).toDouble()))
    }
}
