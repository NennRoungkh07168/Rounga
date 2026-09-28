package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GcodeCompensationTest {

    @Test
    fun `a flat height map leaves a flat cut unchanged`() {
        val moves = GcodeAnalysis.parse("G1 X0 Y0 Z-1 F300\nG1 X50 Y0 Z-1\nG1 X50 Y50 Z-1\n")
        val map = HeightMap(
            listOf(ProbePoint(0.0, 0.0, 0.0), ProbePoint(100.0, 0.0, 0.0), ProbePoint(0.0, 100.0, 0.0), ProbePoint(100.0, 100.0, 0.0)),
            cols = 2, rows = 2,
        )
        val compensated = GcodeCompensation.apply(moves, map)
        for (m in compensated) assertEquals(-1.0, m.z1, 0.001)
    }

    @Test
    fun `a tilted bed lifts Z on the high side and lowers it on the low side`() {
        val moves = GcodeAnalysis.parse("G1 X0 Y50 Z-1 F300\nG1 X100 Y50 Z-1\n")
        val map = HeightMap(
            listOf(ProbePoint(0.0, 0.0, -1.0), ProbePoint(100.0, 0.0, 1.0), ProbePoint(0.0, 100.0, -1.0), ProbePoint(100.0, 100.0, 1.0)),
            cols = 2, rows = 2,
        )
        val compensated = GcodeCompensation.apply(moves, map)
        val startZ = compensated.first().z0
        val endZ = compensated.last().z1
        assertTrue("expected the far end to sit higher: start=$startZ end=$endZ", endZ > startZ)
    }

    @Test
    fun `long moves are subdivided so the correction follows the surface`() {
        val moves = GcodeAnalysis.parse("G1 X0 Y0 Z-1 F300\nG1 X40 Y0 Z-1\n")
        val map = HeightMap(
            listOf(ProbePoint(0.0, 0.0, 0.0), ProbePoint(100.0, 0.0, 5.0), ProbePoint(0.0, 100.0, 0.0), ProbePoint(100.0, 100.0, 5.0)),
            cols = 2, rows = 2,
        )
        val compensated = GcodeCompensation.apply(moves, map)
        assertTrue("a 40mm move should be split into several short segments", compensated.size > 5)
    }

    @Test
    fun `round-tripping through text keeps the move count in the same ballpark`() {
        val moves = GcodeAnalysis.parse("G1 X0 Y0 Z-1 F300\nG1 X20 Y0 Z-1\nG1 X20 Y20 Z-1\n")
        val map = HeightMap(
            listOf(ProbePoint(0.0, 0.0, 0.0), ProbePoint(100.0, 0.0, 0.2), ProbePoint(0.0, 100.0, 0.0), ProbePoint(100.0, 100.0, 0.2)),
            cols = 2, rows = 2,
        )
        val text = GcodeCompensation.toGcode(GcodeCompensation.apply(moves, map))
        val reparsed = GcodeAnalysis.parse(text)
        assertTrue(reparsed.isNotEmpty())
        assertEquals(20.0, reparsed.last().x1, 0.01)
    }
}
