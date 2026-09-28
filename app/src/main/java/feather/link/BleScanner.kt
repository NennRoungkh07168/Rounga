package feather.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Finds nearby BLE devices so the operator can pick a machine instead of typing a MAC address. */
class BleScanner(private val context: Context) {

    data class Found(val address: String, val name: String?, val rssi: Int)

    /**
     * Cold flow of advertisements; collect it to scan, cancel to stop. Fails
     * with [MachineLinkException] if Bluetooth is off or the scan is refused.
     * Caller must already hold the runtime permissions ([BlePermissions]).
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<Found> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            close(MachineLinkException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(Found(result.device.address, result.scanRecord?.deviceName, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                close(MachineLinkException("Bluetooth scan failed (code $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            close(MachineLinkException("Bluetooth permission is missing", e))
            return@callbackFlow
        }
        awaitClose {
            try { scanner.stopScan(cb) } catch (ignored: SecurityException) {}
        }
    }
}
