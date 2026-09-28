package feather.core

/** One probed point: the machine's real Z at a bed position, in millimetres. */
data class ProbePoint(val xMm: Double, val yMm: Double, val zMm: Double)

/**
 * A regular grid of probed heights across the bed, for auto-levelling: material and beds are
 * rarely perfectly flat, so a tool zeroed at one corner can be too high or too low elsewhere.
 * This is the same technique CNC "auto-level" tools (bCNC, Candle, Marlin's mesh bed levelling)
 * use — probe a grid, then bilinear-interpolate between the four nearest points for any (x, y).
 *
 * Offsets are relative to the mean of all probed points, so a flat-but-tilted bed produces small
 * plus/minus corrections rather than shifting the whole job up or down.
 */
class HeightMap(val points: List<ProbePoint>, val cols: Int, val rows: Int) {

    init {
        require(cols >= 2 && rows >= 2) { "a height map needs at least a 2x2 grid" }
        require(points.size == cols * rows) { "expected ${cols * rows} points, got ${points.size}" }
    }

    // Row-major: points[row * cols + col]. Columns share x within a row's scan order; grid need not be
    // perfectly axis-aligned in general, but this app always probes an axis-aligned rectangle.
    private val xs: DoubleArray = DoubleArray(cols) { c -> points[c].xMm }
    private val ys: DoubleArray = DoubleArray(rows) { r -> points[r * cols].yMm }
    private val meanZ: Double = points.sumOf { it.zMm } / points.size

    val minZ: Double get() = points.minOf { it.zMm }
    val maxZ: Double get() = points.maxOf { it.zMm }

    /** How far off-flat this point is, relative to the map's average height. */
    fun offsetAt(xMm: Double, yMm: Double): Double {
        val ci = columnIndexBefore(xMm)
        val ri = rowIndexBefore(yMm)
        val x0 = xs[ci]; val x1 = xs[minOf(ci + 1, cols - 1)]
        val y0 = ys[ri]; val y1 = ys[minOf(ri + 1, rows - 1)]
        val tx = if (x1 > x0) ((xMm - x0) / (x1 - x0)).coerceIn(0.0, 1.0) else 0.0
        val ty = if (y1 > y0) ((yMm - y0) / (y1 - y0)).coerceIn(0.0, 1.0) else 0.0
        fun z(r: Int, c: Int) = points[r * cols + c].zMm
        val top = z(ri, ci) * (1 - tx) + z(ri, minOf(ci + 1, cols - 1)) * tx
        val bottom = z(minOf(ri + 1, rows - 1), ci) * (1 - tx) + z(minOf(ri + 1, rows - 1), minOf(ci + 1, cols - 1)) * tx
        val z = top * (1 - ty) + bottom * ty
        return z - meanZ
    }

    private fun columnIndexBefore(x: Double): Int {
        var i = 0
        while (i < cols - 2 && xs[i + 1] <= x) i++
        return i
    }

    private fun rowIndexBefore(y: Double): Int {
        var i = 0
        while (i < rows - 2 && ys[i + 1] <= y) i++
        return i
    }

    companion object {
        /** Evenly spaced probe targets, [minX]..[maxX] by [minY]..[maxY], row-major (matches the point order [HeightMap] expects). */
        fun gridTargets(minX: Double, minY: Double, maxX: Double, maxY: Double, cols: Int, rows: Int): List<Pair<Double, Double>> {
            require(cols >= 2 && rows >= 2 && maxX > minX && maxY > minY)
            val out = ArrayList<Pair<Double, Double>>(cols * rows)
            for (r in 0 until rows) {
                val y = minY + (maxY - minY) * r / (rows - 1)
                for (c in 0 until cols) {
                    val x = minX + (maxX - minX) * c / (cols - 1)
                    out += x to y
                }
            }
            return out
        }
    }
}
