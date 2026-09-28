package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelOpsMaterialTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a plain gray image has near-zero saturation and no reliable hue`() {
        val px = IntArray(400) { rgb(150, 150, 150) }
        assertEquals(0.0, PixelOps.averageSaturation(px), 0.01)
        assertNull(PixelOps.averageHue(px))
    }

    @Test
    fun `a warm brown image reports a hue in the orange-brown band`() {
        val px = IntArray(400) { rgb(150, 100, 60) }
        val hue = PixelOps.averageHue(px)
        assertNotNull(hue)
        assertTrue("hue was $hue", hue!! in 10.0..50.0)
    }

    @Test
    fun `a mostly blown-out image has a high specular ratio`() {
        val px = IntArray(100) { i -> if (i < 80) rgb(250, 250, 250) else rgb(50, 50, 50) }
        assertTrue(PixelOps.specularHighlightRatio(px) > 0.7)
    }

    @Test
    fun `a checkerboard has higher texture spread than a flat field`() {
        val w = 20; val h = 20
        val flat = IntArray(w * h) { 128 }
        val checker = IntArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 40 else 220 }
        val (_, flatSpread) = PixelOps.textureStats(flat, w, h)
        val (_, checkerSpread) = PixelOps.textureStats(checker, w, h)
        assertTrue(checkerSpread > flatSpread)
    }
}
