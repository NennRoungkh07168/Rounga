package feather.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * BLE transport using the Nordic UART Service (NUS) profile exposed by most
 * GRBL/Marlin BLE bridges (ESP32 BLE-serial firmware, nRF52 UART, ...).
 *
 * Fixes over the first version, all of which would have stopped a real job:
 *  - Notifications are now actually enabled (the CCCD descriptor is written);
 *    `setCharacteristicNotification` alone never delivers anything.
 *  - Writes are sent one at a time and each waits for `onCharacteristicWrite`;
 *    firing several `writeCharacteristic` calls back-to-back drops all but the first.
 *  - Incoming notifications are re-assembled into lines (a reply such as
 *    "ok\r\n" can arrive split across packets).
 *  - The negotiated MTU is used for chunking instead of a hard-coded 20 bytes.
 *  - The emergency stop retries while the radio is busy instead of being lost.
 *  - Both the pre-33 and Android 13+ GATT APIs are handled; missing runtime
 *    permission surfaces as an error message instead of a crash.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleMachineLink(
    private val context: Context,
    private val device: BluetoothDevice,
) : GrblStreamingLink() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write to machine
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify from machine
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_CHUNK_BYTES = 20
        private const val REQUESTED_MTU = 247
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val REALTIME_ATTEMPTS = 100 // x 10 ms
    }

    private val gattLock = Any()
    private val rxBuffer = StringBuilder()
    private val writeStatus = Channel<Int>(Channel.UNLIMITED)
    private val realtimeExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var chunkBytes = DEFAULT_CHUNK_BYTES
    @Volatile private var ready = false
    @Volatile private var connectResult: CompletableDeferred<Unit>? = null

    // ---- Connect ---------------------------------------------------------------

    override suspend fun connect() {
        val result = CompletableDeferred<Unit>()
        connectResult = result
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw MachineLinkException("Bluetooth permission is missing", e)
        }
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            disconnect()
            throw MachineLinkException("Timed out connecting to ${device.address}")
        }
    }

    private fun failConnect(g: BluetoothGatt, message: String) {
        connectResult?.completeExceptionally(MachineLinkException(message))
        try { g.disconnect() } catch (ignored: SecurityException) {}
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val message = "Bluetooth link dropped (status $status)"
                    connectResult?.completeExceptionally(MachineLinkException(message))
                    ready = false
                    onTransportClosed(message)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) chunkBytes = (mtu - 3).coerceAtLeast(DEFAULT_CHUNK_BYTES)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            val tx = service?.getCharacteristic(TX_CHAR_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                failConnect(g, "Machine UART service not found (is this a Nordic-UART BLE bridge?)")
                return
            }
            rxChar = rx
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null || !g.setCharacteristicNotification(tx, true)) {
                failConnect(g, "Could not subscribe to machine replies")
                return
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            if (!started) failConnect(g, "Could not enable machine notifications")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                connectResult?.complete(Unit)
            } else {
                failConnect(g, "Could not enable machine notifications (status $status)")
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { handleIncoming(it) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeStatus.trySend(status)
        }
    }

    // ---- Receive ---------------------------------------------------------------

    private fun handleIncoming(bytes: ByteArray) {
        val lines = ArrayList<String>()
        synchronized(rxBuffer) {
            rxBuffer.append(String(bytes, Charsets.US_ASCII))
            while (true) {
                val nl = rxBuffer.indexOf("\n")
                if (nl < 0) break
                val line = rxBuffer.substring(0, nl).trim()
                rxBuffer.delete(0, nl + 1)
                if (line.isNotEmpty()) lines.add(line)
            }
        }
        lines.forEach { onLineReceived(it) }
    }

    // ---- Send ------------------------------------------------------------------

    override suspend fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkBytes, bytes.size)
            writeChunk(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val g = gatt
        val ch = rxChar
        if (!ready || g == null || ch == null) throw MachineLinkException("Bluetooth link is not connected")
        while (writeStatus.tryReceive().isSuccess) { /* discard stale completions */ }
        val started = try {
            synchronized(gattLock) { rawWrite(g, ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
        } catch (e: SecurityException) {
            throw MachineLinkException("Bluetooth permission is missing", e)
        }
        if (!started) throw MachineLinkException("Bluetooth write was rejected")
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { writeStatus.receive() }
            ?: throw MachineLinkException("Bluetooth write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw MachineLinkException("Bluetooth write failed (status $status)")
    }

    private fun rawWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = bytes
            g.writeCharacteristic(ch)
        }

    /** Retries for up to ~1 s while the radio is busy with an ordinary write. */
    override fun writeRealtime(command: Byte) {
        try {
            realtimeExecutor.execute {
                repeat(REALTIME_ATTEMPTS) {
                    val g = gatt
                    val ch = rxChar
                    if (g == null || ch == null) return@execute
                    val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    val ok = try {
                        synchronized(gattLock) { rawWrite(g, ch, byteArrayOf(command), type) }
                    } catch (ignored: SecurityException) {
                        return@execute
                    }
                    if (ok) return@execute
                    try { Thread.sleep(10) } catch (ignored: InterruptedException) { return@execute }
                }
            }
        } catch (ignored: RejectedExecutionException) { /* already disconnected */ }
    }

    override fun disconnect() {
        ready = false
        try { gatt?.disconnect() } catch (ignored: SecurityException) {}
        try { gatt?.close() } catch (ignored: SecurityException) {}
        gatt = null
        rxChar = null
        realtimeExecutor.shutdown()
        onTransportClosed("Disconnected")
    }
}
