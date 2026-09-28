package feather.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FeatherFile had no test coverage at all before this. Local (non-instrumented) unit tests get a
 * stub android.jar where every org.json method throws "not mocked" — these tests run under
 * Robolectric instead, which provides a real, working org.json implementation, so save/load can
 * actually be exercised here without a device or emulator.
 */
@RunWith(RobolectricTestRunner::class)
class FeatherFileTest {

    private fun mm(v: Double) = Micrometers.fromMillimeters(v)

    private fun roundTrip(doc: FeatherDocument): FeatherDocument {
        val out = ByteArrayOutputStream()
        FeatherFile.write(out, doc)
        return FeatherFile.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `save then load preserves shape geometry exactly, in micrometres`() {
        val rect = Shape.Rect(PointUm(mm(1.0), mm(2.0)), mm(20.0), mm(10.0))
        val doc = FeatherFile.newDocument(listOf(rect), emptyList(), BoardCalibration(10.0), JobSettings())

        val loaded = roundTrip(doc).shapes.single() as Shape.Rect
        assertEquals(rect.topLeft.x.raw, loaded.topLeft.x.raw)
        assertEquals(rect.widthUm.raw, loaded.widthUm.raw)
        assertEquals(rect.heightUm.raw, loaded.heightUm.raw)
    }

    @Test
    fun `save then load preserves per-shape color and hidden`() {
        val visible = Shape.Line(PointUm(mm(0.0), mm(0.0)), PointUm(mm(10.0), mm(0.0)))
            .withColor(-0x1000000 or 0xE53935) // opaque red
        val hidden = Shape.Circle(PointUm(mm(5.0), mm(5.0)), mm(2.0))
            .withColor(-0x1000000 or 0x1E88E5).withHidden(true)
        val doc = FeatherFile.newDocument(listOf(visible, hidden), emptyList(), BoardCalibration(10.0), JobSettings())

        val loaded = roundTrip(doc).shapes
        assertEquals(-0x1000000 or 0xE53935, loaded[0].colorArgb)
        assertFalse(loaded[0].hidden)
        assertEquals(-0x1000000 or 0x1E88E5, loaded[1].colorArgb)
        assertTrue(loaded[1].hidden)
    }

    @Test
    fun `a legacy file with no colorArgb or hidden fields loads with safe defaults`() {
        // Hand-built JSON mimicking a file saved before color/hidden existed: no "colorArgb"/"hidden" keys at all.
        val legacy = JSONObject().apply {
            put("formatVersion", 1)
            put(
                "shapes",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("type", "line")
                        put("a", JSONObject().apply { put("x", 0L); put("y", 0L); put("z", 0L) })
                        put("b", JSONObject().apply { put("x", 10_000L); put("y", 0L); put("z", 0L) })
                        // deliberately no colorArgb/hidden keys
                    },
                ),
            )
            put("referencedImages", org.json.JSONArray())
            put("pixelsPerMillimeterAt100Pct", 10.0)
            put(
                "job",
                JSONObject().apply {
                    put("totalDepthUm", 2000L); put("stepDownUm", 20L)
                    put("feedRateMmPerMin", 800.0); put("plungeRateMmPerMin", 200.0)
                    put("safeHeightUm", 5000L); put("arcToleranceUm", 5L); put("optimizeTravel", true)
                },
            )
        }
        val doc = FeatherFile.read(ByteArrayInputStream(legacy.toString().toByteArray(Charsets.UTF_8)))
        val shape = doc.shapes.single()
        assertEquals(Shape.DEFAULT_COLOR, shape.colorArgb)
        assertFalse(shape.hidden)
    }
}
