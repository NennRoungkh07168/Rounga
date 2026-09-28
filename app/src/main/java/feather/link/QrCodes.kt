package feather.link

import android.app.Activity
import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** QR generation (zxing, pure Java) and scanning (Google Play Services' own scanner UI, no camera code of our own). */
object QrCodes {

    /** Renders [text] as a black-on-white QR bitmap. Throws IllegalArgumentException if [text] is too long to fit. */
    fun generateBitmap(text: String, sizePx: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    /**
     * Opens Google's own scan sheet over the app (no CAMERA permission or preview code needed here).
     * Returns the scanned text, or null if the person cancelled. Throws if Play Services can't run
     * the scanner (rare: very old or Play-Services-free devices).
     */
    suspend fun scan(activity: Activity): String? = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        scanner.startScan()
            .addOnSuccessListener { barcode: Barcode -> if (cont.isActive) cont.resume(barcode.rawValue) }
            .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            .addOnFailureListener { e: Exception -> if (cont.isActive) cont.cancel(e) }
    }
}
