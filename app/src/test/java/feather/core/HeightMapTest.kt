package feather.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeightMapTest {

    private fun flatMap(z: Double) = HeightMap(
        listOf(
            ProbePoint(0.0, 0.0, z), ProbePoint(100.0, 0.0, z),
            ProbePoint(0.0, 100.0, z), ProbePoint(100.0, 100.0, z),
        ),
        cols = 2, rows = 2,
    )

    @Test
    fun `a perfectly flat probed bed gives zero offset everywhere`() {
        val map = flatMap(-2.5)
        assertEquals(0.0, map.offsetAt(50.0, 50.0), 1e-9)
        assertEquals(0.0, map.offsetAt(0.0, 0.0), 1e-9)
        assertEquals(0.0, map.offsetAt(100.0, 100.0), 1e-9)
    }

    @Test
    fun `a tilted bed interpolates linearly between corners`() {
        // z rises 1mm per 100mm of X; Y has no effect.
        val map = HeightMap(
            listOf(
                ProbePoint(0.0, 0.0, 0.0), ProbePoint(100.0, 0.0, 1.0),
                ProbePoint(0.0, 100.0, 0.0), ProbePoint(100.0, 100.0, 1.0),
            ),
            cols = 2, rows = 2,
        )
        // mean z = 0.5, so offset at x=50 (true z=0.5) should be 0.
        assertEquals(0.0, map.offsetAt(50.0, 50.0), 1e-9)
        assertEquals(-0.5, map.offsetAt(0.0, 0.0), 1e-9)
        assertEquals(0.5, map.offsetAt(100.0, 0.0), 1e-9)
    }

    @Test
    fun `gridTargets covers the requested rectangle at the requested density`() {
        val targets = HeightMap.gridTargets(10.0, 10.0, 110.0, 60.0, cols = 3, rows = 2)
        assertEquals(6, targets.size)
        assertEquals(10.0, targets.first().first, 1e-9)
        assertEquals(110.0, targets.last().first, 1e-9)
        assertEquals(60.0, targets.last().second, 1e-9)
    }
}
