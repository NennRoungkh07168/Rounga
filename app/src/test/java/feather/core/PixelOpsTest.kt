package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelOpsTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `background removal floods from the border but not into a different-coloured object`() {
        val w = 10; val h = 10
        val px = IntArray(w * h) { rgb(240, 240, 240) }
        for (y in 3..6) for (x in 3..6) px[y * w + x] = rgb(20, 20, 20) // dark object in the middle
        val mask = PixelOps.floodBackground(px, w, h, 40)
        assertTrue(mask[0])
        assertFalse(mask[4 * w + 4])
    }

    @Test
    fun `a closed ring keeps its inside as foreground`() {
        val w = 12; val h = 12
        val px = IntArray(w * h) { rgb(255, 255, 255) }
        for (y in 2..9) for (x in 2..9) if (y == 2 || y == 9 || x == 2 || x == 9) px[y * w + x] = rgb(0, 0, 0)
        val mask = PixelOps.floodBackground(px, w, h, 30)
        assertTrue(mask[0])
        assertFalse(mask[5 * w + 5].also { }) // enclosed white area is not reachable from the border
    }

    @Test
    fun `edge sketch is white on flat areas and dark on an edge`() {
        val w = 8; val h = 8
        val gray = IntArray(w * h) { if (it % w < 4) 0 else 255 }
        val out = PixelOps.edgeSketch(gray, w, h)
        assertEquals(255, out[3 * w + 1])
        assertTrue(out[3 * w + 3] < 100)
    }

    @Test
    fun `auto levels finds the used tonal range`() {
        val gray = IntArray(1000) { 60 + it % 100 } // 60..159
        val (lo, hi) = PixelOps.autoLevels(gray)
        assertTrue(lo in 60..70)
        assertTrue(hi in 150..159)
    }
}
