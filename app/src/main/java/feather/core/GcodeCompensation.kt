package feather.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Applies a [HeightMap] to already-generated G-code so the tool follows the real surface instead
 * of an assumed flat one — the same approach as bCNC/Candle's "autolevel" and Marlin's mesh bed
 * levelling: long moves are cut into short segments, and each segment's Z is nudged by the map's
 * interpolated offset at its endpoint. The output is freshly emitted G-code (comments and original
 * line formatting are not preserved), always in millimetres and absolute coordinates.
 */
object GcodeCompensation {

    /** Moves longer than this are subdivided so the correction follows curved/tilted surfaces smoothly. */
    private const val MAX_SEGMENT_MM = 4.0

    fun applyToText(gcode: String, map: HeightMap): String = toGcode(apply(GcodeAnalysis.parse(gcode), map))

    fun apply(moves: List<Move>, map: HeightMap): List<Move> {
        val out = ArrayList<Move>(moves.size)
        for (m in moves) {
            val len = hypot(m.x1 - m.x0, m.y1 - m.y0)
            val steps = maxOf(1, ceil(len / MAX_SEGMENT_MM).toInt())
            var px = m.x0; var py = m.y0; var pz = m.z0 + map.offsetAt(m.x0, m.y0)
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                val x = m.x0 + (m.x1 - m.x0) * t
                val y = m.y0 + (m.y1 - m.y0) * t
                val z = m.z0 + (m.z1 - m.z0) * t + map.offsetAt(x, y)
                out += Move(px, py, pz, x, y, z, m.rapid, m.feedMmPerMin)
                px = x; py = y; pz = z
            }
        }
        return out
    }

    /** A clean, self-contained G-code file for [moves]: mm, absolute, one line per move. */
    fun toGcode(moves: List<Move>): String {
        val sb = StringBuilder()
        sb.append("G21\nG90\n")
        var lastFeed: Double? = null
        for (m in moves) {
            val word = if (m.rapid) "G0" else "G1"
            sb.append(word)
            sb.append(" X").append(num(m.x1)).append(" Y").append(num(m.y1)).append(" Z").append(num(m.z1))
            if (!m.rapid && m.feedMmPerMin != lastFeed) {
                sb.append(" F").append(num(m.feedMmPerMin))
                lastFeed = m.feedMmPerMin
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun num(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.rint(r)) r.toLong().toString() else String.format(Locale.ROOT, "%.3f", r).trimEnd('0').trimEnd('.')
    }
}
