package feather.model

import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialGuesserTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `guesses are a probability-like distribution that sums to about one`() {
        val w = 16; val h = 16
        val argb = IntArray(w * h) { rgb(150, 100, 60) } // warm brown, flat -- wood-ish
        val gray = IntArray(argb.size) { 110 }
        val guesses = MaterialGuesser.guess(argb, w, h, gray)
        assertTrue(guesses.isNotEmpty())
        assertTrue(guesses.size <= 3)
        val total = guesses.sumOf { it.confidence }
        assertTrue("total was $total", total in 0.0..1.0001) // top 3 of several scored categories: a share of the whole, not a strict partition
        assertTrue(guesses.all { it.confidence in 0.0..1.0 })
    }

    @Test
    fun `an empty picture produces no guesses instead of crashing`() {
        assertTrue(MaterialGuesser.guess(IntArray(0), 0, 0, IntArray(0)).isEmpty())
    }
}
