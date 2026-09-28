package feather.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {

    @Test
    fun `a material survives an encode-decode round trip`() {
        val m = MaterialPreset(
            id = "x", name = "Acrylic (cast)", category = MaterialCategory.ACRYLIC, thicknessMm = 3.0,
            forLaser = true, laserPowerPercent = 80, laserSpeedMmPerMin = 180.0, laserPasses = 1, notes = "test",
        )
        val text = QrPayload.encodeMaterial(m)
        assertEquals(QrPayload.Kind.MATERIAL, QrPayload.kindOf(text))
        val back = QrPayload.decodeMaterial(text, "new-id")
        assertNotNull(back)
        assertEquals("new-id", back!!.id) // decoding always assigns a fresh id
        assertEquals(m.name, back.name)
        assertEquals(m.laserPowerPercent, back.laserPowerPercent)
        assertEquals(m.thicknessMm, back.thicknessMm, 0.0)
    }

    @Test
    fun `a device's profile and tool rows survive a round trip`() {
        val preset = Presets.starter().first { it.id == "preset-laser-300" }
        val tool = preset.tools.first { it.id == "tool-laser-10w" }
        val text = QrPayload.encodeDevice(preset.name, preset.profile.machineType, preset.controller, preset.profile, tool)
        assertEquals(QrPayload.Kind.DEVICE, QrPayload.kindOf(text))
        val imp = QrPayload.decodeDevice(text)
        assertNotNull(imp)
        assertEquals(MachineType.LASER, imp!!.machineType)
        assertEquals("Laser", imp.toolFamily)
        val rebuilt = Presets.fromImport(imp, ConnectionType.BLE)
        assertEquals(preset.profile.travelMm(Axis.X), rebuilt.profile.travelMm(Axis.X), 0.001)
        val rebuiltTool = rebuilt.tools.first() as ToolHead.Laser
        assertEquals((tool as ToolHead.Laser).defaultPowerPercent, rebuiltTool.defaultPowerPercent)
    }

    @Test
    fun `unrelated text is neither kind and decodes to null`() {
        assertEquals(QrPayload.Kind.UNKNOWN, QrPayload.kindOf("https://example.com"))
        assertNull(QrPayload.decodeMaterial("https://example.com", "id"))
        assertNull(QrPayload.decodeDevice("not json at all"))
    }

    @Test
    fun `garbled json after a valid prefix fails safely instead of crashing`() {
        assertNull(QrPayload.decodeMaterial("FEATHER-MATERIAL-1:{not valid json", "id"))
    }

    @Test
    fun `built-in materials all have positive thickness and speeds`() {
        val all = MaterialPresets.builtIn()
        assertTrue(all.isNotEmpty())
        for (m in all) {
            assertTrue("${m.name} thickness", m.thicknessMm > 0.0)
            if (m.forLaser) {
                assertTrue("${m.name} power", m.laserPowerPercent in 1..100)
                assertTrue("${m.name} speed", m.laserSpeedMmPerMin > 0.0)
            } else {
                assertTrue("${m.name} feed", m.cutFeedMmPerMin > 0.0)
                assertTrue("${m.name} step", m.stepDownMm > 0.0)
            }
        }
    }
}
