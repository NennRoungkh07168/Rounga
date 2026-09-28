package feather.model

import java.util.Locale

/**
 * The live, single-shot commands the Machine and Tools screens send, chosen per
 * G-code dialect so "Home" means `$H` on GRBL and `G28` on Marlin.
 */
object MachineCommands {

    fun home(d: GcodeDialect): String = when (d) {
        GcodeDialect.GRBL -> "\$H"
        else -> "G28"
    }

    fun unlock(d: GcodeDialect): String = when (d) {
        GcodeDialect.GRBL -> "\$X"
        GcodeDialect.MARLIN -> "M999"
        else -> "M999"
    }

    /** Relative jog by ([dx],[dy],[dz]) millimetres at [feed] mm/min. Returns G-code lines to stream. */
    fun jog(d: GcodeDialect, dx: Double, dy: Double, dz: Double, feed: Double): String {
        val words = StringBuilder()
        if (dx != 0.0) words.append(" X").append(num(dx))
        if (dy != 0.0) words.append(" Y").append(num(dy))
        if (dz != 0.0) words.append(" Z").append(num(dz))
        val f = " F" + num(feed)
        return when (d) {
            GcodeDialect.GRBL -> "\$J=G21G91" + words.toString().replace(" ", "") + f.replace(" ", "")
            else -> "G91\nG0${words}$f\nG90"
        }
    }

    /** Make the current position the work origin (X0 Y0 [Z0]). */
    fun setOrigin(d: GcodeDialect, includeZ: Boolean): String {
        val axes = if (includeZ) "X0 Y0 Z0" else "X0 Y0"
        return when (d) {
            GcodeDialect.GRBL -> "G10 L20 P1 $axes"
            else -> "G92 $axes"
        }
    }

    fun setZeroZ(d: GcodeDialect): String = setZ(d, 0.0)

    /** Declare the current Z position to be [value] mm (used after probing a plate of known thickness). */
    fun setZ(d: GcodeDialect, value: Double): String = when (d) {
        GcodeDialect.GRBL -> "G10 L20 P1 Z${num(value)}"
        else -> "G92 Z${num(value)}"
    }

    /** Make the current position of one axis ("X" or "Y") zero, e.g. after touching an edge. */
    fun zeroAxis(d: GcodeDialect, axis: String): String = when (d) {
        GcodeDialect.GRBL -> "G10 L20 P1 ${axis}0"
        else -> "G92 ${axis}0"
    }

    /** Probe toward an edge along [axis] ("X" or "Y") by up to [distanceMm] (negative = toward -). */
    fun probeEdge(axis: String, distanceMm: Double, feed: Double): String =
        "G91\nG38.2 $axis${num(distanceMm)} F${num(feed)}\nG90"

    /** Probe down at most [maxDepthMm] and, on contact, the caller zeroes Z with [setZeroZ]. */
    fun probeZ(maxDepthMm: Double, feed: Double): String = "G91\nG38.2 Z-${num(maxDepthMm)} F${num(feed)}\nG90"

    fun goToOrigin(): String = "G90\nG0 X0 Y0"

    fun spindleOff(): String = "M5"

    private fun num(v: Double): String =
        if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
}
