package feather.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Classic Bluetooth (SPP / RFCOMM) transport for HC-05 / HC-06 serial modules.
 *
 * [BleMachineLink] only works with BLE modules that expose the Nordic UART service. An HC-05 is a
 * *classic* Bluetooth module: it never shows up in a BLE scan and has no GATT service, so it needs
 * this link instead. The module must be paired once in Android's Bluetooth settings (PIN 1234 or 0000).
 *
 * Structure mirrors [WifiMachineLink]: a reader thread feeds lines to the shared GRBL ack logic, and
 * real-time bytes (stop / hold / resume / status) are written from a separate executor so they never
 * wait behind a line that is still being acknowledged.
 */
@SuppressLint("MissingPermission")
class ClassicBluetoothMachineLink(
    private val device: BluetoothDevice,
) : GrblStreamingLink() {

    companion object {
        /** The standard Serial Port Profile UUID every HC-05 / HC-06 listens on. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** True for a classic-Bluetooth device (HC-05 and friends); false for BLE-only devices. */
        @SuppressLint("MissingPermission")
        fun isClassic(device: BluetoothDevice): Boolean = try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> true
                // A device the phone has not classified yet: trust the usual HC-xx module naming.
                BluetoothDevice.DEVICE_TYPE_UNKNOWN -> device.name?.startsWith("HC-", ignoreCase = true) == true
                else -> false // LE-only or dual-mode: use the BLE link
            }
        } catch (e: SecurityException) {
            false
        }

        /** Devices already paired in Android's Bluetooth settings that need this link. */
        @SuppressLint("MissingPermission")
        fun pairedClassicDevices(adapter: BluetoothAdapter?): List<BluetoothDevice> = try {
            adapter?.bondedDevices?.filter { isClassic(it) } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private val writeLock = Any()
    private val realtimeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    private fun label(): String {
        val name = try { device.name } catch (e: SecurityException) { null }
        return name ?: device.address
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            var pending: BluetoothSocket? = null
            try {
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                pending = sock
                sock.connect() // blocks until connected, or throws
                socket = sock
                output = sock.outputStream
                thread(name = "feather-spp-rx", isDaemon = true) { readLoop(sock) }
            } catch (e: IOException) {
                try { pending?.close() } catch (ignored: IOException) {}
                throw MachineLinkException(
                    "Could not connect to ${label()} (${e.message}). Check that it is paired in Android Bluetooth " +
                        "settings, powered, and not already connected to another app or computer.", e
                )
            } catch (e: SecurityException) {
                try { pending?.close() } catch (ignored: IOException) {}
                throw MachineLinkException("Bluetooth permission has not been granted", e)
            }
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val reader = s.inputStream.bufferedReader(Charsets.US_ASCII)
            while (true) {
                val line = reader.readLine() ?: break
                onLineReceived(line)
            }
            onTransportClosed("Connection closed by the module")
        } catch (e: IOException) {
            onTransportClosed("Connection lost (${e.message})")
        }
    }

    override suspend fun writeLine(line: String) {
        withContext(Dispatchers.IO) { writeBytes((line + "\n").toByteArray(Charsets.US_ASCII)) }
    }

    override fun writeRealtime(command: Byte) {
        try {
            realtimeExecutor.execute {
                try { writeBytes(byteArrayOf(command)) } catch (ignored: MachineLinkException) { /* link already dead */ }
            }
        } catch (ignored: RejectedExecutionException) { /* already disconnected */ }
    }

    private fun writeBytes(bytes: ByteArray) {
        val out = output ?: throw MachineLinkException("Not connected")
        try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: IOException) {
            throw MachineLinkException("Connection lost (${e.message})", e)
        }
    }

    override fun disconnect() {
        try { socket?.close() } catch (ignored: IOException) {}
        socket = null
        output = null
        realtimeExecutor.shutdown()
        onTransportClosed("Disconnected")
    }
}
