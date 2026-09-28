package feather.link

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Add ALL of these to AndroidManifest.xml — the OS grants the right subset
 * per device/API level, so it's safe to declare the full set unconditionally:
 *
 * ```xml
 * <!-- API 30 and below: classic runtime permission, used for BLE scan results -->
 * <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
 *     android:maxSdkVersion="30" />
 *
 * <!-- API 31+ (Android 12+): the split Bluetooth permissions -->
 * <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
 *     android:usesPermissionFlags="neverForLocation"
 *     tools:targetApi="s" />
 * <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *
 * <!-- Pre-31 legacy Bluetooth permissions, ignored by 31+ -->
 * <uses-permission android:name="android.permission.BLUETOOTH"
 *     android:maxSdkVersion="30" />
 * <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
 *     android:maxSdkVersion="30" />
 * ```
 *
 * `neverForLocation` on `BLUETOOTH_SCAN` is correct here: Feather only uses
 * scan results to list nearby machines to connect to, never to derive the
 * user's physical location, so it can skip requesting FINE_LOCATION on 31+.
 */
object BlePermissions {

    /** The permissions this device's API level actually needs for a BLE
     *  scan-and-connect flow. Request exactly this list, not a fixed one —
     *  requesting BLUETOOTH_SCAN/CONNECT pre-31 (or FINE_LOCATION on 31+
     *  when unnecessary) just adds a prompt the OS will silently no-op. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasRequiredPermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Bluetooth itself being off is a separate condition from permissions
     *  being granted — check both before starting a scan. */
    fun isBluetoothEnabled(context: Context): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter
        return adapter?.isEnabled == true
    }

    /**
     * Registers the permission-request launcher. Call this in `onCreate`
     * (before the Activity reaches STARTED), then call `launch()` on the
     * returned object when the user taps "scan for machines".
     *
     * Example, inside an Activity:
     * ```
     * private val blePermissionLauncher = BlePermissions.registerLauncher(this) { granted ->
     *     if (granted) startDeviceScan() else showPermissionDeniedMessage()
     * }
     * // later, on button tap:
     * if (BlePermissions.hasRequiredPermissions(this)) startDeviceScan()
     * else blePermissionLauncher.launch(BlePermissions.requiredPermissions())
     * ```
     */
    fun registerLauncher(
        activity: androidx.activity.ComponentActivity,
        onResult: (allGranted: Boolean) -> Unit,
    ): ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            onResult(results.values.all { it })
        }
}
