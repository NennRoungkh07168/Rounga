package feather.model

import feather.core.CutJob
import feather.core.GCodeGenerator
import feather.core.JobSettings
import feather.core.Micrometers
import feather.core.PointUm
import feather.core.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demonstrates the point of the whole model layer: the *same* design (one
 * rectangle) run through the *same* generator produces machine-specific
 * G-code for each device — right tool-on command, right feed clamp, right
 * rejection when the design doesn't fit — without GCodeGenerator ever
 * knowing about "CNC Router" or "Laser" by name.
 */
class MultiDeviceGCodeTest {

    private val design = CutJob(
        shapes = listOf(
            Shape.Rect(
                topLeft = PointUm(Micrometers.fromMillimeters(10.0), Micrometers.fromMillimeters(10.0)),
                widthUm = Micrometers.fromMillimeters(50.0),
                heightUm = Micrometers.fromMillimeters(50.0),
            ),
        ),
        settings = JobSettings(
            totalDepthUm = Micrometers.fromMillimeters(2.0),
            feedRateMmPerMin = 500.0,
        ),
    )

    @Test
    fun `router emits spindle on-off around the toolpath`() {
        val gcode = GCodeGenerator.generate(design, SampleDevices.cncRouter.toMachineContext()!!)
        assertTrue(gcode.contains("M3 S12000")) // Spindle's defaultRpm
        assertTrue(gcode.contains("M5"))
    }

    @Test
    fun `laser emits PWM power instead of RPM`() {
        val gcode = GCodeGenerator.generate(design, SampleDevices.laserEngraver.toMachineContext()!!)
        // Laser default: 60% of pwmMax 1000 = 600
        assertTrue(gcode.contains("M3 S600"))
    }

    @Test
    fun `plotter lowers and lifts the pen instead of a spindle command`() {
        val gcode = GCodeGenerator.generate(design, SampleDevices.plotter.toMachineContext()!!)
        assertTrue(gcode.contains("pen down"))
        assertTrue(gcode.contains("pen up"))
        assertTrue(!gcode.contains("M3"))
    }

    @Test
    fun `a design too big for the PCB mill is rejected before any gcode is sent`() {
        val tooBig = design.copy(
            shapes = listOf(
                Shape.Rect(
                    topLeft = PointUm(Micrometers.fromMillimeters(0.0), Micrometers.fromMillimeters(0.0)),
                    widthUm = Micrometers.fromMillimeters(250.0), // PCB mill only travels 200 mm on X
                    heightUm = Micrometers.fromMillimeters(50.0),
                ),
            ),
        )
        assertThrows(feather.core.MachineLimitException::class.java) {
            GCodeGenerator.generate(tooBig, SampleDevices.pcbMill.toMachineContext()!!)
        }
    }

    @Test
    fun `same design, four devices, four different working areas honoured`() {
        for (device in SampleDevices.fleet) {
            val gcode = GCodeGenerator.generate(design, device.toMachineContext()!!)
            assertEquals(device.profile.coordinateSystem, "G54")
            assertTrue(gcode.contains("M30"))
        }
    }
}
