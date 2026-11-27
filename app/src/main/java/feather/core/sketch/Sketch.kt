package feather.core.sketch

import feather.core.Geometry
import feather.core.Micrometers
import feather.core.PointUm
import feather.core.Shape
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A constraint-driven 2D sketch, in the spirit of FreeCAD's Sketcher: you place points, lines, circles and arcs,
 * then add geometric constraints (horizontal, parallel, tangent, ...) and dimensions (length, radius, angle, ...).
 * [SketchSolver] moves the geometry so every constraint holds. All values are millimetres / degrees, Y up.
 *
 * Elements refer to each other by integer id, so a line is two point ids and an arc is centre + start + end point ids
 * (counter-clockwise from start to end).
 */
class Sketch {
    class SPoint(val id: Int, var x: Double, var y: Double)
    class SLine(val id: Int, val a: Int, val b: Int)
    class SCircle(val id: Int, val center: Int, var radius: Double)
    class SArc(val id: Int, val center: Int, val start: Int, val end: Int)

    val points = LinkedHashMap<Int, SPoint>()
    val lines = LinkedHashMap<Int, SLine>()
    val circles = LinkedHashMap<Int, SCircle>()
    val arcs = LinkedHashMap<Int, SArc>()
    val constraints = ArrayList<Constraint>()
    private var nextId = 1

    fun addPoint(x: Double, y: Double): Int { val id = nextId++; points[id] = SPoint(id, x, y); return id }
    fun addLine(a: Int, b: Int): Int { val id = nextId++; lines[id] = SLine(id, a, b); return id }
    fun addCircle(center: Int, radius: Double): Int { val id = nextId++; circles[id] = SCircle(id, center, radius); return id }
    fun addArc(center: Int, start: Int, end: Int): Int { val id = nextId++; arcs[id] = SArc(id, center, start, end); return id }

    /** Adds a constraint and returns its index (used to report conflicts). */
    fun add(c: Constraint): Int { constraints.add(c); return constraints.size - 1 }
    fun removeConstraint(index: Int) { if (index in constraints.indices) constraints.removeAt(index) }

    private fun pt(x: Double, y: Double) = PointUm(Micrometers.fromMillimeters(x), Micrometers.fromMillimeters(y))

    /** The solved sketch as ordinary drawing shapes (lines, circles and arcs as polylines). Lone points are construction only. */
    fun toShapes(arcToleranceUm: Long = 5L): List<Shape> {
        val out = ArrayList<Shape>()
        for (l in lines.values) {
            val a = points[l.a] ?: continue
            val b = points[l.b] ?: continue
            out.add(Shape.Line(pt(a.x, a.y), pt(b.x, b.y)))
        }
        for (c in circles.values) {
            val m = points[c.center] ?: continue
            out.add(Shape.Circle(pt(m.x, m.y), Micrometers(Math.round(c.radius * 1000.0))))
        }
        for (a in arcs.values) {
            val m = points[a.center] ?: continue
            val s = points[a.start] ?: continue
            val e = points[a.end] ?: continue
            val r = hypot(s.x - m.x, s.y - m.y)
            if (r <= 0.0) continue
            val a0 = atan2(s.y - m.y, s.x - m.x)
            var sweep = atan2(e.y - m.y, e.x - m.x) - a0
            while (sweep <= 0.0) sweep += 2.0 * PI
            val full = Geometry.circleSegmentCount(Math.round(r * 1000.0), arcToleranceUm)
            val n = maxOf(4, ceil(sweep / (2.0 * PI) * full).toInt())
            val pts = ArrayList<PointUm>(n + 1)
            for (i in 0..n) {
                val ang = a0 + sweep * i / n
                pts.add(pt(m.x + r * cos(ang), m.y + r * sin(ang)))
            }
            out.add(Shape.Polyline(pts))
        }
        return out
    }
}

/** Geometric constraints and dimensions. Dimension values are editable so the user can change a number and re-solve. */
sealed class Constraint {
    class Coincident(val p: Int, val q: Int) : Constraint()
    class Horizontal(val line: Int) : Constraint()
    class Vertical(val line: Int) : Constraint()
    class Parallel(val l1: Int, val l2: Int) : Constraint()
    class Perpendicular(val l1: Int, val l2: Int) : Constraint()
    class EqualLength(val l1: Int, val l2: Int) : Constraint()
    /** Straight-line distance between two points. */
    class Distance(val p: Int, val q: Int, var value: Double) : Constraint()
    /** Signed horizontal distance from p to q (q.x - p.x). */
    class DistanceX(val p: Int, val q: Int, var value: Double) : Constraint()
    /** Signed vertical distance from p to q (q.y - p.y). */
    class DistanceY(val p: Int, val q: Int, var value: Double) : Constraint()
    class Length(val line: Int, var value: Double) : Constraint()
    /** [circle] may be a circle id or an arc id. */
    class Radius(val circle: Int, var value: Double) : Constraint()
    class Diameter(val circle: Int, var value: Double) : Constraint()
    class EqualRadius(val c1: Int, val c2: Int) : Constraint()
    /** Angle from line l1 to line l2, counter-clockwise, in degrees. */
    class Angle(val l1: Int, val l2: Int, var degrees: Double) : Constraint()
    class PointOnLine(val p: Int, val line: Int) : Constraint()
    class PointOnCircle(val p: Int, val circle: Int) : Constraint()
    class Fixed(val p: Int, var x: Double, var y: Double) : Constraint()
    class Midpoint(val p: Int, val line: Int) : Constraint()
    /** A line touching a circle or arc. */
    class Tangent(val line: Int, val circle: Int) : Constraint()
}
