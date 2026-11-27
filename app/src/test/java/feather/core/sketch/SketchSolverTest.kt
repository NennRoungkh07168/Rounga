package feather.core.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected numbers come from the Python prototype of the same algorithm (see the project notes). */
class SketchSolverTest {
    private val eps = 1e-6

    @Test
    fun `roughly drawn rectangle snaps to exact dimensions`() {
        val s = Sketch()
        val p = listOf(
            s.addPoint(0.3, 0.2) to s.addPoint(40.4, 0.9), s.addPoint(39.8, 1.1) to s.addPoint(41.0, 25.5),
            s.addPoint(40.5, 24.8) to s.addPoint(-0.4, 26.0), s.addPoint(0.2, 25.2) to s.addPoint(-0.1, 0.6),
        )
        val l = p.map { s.addLine(it.first, it.second) }
        for (i in 0 until 4) s.add(Constraint.Coincident(p[i].second, p[(i + 1) % 4].first))
        s.add(Constraint.Fixed(p[0].first, 10.0, 5.0))
        s.add(Constraint.Horizontal(l[0])); s.add(Constraint.Horizontal(l[2]))
        s.add(Constraint.Vertical(l[1])); s.add(Constraint.Vertical(l[3]))
        s.add(Constraint.Length(l[0], 40.0)); s.add(Constraint.Length(l[1], 25.0))

        val r = SketchSolver.solve(s)
        assertTrue(r.status.toString(), r.ok)
        assertEquals(0, r.degreesOfFreedom)
        assertEquals(50.0, s.points.getValue(p[1].second).x, eps)
        assertEquals(30.0, s.points.getValue(p[1].second).y, eps)
        assertEquals(10.0, s.points.getValue(p[2].second).x, eps)
        assertEquals(30.0, s.points.getValue(p[2].second).y, eps)
        assertEquals(4, s.toShapes().size)
    }

    @Test
    fun `degrees of freedom shrink as constraints are added`() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 3.0); val line = s.addLine(a, b)
        assertEquals(4, SketchSolver.solve(s).degreesOfFreedom)
        s.add(Constraint.Horizontal(line)); assertEquals(3, SketchSolver.solve(s).degreesOfFreedom)
        s.add(Constraint.Length(line, 12.0)); assertEquals(2, SketchSolver.solve(s).degreesOfFreedom)
        s.add(Constraint.Fixed(a, 5.0, 5.0))
        val r = SketchSolver.solve(s)
        assertEquals(0, r.degreesOfFreedom)
        assertEquals(17.0, s.points.getValue(b).x, eps)
        assertEquals(5.0, s.points.getValue(b).y, eps)
    }

    @Test
    fun `contradicting dimensions are reported and the drawing is left alone`() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.5); val line = s.addLine(a, b)
        s.add(Constraint.Fixed(a, 0.0, 0.0)); s.add(Constraint.Horizontal(line))
        s.add(Constraint.Length(line, 40.0)); s.add(Constraint.Length(line, 50.0))
        val r = SketchSolver.solve(s)
        assertEquals(SolveStatus.CONFLICT, r.status)
        assertTrue(r.conflicting.isNotEmpty())
        assertEquals(10.0, s.points.getValue(b).x, eps) // reverted
        assertEquals(0.5, s.points.getValue(b).y, eps)
    }

    @Test
    fun `repeating the same dimension is redundant but fine`() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.5); val line = s.addLine(a, b)
        s.add(Constraint.Fixed(a, 0.0, 0.0)); s.add(Constraint.Horizontal(line))
        s.add(Constraint.Length(line, 40.0)); s.add(Constraint.Length(line, 40.0))
        val r = SketchSolver.solve(s)
        assertTrue(r.ok)
        assertEquals(1, r.redundantConstraints)
        assertEquals(0, r.degreesOfFreedom)
    }

    @Test
    fun `circle diameter and a tangent line`() {
        val s = Sketch()
        val c = s.addPoint(5.0, 5.0); val circle = s.addCircle(c, 3.0)
        val a = s.addPoint(-5.0, 12.0); val b = s.addPoint(30.0, 11.0); val line = s.addLine(a, b)
        s.add(Constraint.Diameter(circle, 20.0)); s.add(Constraint.Fixed(c, 10.0, 10.0))
        s.add(Constraint.Horizontal(line)); s.add(Constraint.Tangent(line, circle)); s.add(Constraint.Fixed(a, 0.0, 20.0))
        val r = SketchSolver.solve(s)
        assertTrue(r.status.toString(), r.ok)
        assertEquals(10.0, s.circles.getValue(circle).radius, eps)
        assertEquals(20.0, s.points.getValue(b).y, eps)
        val shapes = s.toShapes()
        assertEquals(2, shapes.size)
    }

    @Test
    fun `a line that cannot be tangent is a conflict not a collapsed line`() {
        val s = Sketch()
        val c = s.addPoint(5.0, 5.0); val circle = s.addCircle(c, 3.0)
        val a = s.addPoint(-5.0, 12.0); val b = s.addPoint(30.0, 11.0); val line = s.addLine(a, b)
        s.add(Constraint.Diameter(circle, 20.0)); s.add(Constraint.Fixed(c, 10.0, 10.0))
        s.add(Constraint.Horizontal(line)); s.add(Constraint.Tangent(line, circle)); s.add(Constraint.Fixed(a, 0.0, 22.0))
        val r = SketchSolver.solve(s)
        assertFalse(r.ok)
        assertEquals(12.0, s.points.getValue(a).y, eps) // untouched
    }

    @Test
    fun `angle and equal length place a point exactly`() {
        val s = Sketch()
        val o = s.addPoint(0.0, 0.0); val p = s.addPoint(10.0, 1.0); val q = s.addPoint(-1.0, 8.0)
        val l1 = s.addLine(o, p); val l2 = s.addLine(o, q)
        s.add(Constraint.Fixed(o, 0.0, 0.0)); s.add(Constraint.Length(l1, 10.0)); s.add(Constraint.Angle(l1, l2, 60.0))
        s.add(Constraint.EqualLength(l1, l2)); s.add(Constraint.Fixed(p, 10.0, 0.0))
        val r = SketchSolver.solve(s)
        assertTrue(r.ok)
        assertEquals(5.0, s.points.getValue(q).x, eps)
        assertEquals(8.660254, s.points.getValue(q).y, 1e-5)
    }

    @Test
    fun `arc end is pulled onto a line and stays on its circle`() {
        val s = Sketch()
        val c = s.addPoint(0.0, 0.0); val st = s.addPoint(10.0, 0.0); val en = s.addPoint(-1.0, 9.0)
        s.addArc(c, st, en)
        val t1 = s.addPoint(0.0, -20.0); val t2 = s.addPoint(0.0, 20.0); val vl = s.addLine(t1, t2)
        s.add(Constraint.Fixed(c, 0.0, 0.0)); s.add(Constraint.Fixed(st, 10.0, 0.0))
        s.add(Constraint.Fixed(t1, 0.0, -20.0)); s.add(Constraint.Fixed(t2, 0.0, 20.0))
        s.add(Constraint.PointOnLine(en, vl))
        val r = SketchSolver.solve(s)
        assertTrue(r.ok)
        assertEquals(0.0, s.points.getValue(en).x, eps)
        assertEquals(10.0, s.points.getValue(en).y, eps)
    }

    @Test
    fun `a missing element gives a clear error`() {
        val s = Sketch()
        s.addPoint(0.0, 0.0)
        s.add(Constraint.Horizontal(999)) // no such line
        try {
            SketchSolver.solve(s)
            org.junit.Assert.fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no longer exists"))
        }
    }
}
