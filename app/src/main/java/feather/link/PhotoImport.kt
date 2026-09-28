package feather.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import feather.core.ImageTracer
import java.io.IOException
import kotlin.math.max

/** A photo reduced to what tracing needs: luminance per pixel. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray)

object PhotoImport {

    /**
     * Decode [uri], honour the camera's EXIF rotation and shrink so the longer side is at most [maxSide]
     * pixels. Throws [IOException] with a message fit for the UI.
     */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IOException("Cannot open the picture")).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This picture format is not supported")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = (resolver.openInputStream(uri) ?: throw IOException("Cannot open the picture")).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Could not decode the picture")

        val rotation = try {
            resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        } catch (e: IOException) {
            0
        }
        val upright = if (rotation == 0) decoded else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        }
        val (w, h) = ImageTracer.fitSize(upright.width, upright.height, maxSide)
        return if (w != upright.width || h != upright.height) Bitmap.createScaledBitmap(upright, w, h, true) else upright
    }

    /** Flatten [src] to luminance for tracing. Transparent areas count as white paper. */
    fun fromBitmap(src: Bitmap, maxSide: Int = 480): GrayImage {
        val (w, h) = ImageTracer.fitSize(src.width, src.height, maxSide)
        val scaled = if (w != src.width || h != src.height) Bitmap.createScaledBitmap(src, w, h, true) else src
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = argb[i]
            val alpha = (p ushr 24) and 0xFF
            val lum = ImageTracer.luminance(p)
            (lum * alpha + 255 * (255 - alpha)) / 255
        }
        return GrayImage(w, h, gray)
    }

    fun load(context: Context, uri: Uri, maxSide: Int = 480): GrayImage = fromBitmap(loadBitmap(context, uri, maxSide), maxSide)

    private fun exifRotation(stream: java.io.InputStream): Int =
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
