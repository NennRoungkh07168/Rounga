package feather.core

import kotlin.math.abs

/**
 * All geometry in Feather is stored as integer micrometres (µm), never as
 * floating-point millimetres.
 *
 *  - A Double in mm drifts as operations accumulate; a Long in µm is exact for
 *    any value up to ~9.2e12 µm (9,200 km).
 *  - Arithmetic here is overflow-checked (`Math.addExact` etc.): an impossible
 *    coordinate fails loudly instead of silently wrapping around.
 *  - Millimetre *text* is produced by [toGcodeMm] using integer maths only, so
 *    it is exact and independent of the device locale (a German-locale phone
 *    must never emit "1,250" into G-code).
 */
@JvmInline
value class Micrometers(val raw: Long) : Comparable<Micrometers> {

    fun toMillimeters(): Double = raw / 1000.0
    fun toMicrons(): Long = raw

    operator fun plus(other: Micrometers) = Micrometers(Math.addExact(raw, other.raw))
    operator fun minus(other: Micrometers) = Micrometers(Math.subtractExact(raw, other.raw))
    operator fun unaryMinus() = Micrometers(Math.negateExact(raw))
    operator fun times(scalar: Int) = Micrometers(Math.multiplyExact(raw, scalar.toLong()))
    override fun compareTo(other: Micrometers) = raw.compareTo(other.raw)

    val absoluteValue: Micrometers get() = Micrometers(abs(raw))
    val isMultipleOfZStep: Boolean get() = raw % Z_STEP.raw == 0L

    /** Nearest multiple of [step] (ties round up). */
    fun roundedToMultipleOf(step: Micrometers): Micrometers {
        require(step.raw > 0) { "step must be positive" }
        return Micrometers(Math.floorDiv(raw + step.raw / 2, step.raw) * step.raw)
    }

    /**
     * Exact millimetre text with 3 decimals (1 µm resolution), e.g. `-0.005`,
     * `20.000`. Pure integer maths, locale-independent.
     */
    fun toGcodeMm(): String {
        val sign = if (raw < 0) "-" else ""
        val a = abs(raw)
        return "$sign${a / 1000}.${(a % 1000).toString().padStart(3, '0')}"
    }

    companion object {
        fun fromMillimeters(mm: Double): Micrometers {
            require(mm.isFinite()) { "Millimetre value must be finite" }
            return Micrometers(Math.round(mm * 1000.0))
        }
        fun fromMicrons(um: Long): Micrometers = Micrometers(um)
        val ZERO = Micrometers(0)

        /** The machine's smallest addressable Z step, per spec: 0.02 mm = 20 µm. */
        val Z_STEP = Micrometers(20)
    }
}

data class PointUm(val x: Micrometers, val y: Micrometers, val z: Micrometers = Micrometers.ZERO) {
    fun sameXY(other: PointUm): Boolean = x == other.x && y == other.y
}

/**
 * Measured screen scale: device pixels per millimetre at 100 % zoom. Use a
 * caliper/ruler-measured value rather than the OS-reported DPI, which is
 * frequently wrong. Immutable — calibration operations return a new instance.
 */
data class BoardCalibration(val pixelsPerMillimeterAt100Pct: Double) {

    init {
        require(pixelsPerMillimeterAt100Pct.isFinite() && pixelsPerMillimeterAt100Pct > 0.0) {
            "Pixels-per-millimetre must be a positive number"
        }
    }

    /** New calibration from a known physical length and its measured pixel length at 100 % zoom. */
    fun calibrate(knownLengthMm: Double, measuredPixelsAt100Pct: Double): BoardCalibration {
        require(knownLengthMm > 0.0) { "Calibration length must be positive" }
        return BoardCalibration(measuredPixelsAt100Pct / knownLengthMm)
    }

    /**
     * Correct the scale after the user compared what the app *displays* for a
     * segment ([displayedMm]) with what a physical ruler held against the
     * screen shows for the same segment ([actualMm]).
     */
    fun correctedBy(displayedMm: Double, actualMm: Double): BoardCalibration {
        require(displayedMm > 0.0 && actualMm > 0.0) { "Lengths must be positive" }
        return BoardCalibration(pixelsPerMillimeterAt100Pct * displayedMm / actualMm)
    }
}

/**
 * The visible window onto the drawing board.
 *
 * World space is **Y-up** like the machine bed (so a drawing is never
 * mirrored when engraved); screen space is Y-down. [originXUm]/[originYUm] is
 * the world position of the *top-left* screen corner.
 */
data class Viewport(
    val zoom: Double = 1.0,
    val originXUm: Long = 0L,
    val originYUm: Long = 0L,
) {
    init { require(zoom.isFinite() && zoom > 0.0) { "zoom must be positive" } }

    fun pxPerMm(cal: BoardCalibration): Double = cal.pixelsPerMillimeterAt100Pct * zoom

    fun worldToScreenX(x: Micrometers, cal: BoardCalibration): Double =
        (x.raw - originXUm) / 1000.0 * pxPerMm(cal)

    fun worldToScreenY(y: Micrometers, cal: BoardCalibration): Double =
        (originYUm - y.raw) / 1000.0 * pxPerMm(cal)

    fun screenToWorld(sx: Double, sy: Double, cal: BoardCalibration): PointUm {
        val s = pxPerMm(cal)
        return PointUm(
            Micrometers(originXUm + Math.round(sx / s * 1000.0)),
            Micrometers(originYUm - Math.round(sy / s * 1000.0)),
        )
    }

    /** Zoom by [factor] keeping the world point under screen position ([fx],[fy]) fixed. */
    fun zoomAbout(factor: Double, fx: Double, fy: Double, cal: BoardCalibration): Viewport {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return this
        val s = pxPerMm(cal)
        val s2 = cal.pixelsPerMillimeterAt100Pct * newZoom
        val wx = originXUm + fx / s * 1000.0
        val wy = originYUm - fy / s * 1000.0
        return Viewport(
            zoom = newZoom,
            originXUm = Math.round(wx - fx / s2 * 1000.0),
            originYUm = Math.round(wy + fy / s2 * 1000.0),
        )
    }

    /** Drag the board so its content follows a finger that moved ([dx],[dy]) screen pixels. */
    fun panByPx(dx: Double, dy: Double, cal: BoardCalibration): Viewport {
        val s = pxPerMm(cal)
        return copy(
            originXUm = originXUm - Math.round(dx / s * 1000.0),
            originYUm = originYUm + Math.round(dy / s * 1000.0),
        )
    }

    /** Smallest "nice" grid pitch (µm) that stays at least [minPx] apart on screen. */
    fun gridSpacingUm(cal: BoardCalibration, minPx: Double = 20.0): Long {
        val s = pxPerMm(cal)
        return NICE_SPACINGS_UM.firstOrNull { it / 1000.0 * s >= minPx } ?: NICE_SPACINGS_UM.last()
    }

    companion object {
        const val MIN_ZOOM = 0.02
        const val MAX_ZOOM = 400.0
        private val NICE_SPACINGS_UM = longArrayOf(
            10, 50, 100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000,
        ).toList()
    }
}

/** Round [value] to the nearest multiple of [grid] µm. */
fun snapToGrid(value: Micrometers, grid: Long): Micrometers {
    require(grid > 0) { "grid must be positive" }
    return Micrometers(Math.floorDiv(value.raw + grid / 2, grid) * grid)
}
