package feather.interior

import feather.core.Micrometers
import feather.core.PointUm
import feather.core.Shape

/** Turns a room layout into outlines for the drawing board, at a drawing scale such as 1:50. */
object PlanExport {

    val scales: List<Int> = listOf(10, 20, 25, 50, 100, 200)

    /** Real centimetres to paper micrometres at 1:[denominator]. */
    private fun um(cm: Double, denominator: Int): Long = Math.round(cm * 10_000.0 / denominator)

    /** Paper size in millimetres of the room outline at 1:[denominator]. */
    fun paperSizeMm(room: Room, denominator: Int): Pair<Double, Double> =
        (room.widthCm * 10.0 / denominator) to (room.depthCm * 10.0 / denominator)

    /**
     * The room outline plus one closed outline per item. (0, 0) of the paper is the back-left corner of the
     * room, moved by [marginMm] so nothing touches the machine's origin. The plan's y runs from the back wall
     * toward the viewer; it is flipped so the back wall is at the top of the drawing, as on any floor plan.
     */
    fun shapes(scene: Scene, denominator: Int, marginMm: Double = 10.0): List<Shape> {
        require(denominator > 0) { "scale must be positive" }
        val marginUm = Math.round(marginMm * 1000.0)
        val depthUm = um(scene.room.depthCm, denominator)
        fun pt(xCm: Double, yCm: Double) = PointUm(
            Micrometers(marginUm + um(xCm, denominator)),
            Micrometers(marginUm + depthUm - um(yCm, denominator)),
        )
        val out = ArrayList<Shape>()
        out += Shape.Rect(
            PointUm(Micrometers(marginUm), Micrometers(marginUm)),
            Micrometers(um(scene.room.widthCm, denominator)),
            Micrometers(depthUm),
        )
        for (item in scene.items) {
            val c = PlanGeometry.corners(item)
            val pts = c.map { pt(it.first, it.second) }
            out += Shape.Polyline(pts + pts[0])
        }
        return out
    }
}
