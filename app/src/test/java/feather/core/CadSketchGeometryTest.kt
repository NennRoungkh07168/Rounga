package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CadSketchGeometryTest {
    @Test fun rectangleDragSnapsToMillimetres() {
        val profile = CadSketchGeometry.rectangle(81f, 124f, 4f)
        assertNotNull(profile)
        assertEquals(CadSolid.Kind.BOX, profile!!.kind)
        assertEquals(20.0, profile.widthMm, 0.0)
        assertEquals(31.0, profile.depthMm, 0.0)
    }

    @Test fun circleUsesSmallerDragDimensionAsDiameter() {
        val profile = CadSketchGeometry.circle(121f, 79f, 4f)
        assertNotNull(profile)
        assertEquals(CadSolid.Kind.CYLINDER, profile!!.kind)
        assertEquals(20.0, profile.widthMm, 0.0)
        assertEquals(profile.widthMm, profile.depthMm, 0.0)
    }

    @Test fun tinyProfilesAreRejected() {
        assertNull(CadSketchGeometry.rectangle(2f, 2f, 4f))
        assertNull(CadSketchGeometry.circle(2f, 2f, 4f))
    }

    @Test fun profileCreatesParametricPadSolid() {
        val solid = CadSketchProfile(CadSolid.Kind.BOX, 24.0, 16.0).toPad("Sketch Pad", 7L, 8.0)
        assertEquals(24.0, solid.width, 0.0)
        assertEquals(16.0, solid.depth, 0.0)
        assertEquals(8.0, solid.height, 0.0)
        assertEquals("Interactive 2D sketch → Pad (8.0 mm)", solid.feature)
    }
}
