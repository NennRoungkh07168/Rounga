package feather.link

import feather.core.GcodeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream

/** A transport failure with a message that is fit to show the operator. */
class MachineLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown out of [MachineLink.sendGcode] when the operator pressed Stop. */
class JobStoppedException : Exception("Stopped by operator")

/**
 * Common contract so the UI (Start / Pause / Stop, link-mode selector) never
 * needs to know whether it talks BLE, Wi-Fi, or writes a file for an SD card.
 *
 * Suspend functions replace the earlier callback API: errors are exceptions,
 * completion is "the call returned", and cancelling the coroutine cancels the job.
 */
interface MachineLink {
    /** Open the transport. Throws [MachineLinkException]. */
    suspend fun connect()

    /** Stream [gcode] with ack flow control. Returns when finished; throws
     *  [MachineLinkException] on machine/transport errors, [JobStoppedException] after [stop]. */
    suspend fun sendGcode(gcode: String, onProgress: (sent: Int, total: Int) -> Unit)

    /** Emergency stop. Thread-safe, non-blocking, bypasses the send queue. */
    fun stop()

    /** Feed hold (GRBL `!`). */
    fun pause() {}

    /** Cycle resume (GRBL `~`). */
    fun resume() {}

    /** Ask the controller for a status report (GRBL `?`). The reply arrives through [lineListener]. */
    fun requestStatus() {}

    /** Every text line the controller sends back (ok, error, status report, messages). May be called on any thread. */
    var lineListener: ((String) -> Unit)?

    fun disconnect()
}

/**
 * GRBL-style streaming shared by every live transport: send one line, wait
 * for `ok`, send the next. That is what stops the controller's small serial
 * buffer from overflowing on a long job.
 *
 * Subclasses provide the raw byte pipe ([writeLine], [writeRealtime]) and feed
 * received text lines into [onLineReceived]. Because this logic lives in one
 * place it is unit-tested once (see GrblStreamingLinkTest) rather than three times.
 */
abstract class GrblStreamingLink : MachineLink {

    private companion object {
        const val ACK_POLL_MS = 500L
        const val ACK_TIMEOUT_MS = 120_000L // a full planner buffer on a long move can delay "ok" a lot
        const val STOP_SENTINEL = "\u0000STOP"
        const val RESET: Byte = 0x18
        const val HOLD: Byte = 0x21   // '!'
        const val RESUME: Byte = 0x7E // '~'
        const val STATUS: Byte = 0x3F // '?'
    }

    @Volatile final override var lineListener: ((String) -> Unit)? = null

    private val incoming = Channel<String>(Channel.UNLIMITED)
    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    /** Send [line] followed by "\n". May suspend; must throw [MachineLinkException] on failure. */
    protected abstract suspend fun writeLine(line: String)

    /** Send one real-time byte immediately, never queued behind [writeLine]. Must not throw or block. */
    protected abstract fun writeRealtime(command: Byte)

    protected fun onLineReceived(line: String) {
        lineListener?.invoke(line)
        // Status reports ("<Idle|MPos:...>") never carry an ack; keep them out of the ack queue so an idle,
        // polled session cannot grow it without bound.
        if (!line.startsWith("<")) incoming.trySend(line)
    }

    protected fun onTransportClosed(reason: String) {
        incoming.close(MachineLinkException(reason))
    }

    final override suspend fun sendGcode(gcode: String, onProgress: (sent: Int, total: Int) -> Unit) {
        val lines = GcodeText.prepareForStreaming(gcode)
        stopRequested = false
        paused = false
        while (incoming.tryReceive().isSuccess) { /* drop stale greeting / status lines */ }
        onProgress(0, lines.size)
        for ((index, line) in lines.withIndex()) {
            if (stopRequested) throw JobStoppedException()
            writeLine(line)
            awaitAck(line)
            onProgress(index + 1, lines.size)
        }
    }

    private suspend fun awaitAck(sentLine: String) {
        var idleMs = 0L
        while (true) {
            val msg = withTimeoutOrNull(ACK_POLL_MS) { incoming.receive() }
            if (msg == null) {
                if (!paused) idleMs += ACK_POLL_MS // a held machine legitimately stops acking
                if (idleMs >= ACK_TIMEOUT_MS) throw MachineLinkException("The machine stopped responding")
                continue
            }
            idleMs = 0L
            val text = msg.trim()
            when {
                msg == STOP_SENTINEL -> throw JobStoppedException()
                text.equals("ok", ignoreCase = true) -> return
                text.startsWith("error", ignoreCase = true) ->
                    throw MachineLinkException("Machine rejected \"$sentLine\": $text")
                text.startsWith("ALARM", ignoreCase = true) ->
                    throw MachineLinkException("Machine alarm: $text (send \$X to unlock)")
                else -> Unit // greeting, status report, [MSG:...] feedback
            }
        }
    }

    final override fun stop() {
        runCatching { writeRealtime(RESET) } // first: lowest possible latency
        stopRequested = true
        paused = false
        incoming.trySend(STOP_SENTINEL) // wakes a coroutine blocked waiting for "ok"
    }

    final override fun pause() {
        paused = true
        runCatching { writeRealtime(HOLD) }
    }

    final override fun resume() {
        paused = false
        runCatching { writeRealtime(RESUME) }
    }

    final override fun requestStatus() {
        runCatching { writeRealtime(STATUS) }
    }
}

/**
 * "SD card" transport: writes a standard .gcode file (to wherever the user
 * chose via the system file picker) for the machine's own card reader.
 */
class FileExportLink(private val openOutput: () -> OutputStream) : MachineLink {
    override var lineListener: ((String) -> Unit)? = null

    override suspend fun connect() {}

    override suspend fun sendGcode(gcode: String, onProgress: (sent: Int, total: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                openOutput().use { it.write(gcode.toByteArray(Charsets.US_ASCII)) }
            } catch (e: IOException) {
                throw MachineLinkException("Could not write the file: ${e.message}", e)
            }
        }
        onProgress(1, 1)
    }

    override fun stop() { /* no live session to interrupt */ }
    override fun disconnect() {}
}
