package feather.interior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteriorTest {

    private val scene = Scene.starter()

    @Test
    fun `a rotated footprint keeps its area and contains its centre`() {
        val sofa = scene.items.first { it.type == ItemType.SOFA }.copy(rotationDeg = 90.0)
        val c = PlanGeometry.corners(sofa)
        val w = PlanGeometry.distance(c[0], c[1])
        val d = PlanGeometry.distance(c[1], c[2])
        assertEquals(sofa.widthCm, w, 0.001)
        assertEquals(sofa.depthCm, d, 0.001)
        assertTrue(PlanGeometry.contains(sofa, sofa.xCm, sofa.yCm))
        assertFalse(PlanGeometry.contains(sofa, sofa.xCm + 500, sofa.yCm))
    }

    @Test
    fun `rotating 90 degrees swaps the extents on screen`() {
        val item = scene.items.first { it.type == ItemType.SOFA }.copy(rotationDeg = 90.0, xCm = 100.0, yCm = 100.0)
        val xs = PlanGeometry.corners(item).map { it.first }
        assertEquals(item.depthCm, xs.max() - xs.min(), 0.001)
    }

    @Test
    fun `hit test prefers furniture over the rug beneath it`() {
        val hit = PlanGeometry.itemAt(scene, 250.0, 175.0) // coffee table sits on the rug
        assertEquals(ItemType.COFFEE_TABLE, hit?.type)
        val rugOnly = PlanGeometry.itemAt(scene, 250.0, 270.0)
        assertEquals(ItemType.RUG, rugOnly?.type)
    }

    @Test
    fun `every furniture type has parts inside its own footprint`() {
        for (t in ItemType.values()) {
            val item = RoomItem("x", t, t.label, 0.0, 0.0, t.widthCm, t.depthCm, t.heightCm, t.elevationCm, 0.0, t.color)
            val parts = Furniture.partsOf(item)
            assertTrue("${t.label} has parts", parts.isNotEmpty())
            for (p in parts) {
                assertTrue("${t.label} part width", p.w > 0 && p.d > 0 && p.h > 0)
                assertTrue("${t.label} inside width", Math.abs(p.lx) + p.w / 2 <= t.widthCm / 2 + 3.0)
            }
        }
    }

    @Test
    fun `the 3D view produces polygons and hides the walls that face the camera`() {
        val polys = Render3D.render(scene, Camera3D(), 800f, 600f, null)
        assertTrue(polys.size > 100)
        assertTrue(polys.all { it.xs.size == 4 && it.ys.size == 4 })
        // Looking from the front-left, an empty room shows the floor plus exactly two walls.
        val empty = Render3D.render(Scene(scene.room, emptyList()), Camera3D(), 800f, 600f, null)
        val floorTiles = Math.ceil(scene.room.widthCm / 50.0).toInt() * Math.ceil(scene.room.depthCm / 50.0).toInt()
        assertEquals(floorTiles + 2, empty.size)
    }

    @Test
    fun `plan export scales the room and flips y so the back wall is at the top`() {
        val shapes = PlanExport.shapes(scene, 50, marginMm = 10.0)
        assertEquals(1 + scene.items.size, shapes.size)
        val room = shapes[0] as feather.core.Shape.Rect
        assertEquals(scene.room.widthCm * 10.0 / 50 * 1000, room.widthUm.raw.toDouble(), 1.0) // 500 cm at 1:50 = 100 mm
        // The sofa hugs the back wall, so on paper it is near the top (largest y), not the bottom.
        val sofaIndex = scene.items.indexOfFirst { it.type == ItemType.SOFA } + 1
        val sofa = shapes[sofaIndex] as feather.core.Shape.Polyline
        val roomTopY = room.topLeft.y.raw + room.heightUm.raw
        assertTrue(sofa.points.maxOf { it.y.raw } <= roomTopY)
        assertTrue(sofa.points.maxOf { it.y.raw } > roomTopY - 30_000)
        assertNotNull(sofa.points.first())
    }
}
