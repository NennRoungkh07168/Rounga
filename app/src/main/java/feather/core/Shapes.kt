package feather.core

/**
 * Drawing primitives, all in integer µm, world space is Y-up (machine bed).
 * For [Rect], `topLeft` is the minimum-X / minimum-Y corner (the name is kept
 * so `.feather` files written by earlier versions still load unchanged).
 */
sealed class Shape {
    /** Drawn color (ARGB). Default black. Kept per-shape so it survives undo/redo/reorder for free. */
    abstract val colorArgb: Int
    /** Excluded from rendering, hit-testing and G-code export when true — a "layer" visibility toggle. */
    abstract val hidden: Boolean

    data class Line(val a: PointUm, val b: PointUm, override val colorArgb: Int = DEFAULT_COLOR, override val hidden: Boolean = false) : Shape()
    data class Rect(val topLeft: PointUm, val widthUm: Micrometers, val heightUm: Micrometers, override val colorArgb: Int = DEFAULT_COLOR, override val hidden: Boolean = false) : Shape()
    data class Circle(val center: PointUm, val radiusUm: Micrometers, override val colorArgb: Int = DEFAULT_COLOR, override val hidden: Boolean = false) : Shape()
    data class Polyline(val points: List<PointUm>, override val colorArgb: Int = DEFAULT_COLOR, override val hidden: Boolean = false) : Shape()

    companion object {
        const val DEFAULT_COLOR = -0x1000000 // 0xFF000000.toInt(), opaque black
    }

    /** Returns a copy of this shape with a new color, whatever its concrete type. */
    fun withColor(argb: Int): Shape = when (this) {
        is Line -> copy(colorArgb = argb)
        is Rect -> copy(colorArgb = argb)
        is Circle -> copy(colorArgb = argb)
        is Polyline -> copy(colorArgb = argb)
    }

    /** Returns a copy of this shape with a new hidden flag, whatever its concrete type. */
    fun withHidden(h: Boolean): Shape = when (this) {
        is Line -> copy(hidden = h)
        is Rect -> copy(hidden = h)
        is Circle -> copy(hidden = h)
        is Polyline -> copy(hidden = h)
    }
}

/** Everything the operator can tune about a job without redrawing anything. */
data class JobSettings(
    val totalDepthUm: Micrometers = Micrometers.fromMillimeters(2.0),
    /** Depth removed per pass; must be a multiple of the 0.02 mm Z resolution. */
    val stepDownUm: Micrometers = Micrometers.Z_STEP,
    val feedRateMmPerMin: Double = 800.0,
    val plungeRateMmPerMin: Double = 200.0,
    val safeHeightUm: Micrometers = Micrometers.fromMillimeters(5.0),
    /** Max deviation (µm) between a true circle and its polygon approximation. */
    val arcToleranceUm: Long = 5L,
    /** Reorder paths (nearest-first) to cut rapid travel. Cut result is identical. */
    val optimizeTravel: Boolean = true,
) {
    /** Human-readable problems; empty means the settings are safe to run. */
    fun validationErrors(): List<String> {
        val errors = ArrayList<String>()
        val z = Micrometers.Z_STEP.raw
        if (totalDepthUm.raw <= 0) errors += "Total depth must be greater than 0."
        else if (totalDepthUm.raw > MAX_LENGTH_UM) errors += "Total depth is unrealistically large."
        else if (!totalDepthUm.isMultipleOfZStep) errors += "Total depth must be a multiple of 0.02 mm (Z resolution)."
        if (stepDownUm.raw < z) errors += "Step-down cannot be finer than 0.02 mm."
        else if (stepDownUm.raw > MAX_LENGTH_UM) errors += "Step-down is unrealistically large."
        else if (!stepDownUm.isMultipleOfZStep) errors += "Step-down must be a multiple of 0.02 mm."
        if (!feedRateMmPerMin.isFinite() || feedRateMmPerMin <= 0.0 || feedRateMmPerMin > MAX_FEED) errors += "Feed rate must be between 0 and $MAX_FEED mm/min."
        if (!plungeRateMmPerMin.isFinite() || plungeRateMmPerMin <= 0.0 || plungeRateMmPerMin > MAX_FEED) errors += "Plunge rate must be between 0 and $MAX_FEED mm/min."
        if (safeHeightUm.raw <= 0 || safeHeightUm.raw > MAX_LENGTH_UM) errors += "Safe height must be between 0 and 500 mm."
        if (arcToleranceUm !in 1L..1000L) errors += "Arc tolerance must be 1-1000 µm."
        return errors
    }

    companion object {
        const val MAX_LENGTH_UM = 500_000L // 500 mm
        const val MAX_FEED = 50_000.0
    }
}

data class CutJob(val shapes: List<Shape>, val settings: JobSettings = JobSettings())
