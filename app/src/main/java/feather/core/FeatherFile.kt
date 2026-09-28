package feather.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Any problem reading a `.feather` file, with a message fit for the UI. */
class FeatherFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The on-disk document. */
data class FeatherDocument(
    val formatVersion: Int,
    val shapes: List<Shape>,
    val referencedImages: List<String>,
    /** Screen scale of the device that saved the file; informational, not applied on load. */
    val calibration: BoardCalibration,
    val settings: JobSettings,
)

/**
 * Plain-JSON save/load format (`.feather`).
 *
 *  - Shapes are stored as integer µm: a round trip introduces zero drift.
 *  - Images are stored by path, not embedded.
 *  - `formatVersion` is checked: files from a *newer* Feather are rejected with
 *    a clear message instead of being silently misread. Version 1 files (the
 *    original layout) load unchanged; optional newer fields fall back to
 *    defaults.
 *  - File writes are atomic (temp file + rename), so a crash mid-save can never
 *    destroy the previous good copy.
 */
object FeatherFile {

    const val CURRENT_FORMAT_VERSION = 1

    // ---- Streams (used with Android's Storage Access Framework) -------------

    fun write(out: OutputStream, document: FeatherDocument) {
        out.write(toJson(document).toString(2).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun read(input: InputStream): FeatherDocument {
        val bytes = input.readBytes()
        // The picker offers every file. A photo or G-code file decoded as UTF-8 used to surface as
        // "Value ����JFIF of type String cannot be converted to JSONObject"; say what it really is.
        when (FileSniffer.sniff(bytes)) {
            FileKind.IMAGE -> throw FeatherFileException(
                "This is a picture, not a .feather drawing. Use Design > Trace to turn a photo into a toolpath.",
            )
            FileKind.GCODE -> throw FeatherFileException(
                "This is a G-code file, not a .feather drawing. Use Tools > Simulate to preview it.",
            )
            FileKind.EMPTY -> throw FeatherFileException("The file is empty.")
            FileKind.FEATHER, FileKind.OTHER -> Unit
        }
        val text = bytes.toString(Charsets.UTF_8)
        try {
            return fromJson(JSONObject(text))
        } catch (e: JSONException) {
            throw FeatherFileException("Not a valid .feather file. It does not contain readable drawing data.", e)
        } catch (e: IllegalArgumentException) {
            throw FeatherFileException("Corrupt .feather file (${e.message})", e)
        }
    }

    // ---- Plain files ---------------------------------------------------------

    fun save(file: File, document: FeatherDocument) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { write(it, document) }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    fun load(file: File): FeatherDocument = file.inputStream().use { read(it) }

    fun newDocument(
        shapes: List<Shape>,
        referencedImages: List<String>,
        calibration: BoardCalibration,
        settings: JobSettings,
    ) = FeatherDocument(CURRENT_FORMAT_VERSION, shapes, referencedImages, calibration, settings)

    // ---- JSON <-> model --------------------------------------------------------

    private fun toJson(doc: FeatherDocument): JSONObject = JSONObject().apply {
        put("formatVersion", doc.formatVersion)
        put("shapes", JSONArray().also { arr -> doc.shapes.forEach { arr.put(shapeToJson(it)) } })
        put("referencedImages", JSONArray().also { arr -> doc.referencedImages.forEach { arr.put(it) } })
        put("pixelsPerMillimeterAt100Pct", doc.calibration.pixelsPerMillimeterAt100Pct)
        put("job", JSONObject().apply {
            put("totalDepthUm", doc.settings.totalDepthUm.raw)
            put("stepDownUm", doc.settings.stepDownUm.raw)
            put("feedRateMmPerMin", doc.settings.feedRateMmPerMin)
            put("plungeRateMmPerMin", doc.settings.plungeRateMmPerMin)
            put("safeHeightUm", doc.settings.safeHeightUm.raw)
            put("arcToleranceUm", doc.settings.arcToleranceUm)
            put("optimizeTravel", doc.settings.optimizeTravel)
        })
    }

    private fun fromJson(json: JSONObject): FeatherDocument {
        val version = json.optInt("formatVersion", 1)
        if (version < 1) throw FeatherFileException("Invalid format version $version")
        if (version > CURRENT_FORMAT_VERSION) {
            throw FeatherFileException(
                "This file was saved by a newer version of Feather (format $version). Please update the app.",
            )
        }
        val defaults = JobSettings()
        val job = json.getJSONObject("job")
        val settings = JobSettings(
            totalDepthUm = Micrometers(job.getLong("totalDepthUm")),
            stepDownUm = Micrometers(job.getLong("stepDownUm")),
            feedRateMmPerMin = job.getDouble("feedRateMmPerMin"),
            plungeRateMmPerMin = job.getDouble("plungeRateMmPerMin"),
            safeHeightUm = Micrometers(job.getLong("safeHeightUm")),
            arcToleranceUm = job.optLong("arcToleranceUm", defaults.arcToleranceUm),
            optimizeTravel = job.optBoolean("optimizeTravel", defaults.optimizeTravel),
        )
        val shapesJson = json.getJSONArray("shapes")
        val shapes = (0 until shapesJson.length()).map { shapeFromJson(shapesJson.getJSONObject(it)) }
        val imagesJson = json.optJSONArray("referencedImages") ?: JSONArray()
        val images = (0 until imagesJson.length()).map { imagesJson.getString(it) }
        return FeatherDocument(
            formatVersion = version,
            shapes = shapes,
            referencedImages = images,
            calibration = BoardCalibration(json.getDouble("pixelsPerMillimeterAt100Pct")),
            settings = settings,
        )
    }

    private fun pointToJson(p: PointUm) = JSONObject().apply {
        put("x", p.x.raw); put("y", p.y.raw); put("z", p.z.raw)
    }

    private fun pointFromJson(o: JSONObject) = PointUm(
        Micrometers(o.getLong("x")),
        Micrometers(o.getLong("y")),
        Micrometers(o.optLong("z", 0L)),
    )

    private fun shapeToJson(s: Shape): JSONObject = when (s) {
        is Shape.Line -> JSONObject().apply {
            put("type", "line"); put("a", pointToJson(s.a)); put("b", pointToJson(s.b))
        }
        is Shape.Rect -> JSONObject().apply {
            put("type", "rect"); put("topLeft", pointToJson(s.topLeft))
            put("widthUm", s.widthUm.raw); put("heightUm", s.heightUm.raw)
        }
        is Shape.Circle -> JSONObject().apply {
            put("type", "circle"); put("center", pointToJson(s.center)); put("radiusUm", s.radiusUm.raw)
        }
        is Shape.Polyline -> JSONObject().apply {
            put("type", "polyline")
            put("points", JSONArray().also { arr -> s.points.forEach { arr.put(pointToJson(it)) } })
        }
    }.apply {
        // Common to every variant: written once here so a new Shape subtype can't forget it.
        put("colorArgb", s.colorArgb); put("hidden", s.hidden)
    }

    private fun shapeFromJson(o: JSONObject): Shape {
        val colorArgb = if (o.has("colorArgb")) o.getInt("colorArgb") else Shape.DEFAULT_COLOR
        val hidden = if (o.has("hidden")) o.getBoolean("hidden") else false   // older .feather files have neither field
        val base = when (val type = o.getString("type")) {
            "line" -> Shape.Line(pointFromJson(o.getJSONObject("a")), pointFromJson(o.getJSONObject("b")))
            "rect" -> Shape.Rect(
                pointFromJson(o.getJSONObject("topLeft")),
                Micrometers(o.getLong("widthUm")),
                Micrometers(o.getLong("heightUm")),
            )
            "circle" -> Shape.Circle(pointFromJson(o.getJSONObject("center")), Micrometers(o.getLong("radiusUm")))
            "polyline" -> {
                val pts = o.getJSONArray("points")
                Shape.Polyline((0 until pts.length()).map { pointFromJson(pts.getJSONObject(it)) })
            }
            else -> throw FeatherFileException("Unknown shape type \"$type\" in .feather file")
        }
        return base.withColor(colorArgb).withHidden(hidden)
    }
}
