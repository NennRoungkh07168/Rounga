package feather.core

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** What the cutter does with the closed shapes in a drawing. Open lines are always engraved on the line. */
enum class CamOperation(val label: String) {
    ENGRAVE("Engrave on the line"),
    OUTSIDE("Cut outside (tool outside the shape, holes cut inside)"),
    INSIDE("Cut inside (tool inside the shape)"),
    POCKET("Pocket (clear the whole area)"),
}

/**
 * Turns the drawing into the path the CUTTER CENTRE must follow, by offsetting closed shapes by the tool
 * radius. The result is ordinary [Shape.Polyline]s, so [GCodeGenerator] (depth passes, travel ordering,
 * machine checks) works on it unchanged.
 *
 * Closed shapes are combined with the even-odd rule, so a shape drawn inside another one is a HOLE:
 *  - OUTSIDE: outer outlines grow by the tool radius, holes shrink by it (the tool stays in the waste).
 *  - INSIDE : outer outlines shrink, holes grow (the tool stays inside each outline).
 *  - POCKET : concentric loops from the wall inwards, spaced by the stepover, cut from the middle outwards.
 * Offsetting uses JTS (robust polygon buffer), working in millimetres.
 */
object Cam {
    private val factory = GeometryFactory()
    private const val MAX_POCKET_LOOPS = 3000

    /** Throws [IllegalArgumentException] with a readable message when the tool cannot do what was asked. */
    fun apply(shapes: List<Shape>, settings: JobSettings): List<Shape> {
        if (settings.operation == CamOperation.ENGRAVE) return shapes
        val toolMm = settings.toolDiameterUm.raw / 1000.0
        if (!(toolMm > 0.0)) throw IllegalArgumentException("Tool diameter must be greater than 0.")
        val radiusMm = toolMm / 2.0
        val stepMm = toolMm * settings.stepoverPercent / 100.0
        if (!(stepMm > 0.0)) throw IllegalArgumentException("Stepover must be greater than 0.")

        val passthrough = ArrayList<Shape>()
        val closed = ArrayList<JtsGeometry>()
        for (s in shapes) {
            val ring = outlineMm(s, settings.arcToleranceUm)
            if (ring == null) { passthrough.add(s); continue }
            val g = polygonOf(ring) ?: continue // degenerate: nothing to cut
            closed.add(g)
        }
        if (closed.isEmpty()) return shapes // no closed shapes: just engrave the lines

        try {
            var region: JtsGeometry = closed[0]
            for (i in 1 until closed.size) region = region.symDifference(closed[i])
            if (region.isEmpty) {
                throw IllegalArgumentException("The closed shapes cancel each other out (identical shapes drawn on top of each other).")
            }
            val params = bufferParams(radiusMm, settings.arcToleranceUm)
            val result = ArrayList<Shape>()
            when (settings.operation) {
                CamOperation.ENGRAVE -> return shapes
                CamOperation.OUTSIDE -> result.addAll(ringsOf(buffer(region, radiusMm, params)))
                CamOperation.INSIDE -> {
                    val g = buffer(region, -radiusMm, params)
                    if (g.isEmpty) throw toolTooBig(toolMm)
                    result.addAll(ringsOf(g))
                }
                CamOperation.POCKET -> {
                    val loops = ArrayList<List<Shape>>()
                    var d = -radiusMm
                    while (loops.size < MAX_POCKET_LOOPS) {
                        val g = buffer(region, d, params)
                        if (g.isEmpty) break
                        loops.add(ringsOf(g))
                        d -= stepMm
                    }
                    if (loops.isEmpty()) throw toolTooBig(toolMm)
                    for (i in loops.indices.reversed()) result.addAll(loops[i]) // middle first, finishing wall last
                }
            }
            return passthrough + result
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException(
                "Could not offset these shapes for the tool. They may cross themselves or overlap awkwardly; simplify the drawing and try again.",
                e,
            )
        }
    }

    private fun toolTooBig(toolMm: Double) =
        IllegalArgumentException("The ${"%.2f".format(java.util.Locale.US, toolMm)} mm tool is too big to fit inside these shapes.")

    private fun buffer(g: JtsGeometry, distanceMm: Double, params: BufferParameters): JtsGeometry =
        BufferOp(g, params).getResultGeometry(distanceMm)

    /** Round joins with enough segments per quadrant that the offset stays within the arc tolerance. */
    private fun bufferParams(radiusMm: Double, arcToleranceUm: Long): BufferParameters {
        val tolMm = arcToleranceUm / 1000.0
        val quad = if (radiusMm <= tolMm) 8
        else ceil((Math.PI / 2.0) / acos(1.0 - tolMm / radiusMm)).toInt().coerceIn(4, 64)
        return BufferParameters(quad, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 5.0)
    }

    /** A closed outline in mm (first point repeated at the end), or null for open shapes such as lines. */
    private fun outlineMm(shape: Shape, arcToleranceUm: Long): Array<Coordinate>? {
        val pts = ArrayList<Coordinate>()
        when (shape) {
            is Shape.Line -> return null
            is Shape.Rect -> {
                val x0 = shape.topLeft.x.raw / 1000.0
                val y0 = shape.topLeft.y.raw / 1000.0
                val x1 = (shape.topLeft.x.raw + shape.widthUm.raw) / 1000.0
                val y1 = (shape.topLeft.y.raw + shape.heightUm.raw) / 1000.0
                pts.add(Coordinate(x0, y0)); pts.add(Coordinate(x1, y0))
                pts.add(Coordinate(x1, y1)); pts.add(Coordinate(x0, y1)); pts.add(Coordinate(x0, y0))
            }
            is Shape.Circle -> {
                val r = shape.radiusUm.raw
                if (r <= 0) return null
                val n = Geometry.circleSegmentCount(r, arcToleranceUm)
                val cx = shape.center.x.raw / 1000.0
                val cy = shape.center.y.raw / 1000.0
                val rm = r / 1000.0
                for (i in 0 until n) {
                    val a = 2.0 * Math.PI * i / n
                    pts.add(Coordinate(cx + rm * cos(a), cy + rm * sin(a)))
                }
                pts.add(Coordinate(pts[0].x, pts[0].y))
            }
            is Shape.Polyline -> {
                val p = shape.points
                if (p.size < 4 || !p.first().sameXY(p.last())) return null
                for (q in p) pts.add(Coordinate(q.x.raw / 1000.0, q.y.raw / 1000.0))
            }
        }
        return pts.toTypedArray()
    }

    private fun polygonOf(coords: Array<Coordinate>): JtsGeometry? {
        if (coords.size < 4) return null
        val poly: Polygon = try {
            factory.createPolygon(coords)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val fixed: JtsGeometry = if (poly.isValid) poly else poly.buffer(0.0) // repairs simple self-crossings
        return if (fixed.isEmpty) null else fixed
    }

    /** Every outline and hole boundary of a polygon / multipolygon as closed polylines in µm. */
    private fun ringsOf(g: JtsGeometry): List<Shape> {
        val out = ArrayList<Shape>()
        for (i in 0 until g.numGeometries) {
            val part = g.getGeometryN(i)
            if (part is Polygon) {
                addRing(out, part.exteriorRing.coordinates)
                for (h in 0 until part.numInteriorRing) addRing(out, part.getInteriorRingN(h).coordinates)
            }
        }
        return out
    }

    private fun addRing(out: MutableList<Shape>, coords: Array<Coordinate>) {
        if (coords.size < 4) return
        val pts = ArrayList<PointUm>(coords.size)
        for (c in coords) {
            val p = PointUm(Micrometers(Math.round(c.x * 1000.0)), Micrometers(Math.round(c.y * 1000.0)))
            if (pts.isEmpty() || !pts.last().sameXY(p)) pts.add(p)
        }
        if (pts.size < 3) return
        if (!pts.first().sameXY(pts.last())) pts.add(pts.first()) // close exactly after rounding
        out.add(Shape.Polyline(pts))
    }
}
