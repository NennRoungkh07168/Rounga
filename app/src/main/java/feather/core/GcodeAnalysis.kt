package feather.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** One straight move of the tool, in millimetres. */
data class Move(
    val x0: Double, val y0: Double, val z0: Double,
    val x1: Double, val y1: Double, val z1: Double,
    val rapid: Boolean,
    val feedMmPerMin: Double,
) {
    val lengthMm: Double
        get() {
            val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
}

data class GcodeStats(
    val moves: Int,
    val cutLengthMm: Double,
    val rapidLengthMm: Double,
    val estimatedSeconds: Double,
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    val minZ: Double, val maxZ: Double,
    val lineCount: Int,
) {
    fun timeText(): String {
        val total = estimatedSeconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

/**
 * Reads the linear moves (G0/G1) out of G-code so the app can estimate time,
 * check bounds against the machine, and draw a simulation. Arcs (G2/G3) are
 * approximated by their chord; Feather's own generator never emits them.
 */
object GcodeAnalysis {

    private const val DEFAULT_FEED = 1000.0

    fun parse(gcode: String): List<Move> {
        val moves = ArrayList<Move>()
        var x = 0.0; var y = 0.0; var z = 0.0
        var feed = DEFAULT_FEED
        var rapid = true
        var absolute = true
        var inches = false

        for (raw in gcode.lineSequence()) {
            val line = GcodeText.stripComments(raw).uppercase(Locale.ROOT)
            if (line.isEmpty() || line.startsWith("$")) continue
            var nx: Double? = null; var ny: Double? = null; var nz: Double? = null
            var motion = false
            for (word in splitWords(line)) {
                val letter = word[0]
                val value = word.substring(1).toDoubleOrNull() ?: continue
                when (letter) {
                    'G' -> when (value.toInt()) {
                        0 -> { rapid = true; motion = true }
                        1, 2, 3 -> { rapid = false; motion = true }
                        20 -> inches = true
                        21 -> inches = false
                        90 -> absolute = true
                        91 -> absolute = false
                    }
                    'X' -> nx = value
                    'Y' -> ny = value
                    'Z' -> nz = value
                    'F' -> feed = value * (if (inches) 25.4 else 1.0)
                }
            }
            if (!motion && nx == null && ny == null && nz == null) continue
            val k = if (inches) 25.4 else 1.0
            val tx = if (nx == null) x else if (absolute) nx * k else x + nx * k
            val ty = if (ny == null) y else if (absolute) ny * k else y + ny * k
            val tz = if (nz == null) z else if (absolute) nz * k else z + nz * k
            if (tx != x || ty != y || tz != z) {
                moves.add(Move(x, y, z, tx, ty, tz, rapid, feed))
            }
            x = tx; y = ty; z = tz
        }
        return moves
    }

    /** "G1", "X10.5", "F800" ... from a line with or without spaces ("G1X10Y5"). */
    private fun splitWords(line: String): List<String> {
        val words = ArrayList<String>()
        var start = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch.isLetter()) {
                if (start >= 0) words.add(line.substring(start, i).trim())
                start = i
            } else if (ch == ' ' && start >= 0) {
                words.add(line.substring(start, i).trim())
                start = -1
            }
        }
        if (start >= 0) words.add(line.substring(start).trim())
        return words.filter { it.length > 1 }
    }

    /**
     * @param rapidMmPerMin speed assumed for G0 moves (the machine's max feed)
     */
    fun stats(moves: List<Move>, lineCount: Int, rapidMmPerMin: Double = 3000.0): GcodeStats {
        var cut = 0.0
        var rapid = 0.0
        var seconds = 0.0
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        fun visit(x: Double, y: Double, z: Double) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        for (m in moves) {
            visit(m.x0, m.y0, m.z0); visit(m.x1, m.y1, m.z1)
            val len = m.lengthMm
            if (m.rapid) {
                rapid += len
                seconds += len / maxOf(rapidMmPerMin, 1.0) * 60.0
            } else {
                cut += len
                seconds += len / maxOf(m.feedMmPerMin, 1.0) * 60.0
            }
        }
        if (moves.isEmpty()) { minX = 0.0; minY = 0.0; maxX = 0.0; maxY = 0.0; minZ = 0.0; maxZ = 0.0 }
        return GcodeStats(moves.size, cut, rapid, seconds, minX, minY, maxX, maxY, minZ, maxZ, lineCount)
    }

    /** Human-readable problems when [stats] falls outside a bed of the given travel (origin at 0,0). */
    fun boundsProblems(stats: GcodeStats, travelXmm: Double, travelYmm: Double): List<String> {
        val problems = ArrayList<String>()
        val eps = 0.001
        if (stats.moves == 0) return listOf("The G-code contains no moves.")
        if (stats.minX < -eps || stats.maxX > travelXmm + eps) {
            problems += "X runs from ${fmt(stats.minX)} to ${fmt(stats.maxX)} mm but the bed is ${fmt(travelXmm)} mm wide."
        }
        if (stats.minY < -eps || stats.maxY > travelYmm + eps) {
            problems += "Y runs from ${fmt(stats.minY)} to ${fmt(stats.maxY)} mm but the bed is ${fmt(travelYmm)} mm deep."
        }
        return problems
    }

    /** Distance along the whole path, used to scrub the simulation. */
    fun totalLength(moves: List<Move>): Double = moves.sumOf { abs(it.lengthMm) }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
}
