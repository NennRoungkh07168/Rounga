package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeAnalysisTest {

    @Test
    fun `parses moves, modal feed and compact words`() {
        val moves = GcodeAnalysis.parse("G21\nG90\nG0 X10 Y0\nG1X20Y0F600\nG1 X20 Y10 ; comment\n")
        assertEquals(3, moves.size)
        assertTrue(moves[0].rapid)
        assertEquals(600.0, moves[1].feedMmPerMin, 0.0)
        assertEquals(600.0, moves[2].feedMmPerMin, 0.0) // F is modal
    }

    @Test
    fun `time estimate follows feed rate`() {
        val moves = GcodeAnalysis.parse("G1 X60 F600\n")
        val stats = GcodeAnalysis.stats(moves, 1)
        assertEquals(6.0, stats.estimatedSeconds, 0.001) // 60 mm at 600 mm/min = 6 s
        assertEquals(60.0, stats.cutLengthMm, 0.001)
    }

    @Test
    fun `generated job reports bounds outside a small bed`() {
        val job = CutJob(listOf(Shape.Rect(PointUm(Micrometers.fromMillimeters(0.0), Micrometers.fromMillimeters(0.0)),
            Micrometers.fromMillimeters(50.0), Micrometers.fromMillimeters(50.0))))
        val text = GCodeGenerator.generate(job)
        val stats = GcodeAnalysis.stats(GcodeAnalysis.parse(text), 1)
        assertTrue(GcodeAnalysis.boundsProblems(stats, 100.0, 100.0).isEmpty())
        assertEquals(2, GcodeAnalysis.boundsProblems(stats, 40.0, 40.0).size)
    }
}
