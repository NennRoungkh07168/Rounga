package feather.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import feather.core.PixelOps
import feather.link.PhotoImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Non-destructive colour edits: applied when drawing the preview and when exporting, never baked in until you Trace or Save. */
data class Adjust(
    /** -1..1 */ val brightness: Float = 0f,
    /** -1..1 */ val contrast: Float = 0f,
    /** -1..1 (0 = original) */ val saturation: Float = 0f,
    /** -1 cool .. 1 warm */ val warmth: Float = 0f,
    val grayscale: Boolean = false,
    val invert: Boolean = false,
    /** -1 = off; 0..255 turns the picture into hard black and white at that level. */
    val hardThreshold: Int = -1,
)

/** Crop rectangle as fractions of the current picture. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
}

data class PhotoState(
    val loading: Boolean = false,
    val hasImage: Boolean = false,
    val preview: ImageBitmap? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val adjust: Adjust = Adjust(),
    val crop: CropRect = CropRect(),
    val canUndo: Boolean = false,
    val message: String = "",
)

/**
 * A small photo editor for preparing pictures to trace or cut: crop, tone, presets, masking (paint out what
 * you do not want) and background removal. Pixel-changing steps (crop, rotate, mask, remove background,
 * sketch) are undoable; colour edits stay live until you export.
 */
class PhotoEditor(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        const val MAX_SIDE = 1280
        const val MAX_SIDE_MACRO = 1920
        private const val MAX_UNDO = 6

        val presets: List<Pair<String, Adjust>> = listOf(
            "Original" to Adjust(),
            "Vivid" to Adjust(saturation = 0.5f, contrast = 0.2f),
            "Warm" to Adjust(warmth = 0.6f, saturation = 0.15f),
            "Cool" to Adjust(warmth = -0.6f),
            "Fade" to Adjust(contrast = -0.25f, brightness = 0.1f, saturation = -0.3f),
            "B&W" to Adjust(grayscale = true, contrast = 0.15f),
            "Punch" to Adjust(grayscale = true, contrast = 0.6f),
            "Engrave" to Adjust(grayscale = true, contrast = 0.5f, brightness = 0.05f),
            "Stencil" to Adjust(hardThreshold = 128),
        )

        fun matrix(adj: Adjust): ColorMatrix {
            val m = ColorMatrix()
            val gray = adj.grayscale || adj.hardThreshold >= 0
            m.postConcat(ColorMatrix().apply { setSaturation(if (gray) 0f else (1f + adj.saturation).coerceIn(0f, 2f)) })
            val w = adj.warmth
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f + 0.25f * w, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f - 0.25f * w, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            val c = adj.contrast
            val scale = (1f + c * (if (c > 0f) 2f else 1f)).coerceAtLeast(0.05f)
            val shift = 128f * (1f - scale) + adj.brightness * 255f
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, shift,
                        0f, scale, 0f, 0f, shift,
                        0f, 0f, scale, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            if (adj.hardThreshold >= 0) {
                val k = 40f
                val t = -k * adj.hardThreshold
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            k, 0f, 0f, 0f, t,
                            0f, k, 0f, 0f, t,
                            0f, 0f, k, 0f, t,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            if (adj.invert) {
                m.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            return m
        }

        /** [src] with [adj] applied, as a new bitmap. */
        fun render(src: Bitmap, adj: Adjust): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(matrix(adj))
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            return out
        }
    }

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var original: Bitmap? = null
    private var base: Bitmap? = null
    private val undoStack = ArrayList<Bitmap>()
    private val lock = Mutex()
    private var renderJob: Job? = null

    // ---- Loading ------------------------------------------------------------------------

    /** [macro]: a close-up photo — keep more detail instead of the usual downsample (see MAX_SIDE_MACRO). */
    fun load(uri: Uri, macro: Boolean = false) {
        val maxSide = if (macro) MAX_SIDE_MACRO else MAX_SIDE
        scope.launch {
            _state.update { it.copy(loading = true, message = "") }
            try {
                val bmp = withContext(Dispatchers.IO) { PhotoImport.loadBitmap(context, uri, maxSide) }
                lock.withLock { setBase(bmp, fresh = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, message = e.message ?: "Could not open the picture") }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(loading = false, message = "That picture is too large to open") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = "Could not open the picture (${e.message})") }
            }
        }
    }

    private fun setBase(bmp: Bitmap, fresh: Boolean) {
        base = bmp
        if (fresh) original = bmp
        undoStack.clear()
        _state.update { it.copy(adjust = Adjust(), crop = CropRect(), canUndo = false, message = "") }
        rerender()
    }

    // ---- Preview ----------------------------------------------------------------------------

    private fun rerender() {
        val b = base ?: return
        val adj = _state.value.adjust
        renderJob?.cancel()
        renderJob = scope.launch {
            val out = withContext(Dispatchers.Default) { render(b, adj) }
            _state.update {
                it.copy(preview = out.asImageBitmap(), widthPx = out.width, heightPx = out.height, hasImage = true, loading = false)
            }
        }
    }

    fun setAdjust(change: (Adjust) -> Adjust) {
        _state.update { it.copy(adjust = change(it.adjust)) }
        rerender()
    }

    fun applyPreset(adjust: Adjust) {
        _state.update { it.copy(adjust = adjust) }
        rerender()
    }

    /** Stretch the tones so the darkest 1% is black and the lightest 1% is white. */
    fun autoEnhance() {
        val b = base ?: return
        scope.launch {
            val (lo, hi) = withContext(Dispatchers.Default) {
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                PixelOps.autoLevels(IntArray(px.size) { feather.core.ImageTracer.luminance(px[it]) })
            }
            val scale = 255f / (hi - lo).coerceAtLeast(1)
            val contrast = (scale - 1f).coerceIn(-1f, 1f)
            val appliedScale = 1f + contrast * (if (contrast > 0f) 2f else 1f)
            val mid = (lo + hi) / 2f
            val brightness = (-appliedScale * (mid - 128f) / 255f).coerceIn(-1f, 1f)
            setAdjust { it.copy(contrast = contrast, brightness = brightness) }
        }
    }

    // ---- Crop ---------------------------------------------------------------------------------

    fun setCrop(rect: CropRect) {
        val l = rect.left.coerceIn(0f, 0.95f)
        val t = rect.top.coerceIn(0f, 0.95f)
        val r = rect.right.coerceIn(l + 0.05f, 1f)
        val b = rect.bottom.coerceIn(t + 0.05f, 1f)
        _state.update { it.copy(crop = CropRect(l, t, r, b)) }
    }

    /** Largest centred crop with width:height = [ratio] (null = the whole picture). */
    fun setCropAspect(ratio: Float?) {
        val s = _state.value
        if (ratio == null || s.widthPx <= 0 || s.heightPx <= 0) { setCrop(CropRect()); return }
        val imageRatio = s.widthPx.toFloat() / s.heightPx
        val fw: Float
        val fh: Float
        if (ratio > imageRatio) { fw = 1f; fh = imageRatio / ratio } else { fh = 1f; fw = ratio / imageRatio }
        setCrop(CropRect((1f - fw) / 2f, (1f - fh) / 2f, (1f + fw) / 2f, (1f + fh) / 2f))
    }

    fun applyCrop() {
        val crop = _state.value.crop
        if (crop.isFull) return
        edit { b ->
            val x = (crop.left * b.width).toInt().coerceIn(0, b.width - 8)
            val y = (crop.top * b.height).toInt().coerceIn(0, b.height - 8)
            val w = ((crop.right - crop.left) * b.width).toInt().coerceIn(8, b.width - x)
            val h = ((crop.bottom - crop.top) * b.height).toInt().coerceIn(8, b.height - y)
            Bitmap.createBitmap(b, x, y, w, h)
        }
        _state.update { it.copy(crop = CropRect()) }
    }

    // ---- Pixel edits (undoable) ------------------------------------------------------------------

    /** Runs [op] on the current picture off the main thread and makes its result the new picture. */
    private fun edit(op: (Bitmap) -> Bitmap) {
        scope.launch {
            lock.withLock {
                val b = base ?: return@withLock
                val nb = withContext(Dispatchers.Default) { op(b) }
                if (nb === b) return@withLock
                undoStack.add(b)
                if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
                base = nb
                _state.update { it.copy(canUndo = true) }
                rerender()
            }
        }
    }

    fun rotate(degrees: Int) = edit { b ->
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun flip(horizontal: Boolean) = edit { b ->
        val m = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, b.width / 2f, b.height / 2f) else postScale(1f, -1f, b.width / 2f, b.height / 2f)
        }
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Turn the picture into a pencil-style line drawing (good for tracing photos of shaded objects). */
    fun edgeSketch() = edit { b ->
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size) { feather.core.ImageTracer.luminance(px[it]) }
        val sketch = PixelOps.edgeSketch(gray, w, h, 2.2)
        val out = IntArray(px.size) { i -> (0xFF shl 24) or (sketch[i] shl 16) or (sketch[i] shl 8) or sketch[i] }
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        nb.setPixels(out, 0, w, 0, 0, w, h)
        nb
    }

    /** Paint the plain background white so it is not traced. [tolerance] is the colour distance (0..255). */
    fun removeBackground(tolerance: Int) = edit { b ->
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val mask = PixelOps.floodBackground(px, w, h, tolerance)
        for (i in px.indices) if (mask[i]) px[i] = -1 // 0xFFFFFFFF
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        nb.setPixels(px, 0, w, 0, 0, w, h)
        nb
    }

    /**
     * Paint white along a finger stroke. [xs]/[ys] are fractions of the picture (0..1);
     * [radiusFraction] is the brush radius as a fraction of the picture's longer side.
     */
    fun eraseStroke(xs: FloatArray, ys: FloatArray, radiusFraction: Float) {
        if (xs.isEmpty() || xs.size != ys.size) return
        edit { b ->
            val nb = b.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(nb)
            val r = radiusFraction * maxOf(b.width, b.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = -1
                style = Paint.Style.STROKE
                strokeWidth = r * 2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            if (xs.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(xs[0] * b.width, ys[0] * b.height, r, paint)
            } else {
                val path = android.graphics.Path()
                path.moveTo(xs[0] * b.width, ys[0] * b.height)
                for (i in 1 until xs.size) path.lineTo(xs[i] * b.width, ys[i] * b.height)
                canvas.drawPath(path, paint)
            }
            nb
        }
    }

    fun undo() {
        scope.launch {
            lock.withLock {
                if (undoStack.isEmpty()) return@withLock
                base = undoStack.removeAt(undoStack.size - 1)
                _state.update { it.copy(canUndo = undoStack.isNotEmpty()) }
                rerender()
            }
        }
    }

    /** Back to the picture as it was first opened. */
    fun reset() {
        val o = original ?: return
        scope.launch { lock.withLock { setBase(o, fresh = false) } }
    }

    // ---- Output ----------------------------------------------------------------------------------

    /** The picture with every edit applied (for tracing). */
    suspend fun exportBitmap(): Bitmap? {
        val b = base ?: return null
        val adj = _state.value.adjust
        return withContext(Dispatchers.Default) { render(b, adj) }
    }

    /** Writes the edited picture as PNG. Returns null on success or a message. */
    suspend fun saveTo(uri: Uri): String? {
        val bmp = exportBitmap() ?: return "Open a picture first"
        return try {
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the destination")
                out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            null
        } catch (e: IOException) {
            "Save failed: ${e.message}"
        }
    }
}
