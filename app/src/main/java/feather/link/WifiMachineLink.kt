package feather.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Wi-Fi transport for network-enabled controllers (e.g. ESP32 GRBL bridges)
 * exposing a raw TCP G-code port.
 *
 *  - Connect has a timeout (no more hanging for minutes on a wrong IP).
 *  - A dedicated reader thread feeds lines to the shared ack logic.
 *  - Real-time bytes (stop / hold / resume) are written from a separate
 *    executor: Android forbids socket writes on the main thread, and the
 *    stop button must never wait behind a line that is still being acked.
 */
class WifiMachineLink(
    private val host: String,
    private val port: Int = 23,
    private val connectTimeoutMs: Int = 5_000,
) : GrblStreamingLink() {

    private val writeLock = Any()
    private val realtimeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket = s
                output = s.getOutputStream()
                thread(name = "feather-wifi-rx", isDaemon = true) { readLoop(s) }
            } catch (e: IOException) {
                throw MachineLinkException("Could not reach $host:$port (${e.message})", e)
            } catch (e: IllegalArgumentException) {
                throw MachineLinkException("Invalid address $host:$port", e)
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
            while (true) {
                val line = reader.readLine() ?: break
                onLineReceived(line)
            }
            onTransportClosed("Connection closed by the machine")
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
