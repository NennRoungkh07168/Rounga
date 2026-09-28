package feather.core

import kotlin.math.max
import kotlin.math.min

/**
 * Photo -> vector outlines, with no Android dependency so it is unit-testable.
 *
 * Pipeline: grayscale pixels -> [threshold] (auto via [otsu]) -> [contours]
 * (marching squares, stitched into closed loops) -> [toShapes] (drop specks,
 * simplify, scale to real millimetres, flip to the machine's Y-up space).
 */
object ImageTracer {

    /** Pixels with gray <= this value are "ink" when not inverted. Falls back to 127 for a flat image. */
    fun otsu(gray: IntArray): Int {
        if (gray.isEmpty()) return 127
        val hist = IntArray(256)
        for (g in gray) hist[g.coerceIn(0, 255)]++
        val total = gray.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var weightBack = 0.0
        var sumBack = 0.0
        var best = -1.0
        var threshold = 127
        for (t in 0..255) {
            weightBack += hist[t]
            if (weightBack == 0.0) continue
            val weightFront = total - weightBack
            if (weightFront == 0.0) break
            sumBack += t * hist[t].toDouble()
            val meanBack = sumBack / weightBack
            val meanFront = (sumAll - sumBack) / weightFront
            val between = weightBack * weightFront * (meanBack - meanFront) * (meanBack - meanFront)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** true = ink. Dark pixels are ink unless [invert] (light-on-dark artwork). */
    fun threshold(gray: IntArray, level: Int, invert: Boolean): BooleanArray =
        BooleanArray(gray.size) { i -> if (invert) gray[i] > level else gray[i] <= level }

    /** A loop of vertices in doubled pixel coordinates (x2, y2): divide by 2 for pixels. Y is down. */
    class Loop(val xs: IntArray, val ys: IntArray) {
        val size: Int get() = xs.size
    }

    /**
     * Marching squares over a mask padded by one empty pixel, so every loop closes.
     * Every crossed cell edge is shared by exactly two cells, so every vertex has
     * degree two and stitching segments always yields closed loops.
     */
    fun contours(mask: BooleanArray, w: Int, h: Int): List<Loop> {
        require(w > 0 && h > 0 && mask.size == w * h) { "mask size does not match ${w}x$h" }
        fun inside(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && mask[y * w + x]
        fun key(x2: Int, y2: Int): Long = (x2.toLong() shl 32) or (y2.toLong() and 0xFFFFFFFFL)

        // Segment endpoints, two entries per segment.
        val segX = ArrayList<Int>()
        val segY = ArrayList<Int>()
        fun seg(ax: Int, ay: Int, bx: Int, by: Int) {
            segX.add(ax); segY.add(ay); segX.add(bx); segY.add(by)
        }

        for (y in -1 until h) {
            for (x in -1 until w) {
                val c = (if (inside(x, y)) 8 else 0) or (if (inside(x + 1, y)) 4 else 0) or
                    (if (inside(x + 1, y + 1)) 2 else 0) or (if (inside(x, y + 1)) 1 else 0)
                if (c == 0 || c == 15) continue
                // Edge midpoints in doubled coordinates.
                val tx = 2 * x + 1; val ty = 2 * y          // top
                val rx = 2 * x + 2; val ry = 2 * y + 1      // right
                val bx = 2 * x + 1; val by = 2 * y + 2      // bottom
                val lx = 2 * x;     val ly = 2 * y + 1      // left
                when (c) {
                    1 -> seg(lx, ly, bx, by)
                    2 -> seg(bx, by, rx, ry)
                    3 -> seg(lx, ly, rx, ry)
                    4 -> seg(tx, ty, rx, ry)
                    5 -> { seg(tx, ty, lx, ly); seg(bx, by, rx, ry) }
                    6 -> seg(tx, ty, bx, by)
                    7 -> seg(tx, ty, lx, ly)
                    8 -> seg(tx, ty, lx, ly)
                    9 -> seg(tx, ty, bx, by)
                    10 -> { seg(tx, ty, rx, ry); seg(lx, ly, bx, by) }
                    11 -> seg(tx, ty, rx, ry)
                    12 -> seg(lx, ly, rx, ry)
                    13 -> seg(bx, by, rx, ry)
                    14 -> seg(lx, ly, bx, by)
                }
            }
        }

        val segCount = segX.size / 2
        val adjacency = HashMap<Long, MutableList<Int>>(segCount * 2)
        for (i in 0 until segCount) {
            adjacency.getOrPut(key(segX[2 * i], segY[2 * i])) { ArrayList(2) }.add(i)
            adjacency.getOrPut(key(segX[2 * i + 1], segY[2 * i + 1])) { ArrayList(2) }.add(i)
        }

        val used = BooleanArray(segCount)
        val loops = ArrayList<Loop>()
        for (s in 0 until segCount) {
            if (used[s]) continue
            used[s] = true
            val startX = segX[2 * s]; val startY = segY[2 * s]
            var curX = segX[2 * s + 1]; var curY = segY[2 * s + 1]
            val xs = ArrayList<Int>()
            val ys = ArrayList<Int>()
            xs.add(startX); ys.add(startY)
            var closed = true
            while (curX != startX || curY != startY) {
                xs.add(curX); ys.add(curY)
                var next = -1
                for (j in adjacency[key(curX, curY)] ?: emptyList<Int>()) {
                    if (!used[j]) { next = j; break }
                }
                if (next < 0) { closed = false; break } // cannot happen on a padded mask; be safe anyway
                used[next] = true
                if (segX[2 * next] == curX && segY[2 * next] == curY) {
                    curX = segX[2 * next + 1]; curY = segY[2 * next + 1]
                } else {
                    curX = segX[2 * next]; curY = segY[2 * next]
                }
            }
            if (closed) loops.add(Loop(xs.toIntArray(), ys.toIntArray()))
        }
        return loops
    }

    /**
     * Turn loops into closed polylines in the drawing's coordinate space.
     *
     * @param widthMm    real-world width the whole picture should span
     * @param originXUm  world X of the picture's left edge
     * @param originYUm  world Y of the picture's bottom edge
     * @param simplifyUm Ramer-Douglas-Peucker tolerance
     * @param minLoopVertices loops with fewer raw vertices are treated as dust and dropped
     */
    fun toShapes(
        loops: List<Loop>,
        imageWidthPx: Int,
        imageHeightPx: Int,
        widthMm: Double,
        originXUm: Long,
        originYUm: Long,
        simplifyUm: Long,
        minLoopVertices: Int,
    ): List<Shape.Polyline> {
        require(imageWidthPx > 0 && imageHeightPx > 0) { "image is empty" }
        require(widthMm.isFinite() && widthMm > 0.0) { "width must be positive" }
        val umPerPixel = widthMm * 1000.0 / imageWidthPx
        val out = ArrayList<Shape.Polyline>()
        for (loop in loops) {
            if (loop.size < minLoopVertices) continue
            val pts = ArrayList<PointUm>(loop.size + 1)
            for (i in 0 until loop.size) {
                val px = loop.xs[i] / 2.0
                val py = loop.ys[i] / 2.0
                pts.add(
                    PointUm(
                        Micrometers(originXUm + Math.round(px * umPerPixel)),
                        Micrometers(originYUm + Math.round((imageHeightPx - py) * umPerPixel)), // flip: image Y is down
                    ),
                )
            }
            pts.add(pts[0]) // close the loop exactly
            val simplified = Geometry.simplify(pts, simplifyUm)
            if (simplified.size >= 4) out.add(Shape.Polyline(simplified)) // triangle + closing point at minimum
        }
        return out
    }

    /** Size of the picture after fitting it inside [maxSide] pixels on its longer edge. */
    fun fitSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    /** ARGB -> 0..255 luminance (integer Rec.601 weights). */
    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return min(255, (299 * r + 587 * g + 114 * b) / 1000)
    }
}
