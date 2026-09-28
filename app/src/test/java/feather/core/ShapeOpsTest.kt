package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeOpsTest {

    private fun mm(v: Double) = Micrometers.fromMillimeters(v)
    private val rect = Shape.Rect(PointUm(mm(0.0), mm(0.0)), mm(20.0), mm(10.0))

    @Test
    fun `translate and scale keep exact micrometres`() {
        val moved = ShapeOps.translate(rect, 5_000, -2_000) as Shape.Rect
        assertEquals(5_000L, moved.topLeft.x.raw)
        val scaled = ShapeOps.scale(rect, 0, 0, 2.0) as Shape.Rect
        assertEquals(40_000L, scaled.widthUm.raw)
    }

    @Test
    fun `rotating a rectangle by 90 swaps its extents`() {
        val r = ShapeOps.rotate(rect, 10_000, 5_000, 90.0)
        val b = Geometry.bounds(listOf(r))!!
        assertEquals(10_000L, b.width)
        assertEquals(20_000L, b.height)
    }

    @Test
    fun `hit test picks the nearest outline within tolerance`() {
        val shapes = listOf<Shape>(rect, Shape.Circle(PointUm(mm(100.0), mm(100.0)), mm(5.0)))
        assertEquals(0, ShapeOps.hitTest(shapes, PointUm(mm(10.0), mm(0.2)), 500.0))
        assertEquals(1, ShapeOps.hitTest(shapes, PointUm(mm(105.0), mm(100.0)), 500.0))
        assertEquals(-1, ShapeOps.hitTest(shapes, PointUm(mm(50.0), mm(50.0)), 500.0))
    }

    @Test
    fun `mirror keeps a rectangle a rectangle`() {
        val m = ShapeOps.mirror(rect, 0, 0, horizontal = true)
        assertTrue(m is Shape.Rect)
        assertEquals(-20_000L, (m as Shape.Rect).topLeft.x.raw)
    }

    // ---- Color and visibility survive every transform (Layers/Properties panel) ----

    @Test
    fun `translate, scale, rotate and mirror all preserve color and hidden`() {
        val colored = rect.withColor(-0x1000000 or 0xE53935).withHidden(true) as Shape.Rect
        assertEquals(-0x1000000 or 0xE53935, ShapeOps.translate(colored, 1_000, 1_000).colorArgb)
        assertTrue(ShapeOps.translate(colored, 1_000, 1_000).hidden)
        assertEquals(colored.colorArgb, ShapeOps.scale(colored, 0, 0, 2.0).colorArgb)
        assertTrue(ShapeOps.scale(colored, 0, 0, 2.0).hidden)
        // Rect -> Polyline on rotate is a type change; color/hidden must still carry over.
        assertEquals(colored.colorArgb, ShapeOps.rotate(colored, 0, 0, 45.0).colorArgb)
        assertTrue(ShapeOps.rotate(colored, 0, 0, 45.0).hidden)
        assertEquals(colored.colorArgb, ShapeOps.mirror(colored, 0, 0, horizontal = true).colorArgb)
        assertTrue(ShapeOps.mirror(colored, 0, 0, horizontal = true).hidden)
    }

    @Test
    fun `simplify preserves color and hidden on a polyline`() {
        val poly = Shape.Polyline(listOf(PointUm(mm(0.0), mm(0.0)), PointUm(mm(1.0), mm(0.0)), PointUm(mm(2.0), mm(0.0))))
            .withColor(-0x1000000 or 0x1E88E5).withHidden(true)
        val out = ShapeOps.simplify(poly, 50L)
        assertEquals(poly.colorArgb, out.colorArgb)
        assertTrue(out.hidden)
    }

    @Test
    fun `hit test skips hidden shapes`() {
        val hiddenRect = rect.withHidden(true)
        val shapes = listOf<Shape>(hiddenRect, Shape.Circle(PointUm(mm(100.0), mm(100.0)), mm(5.0)))
        // Same point that hit the rectangle when visible (see the test above) now misses entirely.
        assertEquals(-1, ShapeOps.hitTest(shapes, PointUm(mm(10.0), mm(0.2)), 500.0))
        assertEquals(1, ShapeOps.hitTest(shapes, PointUm(mm(105.0), mm(100.0)), 500.0))
    }
}
