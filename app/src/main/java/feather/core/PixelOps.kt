package feather.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain-array image operations, kept free of Android types so they can be unit-tested. */
object PixelOps {

    /** Sobel edge strength drawn as dark lines on white: a pencil-sketch of the picture. gray = 0..255. */
    fun edgeSketch(gray: IntArray, w: Int, h: Int, gain: Double = 2.0): IntArray {
        require(gray.size == w * h) { "size mismatch" }
        val out = IntArray(w * h) { 255 }
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                fun g(dx: Int, dy: Int) = gray[(y + dy) * w + x + dx]
                val gx = -g(-1, -1) - 2 * g(-1, 0) - g(-1, 1) + g(1, -1) + 2 * g(1, 0) + g(1, 1)
                val gy = -g(-1, -1) - 2 * g(0, -1) - g(1, -1) + g(-1, 1) + 2 * g(0, 1) + g(1, 1)
                val mag = sqrt((gx * gx + gy * gy).toDouble())
                out[y * w + x] = (255.0 - mag * gain / 4.0).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /**
     * Marks the plain background: pixels reachable from the picture's border whose colour is within
     * [tolerance] (RGB distance) of the average border colour. true = background.
     */
    fun floodBackground(argb: IntArray, w: Int, h: Int, tolerance: Int): BooleanArray {
        require(argb.size == w * h) { "size mismatch" }
        val mask = BooleanArray(w * h)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        fun border(x: Int, y: Int) {
            val p = argb[y * w + x]
            sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
        }
        for (x in 0 until w) { border(x, 0); border(x, h - 1) }
        for (y in 1 until h - 1) { border(0, y); border(w - 1, y) }
        val mr = (sr / n).toInt(); val mg = (sg / n).toInt(); val mb = (sb / n).toInt()
        val tol2 = tolerance.toLong() * tolerance

        fun near(i: Int): Boolean {
            val p = argb[i]
            val dr = ((p shr 16) and 0xFF) - mr
            val dg = ((p shr 8) and 0xFF) - mg
            val db = (p and 0xFF) - mb
            return (dr * dr + dg * dg + db * db).toLong() <= tol2
        }

        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) {
            if (!mask[i] && near(i)) { mask[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return mask
    }

    /** Sobel gradient magnitude at every pixel (0 at the border), the raw signal behind [edgeSketch]. */
    fun gradientMagnitude(gray: IntArray, w: Int, h: Int): DoubleArray {
        require(gray.size == w * h) { "size mismatch" }
        val out = DoubleArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                fun g(dx: Int, dy: Int) = gray[(y + dy) * w + x + dx]
                val gx = -g(-1, -1) - 2 * g(-1, 0) - g(-1, 1) + g(1, -1) + 2 * g(1, 0) + g(1, 1)
                val gy = -g(-1, -1) - 2 * g(0, -1) - g(1, -1) + g(-1, 1) + 2 * g(0, 1) + g(1, 1)
                out[y * w + x] = sqrt((gx * gx + gy * gy).toDouble())
            }
        }
        return out
    }

    /** Mean and standard deviation of the surface's local texture (edge strength) — rough, not identity. */
    fun textureStats(gray: IntArray, w: Int, h: Int): Pair<Double, Double> {
        val mags = gradientMagnitude(gray, w, h)
        if (mags.isEmpty()) return 0.0 to 0.0
        val mean = mags.average()
        val variance = mags.sumOf { (it - mean) * (it - mean) } / mags.size
        return mean to sqrt(variance)
    }

    /** Average HSV saturation (0..1) across the picture — low means near-grayscale (metal, white paper, bare wood grain can vary). */
    fun averageSaturation(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        var sum = 0.0
        for (p in argb) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            sum += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
        }
        return sum / argb.size
    }

    /** Average hue in degrees (0..360), or null if the picture is too close to gray to have a meaningful hue. */
    fun averageHue(argb: IntArray): Double? {
        if (argb.isEmpty()) return null
        var sx = 0.0; var sy = 0.0; var weight = 0.0
        for (p in argb) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (sat < 0.08) continue // near-gray pixels carry no reliable hue; skip rather than let noise dominate
            val delta = (mx - mn).toDouble()
            val hue = when (mx) {
                r -> 60.0 * (((g - b) / delta) % 6.0)
                g -> 60.0 * (((b - r) / delta) + 2.0)
                else -> 60.0 * (((r - g) / delta) + 4.0)
            }.let { if (it < 0) it + 360.0 else it }
            sx += cos(Math.toRadians(hue)) * sat
            sy += sin(Math.toRadians(hue)) * sat
            weight += sat
        }
        if (weight < argb.size * 0.03) return null // almost nothing had usable colour: treat as gray/metallic
        val angle = Math.toDegrees(kotlin.math.atan2(sy, sx))
        return if (angle < 0) angle + 360.0 else angle
    }

    /** Fraction of pixels that are near-white and blown out — a rough proxy for glare off a shiny/reflective surface. */
    fun specularHighlightRatio(argb: IntArray): Double {
        if (argb.isEmpty()) return 0.0
        var blown = 0
        for (p in argb) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (r > 240 && g > 240 && b > 240) blown++
        }
        return blown.toDouble() / argb.size
    }

    /** The 1st and 99th percentile of [gray]: the range "Auto enhance" stretches to full black..white. */
    fun autoLevels(gray: IntArray): Pair<Int, Int> {
        if (gray.isEmpty()) return 0 to 255
        val hist = IntArray(256)
        for (g in gray) hist[g.coerceIn(0, 255)]++
        val cut = (gray.size * 0.01).toInt()
        var acc = 0
        var lo = 0
        for (i in 0..255) { acc += hist[i]; if (acc > cut) { lo = i; break } }
        acc = 0
        var hi = 255
        for (i in 255 downTo 0) { acc += hist[i]; if (acc > cut) { hi = i; break } }
        return if (hi - lo < 8) 0 to 255 else lo to hi
    }
}
