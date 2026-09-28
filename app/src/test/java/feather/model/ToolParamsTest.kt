package feather.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolParamsTest {

    private val laser10 = ToolHead.Laser(id = "a", name = "Laser 10W", maxPowerWatts = 10.0, defaultPowerPercent = 60)
    private val laser20 = ToolHead.Laser(id = "b", name = "Laser 20W", maxPowerWatts = 20.0, defaultPowerPercent = 40)

    @Test
    fun `preview lists every parameter and marks the ones that differ`() {
        val changes = ToolParams.preview(laser10, laser20)
        assertEquals(ToolParams.rows(laser10).size, changes.size)
        assertTrue(changes.first { it.param.key == "maxPowerWatts" }.differs)
        assertFalse(changes.first { it.param.key == "pwmMax" }.differs)
    }

    @Test
    fun `apply copies only the ticked keys and keeps id and name`() {
        val result = ToolParams.apply(laser10, laser20, setOf("defaultPowerPercent")) as ToolHead.Laser
        assertEquals(60, result.defaultPowerPercent)
        assertEquals(20.0, result.maxPowerWatts, 0.0) // not ticked, untouched
        assertEquals("b", result.id)
        assertEquals("Laser 20W", result.name)
    }

    @Test
    fun `different families cannot be copied onto each other`() {
        val pen = ToolHead.Pen(id = "p")
        assertFalse(ToolParams.sameFamily(laser10, pen))
        assertTrue(ToolParams.preview(laser10, pen).isEmpty())
        assertEquals(pen, ToolParams.apply(laser10, pen, setOf("defaultPowerPercent")))
    }

    @Test
    fun `profile copy skips axes the target does not have`() {
        val cnc = Presets.starter().first { it.id == "preset-cnc-400" }
        val laser = Presets.starter().first { it.id == "preset-laser-300" }
        val changes = ProfileParams.preview(cnc.profile, laser.profile)
        assertTrue(changes.none { it.param.key.startsWith("Z.") })
        val applied = ProfileParams.apply(cnc.profile, laser.profile, setOf("X.travel"))
        assertEquals(400.0, applied.travelMm(Axis.X), 0.0)
        assertEquals(300.0, applied.travelMm(Axis.Y), 0.0)
    }
}
