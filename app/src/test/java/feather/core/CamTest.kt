package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CamTest {
    private fun p(x: Double, y: Double) = PointUm(Micrometers.fromMillimeters(x), Micrometers.fromMillimeters(y))
    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        Shape.Rect(p(x, y), Micrometers.fromMillimeters(w), Micrometers.fromMillimeters(h))
    private fun circle(x: Double, y: Double, d: Double) = Shape.Circle(p(x, y), Micrometers(Math.round(d * 500.0)))
    private fun settings(op: CamOperation, tool: Double, stepover: Int = 40) =
        JobSettings(operation = op, toolDiameterUm = Micrometers.fromMillimeters(tool), stepoverPercent = stepover)
    private fun widthMm(shapes: List<Shape>) = Geometry.bounds(shapes)!!.width / 1000.0
    private fun heightMm(shapes: List<Shape>) = Geometry.bounds(shapes)!!.height / 1000.0

    @Test
    fun `engrave leaves shapes untouched`() {
        val shapes = listOf<Shape>(rect(0.0, 0.0, 20.0, 20.0))
        assertTrue(Cam.apply(shapes, settings(CamOperation.ENGRAVE, 4.0)) === shapes)
    }

    @Test
    fun `outside profile grows a rectangle by the tool radius`() {
        val out = Cam.apply(listOf(rect(0.0, 0.0, 20.0, 20.0)), settings(CamOperation.OUTSIDE, 4.0))
        assertEquals(1, out.size)
        assertEquals(24.0, widthMm(out), 0.01)
        assertEquals(24.0, heightMm(out), 0.01)
    }

    @Test
    fun `inside profile shrinks a circle by the tool radius`() {
        val out = Cam.apply(listOf(circle(0.0, 0.0, 20.0)), settings(CamOperation.INSIDE, 4.0))
        assertEquals(16.0, widthMm(out), 0.05)
    }

    @Test
    fun `a shape inside another is a hole - outside profile grows the part and shrinks the hole`() {
        val part = rect(0.0, 0.0, 40.0, 40.0)
        val hole = circle(20.0, 20.0, 10.0)
        val out = Cam.apply(listOf(part, hole), settings(CamOperation.OUTSIDE, 4.0))
        assertEquals(2, out.size)
        val widths = out.map { widthMm(listOf(it)) }.sorted()
        assertEquals(6.0, widths[0], 0.05)   // hole: 10 mm - 2 * 2 mm
        assertEquals(44.0, widths[1], 0.05)  // part: 40 mm + 2 * 2 mm
    }

    @Test
    fun `pocket makes concentric loops that stay inside the wall`() {
        val out = Cam.apply(listOf(rect(0.0, 0.0, 20.0, 20.0)), settings(CamOperation.POCKET, 4.0, stepover = 50))
        assertTrue("loops=${out.size}", out.size in 4..5)
        assertTrue(widthMm(out) <= 16.01) // widest loop = wall offset, 20 - 2 * 2
    }

    @Test
    fun `tool larger than the shape is reported clearly`() {
        try {
            Cam.apply(listOf(rect(0.0, 0.0, 3.0, 3.0)), settings(CamOperation.INSIDE, 4.0))
            fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too big"))
        }
    }

    @Test
    fun `open lines pass through and are still engraved`() {
        val line = Shape.Line(p(0.0, 0.0), p(10.0, 0.0))
        val out = Cam.apply(listOf(line, rect(0.0, 0.0, 20.0, 20.0)), settings(CamOperation.OUTSIDE, 4.0))
        assertTrue(out.contains(line))
        assertEquals(2, out.size)
    }

    @Test
    fun `regular polygon closes and has the requested corners`() {
        val hex = Primitives.regularPolygon(0.0, 0.0, 20.0, 6, 0.0)
        assertEquals(7, hex.points.size)
        assertTrue(hex.points.first().sameXY(hex.points.last()))
        assertEquals(10_000L, hex.points[0].x.raw)
    }

    @Test
    fun `quarter arc starts and ends on the circle`() {
        val arc = Primitives.arc(0.0, 0.0, 20.0, 0.0, 90.0, 5L)
        assertEquals(10_000L, arc.points.first().x.raw)
        assertEquals(10_000L, arc.points.last().y.raw)
    }
}
