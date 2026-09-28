package feather.interior

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Plan coordinates: x runs left to right along the room's width, z (called "y" in the plan) runs from the
 * back wall (z = 0) toward the front wall (z = depth). All lengths are centimetres. An item's local +z is its
 * front, and a rotation of 0 puts its back toward the back wall.
 */

enum class ItemType(
    val label: String,
    val widthCm: Double,
    val depthCm: Double,
    val heightCm: Double,
    val elevationCm: Double,
    val color: Int,
) {
    SOFA("Sofa", 200.0, 90.0, 85.0, 0.0, 0xFF6C8EBF.toInt()),
    ARMCHAIR("Armchair", 85.0, 85.0, 85.0, 0.0, 0xFFB5838D.toInt()),
    BED("Bed", 160.0, 200.0, 100.0, 0.0, 0xFF9DB4C0.toInt()),
    DINING_TABLE("Dining table", 160.0, 90.0, 75.0, 0.0, 0xFFC69C6D.toInt()),
    COFFEE_TABLE("Coffee table", 100.0, 55.0, 42.0, 0.0, 0xFFD4A373.toInt()),
    CHAIR("Chair", 45.0, 45.0, 90.0, 0.0, 0xFFE9C46A.toInt()),
    DESK("Desk", 140.0, 70.0, 75.0, 0.0, 0xFFBC8A5F.toInt()),
    WARDROBE("Wardrobe", 120.0, 60.0, 210.0, 0.0, 0xFFF1E3D3.toInt()),
    SHELF("Shelf", 90.0, 30.0, 180.0, 0.0, 0xFFA98467.toInt()),
    TV_UNIT("TV unit", 160.0, 40.0, 50.0, 0.0, 0xFF6D6875.toInt()),
    RUG("Rug", 200.0, 140.0, 1.5, 0.0, 0xFFE76F51.toInt()),
    PLANT("Plant", 40.0, 40.0, 110.0, 0.0, 0xFF52B788.toInt()),
    LAMP("Lamp", 35.0, 35.0, 160.0, 0.0, 0xFFFFD166.toInt()),
    DOOR("Door", 90.0, 8.0, 210.0, 0.0, 0xFF8D6E63.toInt()),
    WINDOW("Window", 120.0, 8.0, 120.0, 90.0, 0xFF90CAF9.toInt()),
}

data class RoomItem(
    val id: String,
    val type: ItemType,
    val name: String,
    /** Centre of the footprint. */
    val xCm: Double,
    val yCm: Double,
    val widthCm: Double,
    val depthCm: Double,
    val heightCm: Double,
    val elevationCm: Double,
    /** Degrees, clockwise as seen from above. */
    val rotationDeg: Double,
    val color: Int,
)

data class Room(
    val widthCm: Double = 500.0,
    val depthCm: Double = 400.0,
    val heightCm: Double = 270.0,
    val floorColor: Int = 0xFFD9B99B.toInt(),
    val wallColor: Int = 0xFFF3EFE6.toInt(),
)

data class Scene(val room: Room = Room(), val items: List<RoomItem> = emptyList()) {

    companion object {
        /** A furnished starter room so the first look already tells the story. */
        fun starter(): Scene {
            val room = Room()
            var n = 0
            fun item(type: ItemType, x: Double, y: Double, rot: Double = 0.0, w: Double = type.widthCm, d: Double = type.depthCm): RoomItem {
                n++
                return RoomItem("start-$n", type, type.label, x, y, w, d, type.heightCm, type.elevationCm, rot, type.color)
            }
            return Scene(
                room,
                listOf(
                    item(ItemType.RUG, 250.0, 205.0, 0.0, 260.0, 170.0),
                    item(ItemType.SOFA, 250.0, 46.0),
                    item(ItemType.COFFEE_TABLE, 250.0, 175.0),
                    item(ItemType.TV_UNIT, 250.0, 378.0, 180.0),
                    item(ItemType.ARMCHAIR, 95.0, 190.0, 90.0),
                    item(ItemType.PLANT, 35.0, 35.0),
                    item(ItemType.LAMP, 455.0, 35.0),
                    item(ItemType.SHELF, 465.0, 150.0, 90.0),
                    item(ItemType.WINDOW, 250.0, 4.0),
                    item(ItemType.DOOR, 4.0, 300.0, 270.0),
                ),
            )
        }
    }
}

/** Footprint maths shared by the plan view, hit-testing, the 3D renderer and plotting. */
object PlanGeometry {

    /** World position of a point given in an item's local frame (lx right, lz front). */
    fun toWorld(item: RoomItem, lx: Double, lz: Double): Pair<Double, Double> {
        val r = Math.toRadians(item.rotationDeg)
        val c = cos(r)
        val s = sin(r)
        return (item.xCm + lx * c - lz * s) to (item.yCm + lx * s + lz * c)
    }

    /** The four corners, back-left first, clockwise from above. */
    fun corners(item: RoomItem): List<Pair<Double, Double>> {
        val hw = item.widthCm / 2
        val hd = item.depthCm / 2
        return listOf(toWorld(item, -hw, -hd), toWorld(item, hw, -hd), toWorld(item, hw, hd), toWorld(item, -hw, hd))
    }

    fun contains(item: RoomItem, x: Double, y: Double): Boolean {
        val r = Math.toRadians(-item.rotationDeg)
        val dx = x - item.xCm
        val dy = y - item.yCm
        val lx = dx * cos(r) - dy * sin(r)
        val lz = dx * sin(r) + dy * cos(r)
        return Math.abs(lx) <= item.widthCm / 2 && Math.abs(lz) <= item.depthCm / 2
    }

    /** Topmost item at a plan point; rugs only win when nothing else is there. */
    fun itemAt(scene: Scene, x: Double, y: Double): RoomItem? {
        val hits = scene.items.filter { contains(it, x, y) }
        return hits.lastOrNull { it.type != ItemType.RUG } ?: hits.lastOrNull()
    }

    fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double = hypot(a.first - b.first, a.second - b.second)
}
