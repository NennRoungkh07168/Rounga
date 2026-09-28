package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTracerTest {

    private fun mask(w: Int, h: Int, ink: (Int, Int) -> Boolean) = BooleanArray(w * h) { ink(it % w, it / w) }

    @Test
    fun `a filled block gives one closed loop`() {
        val m = mask(8, 8) { x, y -> x in 2..5 && y in 2..5 }
        assertEquals(1, ImageTracer.contours(m, 8, 8).size)
    }

    @Test
    fun `a ring gives an outer and an inner loop`() {
        val m = mask(8, 8) { x, y -> x in 2..5 && y in 2..5 && !(x in 3..4 && y in 3..4) }
        assertEquals(2, ImageTracer.contours(m, 8, 8).size)
    }

    @Test
    fun `an empty or full mask still closes every loop`() {
        assertEquals(0, ImageTracer.contours(BooleanArray(16), 4, 4).size)
        assertEquals(1, ImageTracer.contours(BooleanArray(16) { true }, 4, 4).size) // padding closes it
    }

    @Test
    fun `otsu splits a two-tone image between the tones`() {
        val gray = IntArray(1000) { if (it < 500) 20 else 220 }
        val t = ImageTracer.otsu(gray)
        assertTrue(t in 20 until 220)
    }

    @Test
    fun `traced shapes are scaled to the requested width and flipped to Y-up`() {
        // Ink in the top-left quadrant of a 10 x 10 picture.
        val m = mask(10, 10) { x, y -> x in 1..3 && y in 1..3 }
        val loops = ImageTracer.contours(m, 10, 10)
        val shapes = ImageTracer.toShapes(loops, 10, 10, widthMm = 100.0, originXUm = 0, originYUm = 0, simplifyUm = 10, minLoopVertices = 4)
        assertEquals(1, shapes.size)
        val b = Geometry.bounds(shapes)!!
        // top of the picture is high Y: the block must sit in the upper half
        assertTrue(b.minY > 50_000)
        assertTrue(b.maxX < 50_000)
        // closed exactly
        val pts = shapes[0].points
        assertTrue(pts.first().sameXY(pts.last()))
    }

    @Test
    fun `specks below the vertex limit are dropped`() {
        val m = mask(6, 6) { x, y -> x == 2 && y == 2 }
        val loops = ImageTracer.contours(m, 6, 6)
        val shapes = ImageTracer.toShapes(loops, 6, 6, 60.0, 0, 0, 10, minLoopVertices = 12)
        assertEquals(0, shapes.size)
    }
}
