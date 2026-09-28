package feather.link

import feather.core.GcodeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class MachinePosition(val x: Double, val y: Double, val z: Double, val isWork: Boolean)

data class SessionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val label: String = "",
    /** GRBL state word: Idle, Run, Hold, Alarm, Home ... blank until the first report. */
    val machineState: String = "",
    val position: MachinePosition? = null,
    /** The most recent probe trigger position (GRBL `[PRB:...]`), and whether it made contact. Cleared by [MachineSession.clearProbeResult]. */
    val lastProbe: MachinePosition? = null,
    val lastProbeContact: Boolean = true,
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
)

/**
 * One live connection to the selected machine, kept open between jobs so that
 * Home / Jog / Set origin / Probe and the console share it with Start. Wi-Fi
 * bridges and BLE UARTs both accept a single client, so the job runner reuses
 * this link instead of opening a second one.
 */
class MachineSession(private val scope: CoroutineScope) {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val POLL_MS = 250L
        const val SILENCE_LIMIT_MS = 5_000L
        const val MAX_LOG = 200
    }

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile var link: MachineLink? = null
        private set

    /** Set by the job runner while it owns the link, so manual commands cannot interleave with a job. */
    @Volatile var jobActive: Boolean = false

    private val sendLock = Mutex()
    private var pollJob: Job? = null
    @Volatile private var lastReportAt = 0L

    fun log(line: String) {
        _state.update { s -> s.copy(log = (s.log + line).takeLast(MAX_LOG)) }
    }

    fun clearLog() { _state.update { it.copy(log = emptyList()) } }

    /** Opens [newLink]. Returns null on success, or a message fit for the operator. */
    suspend fun connect(newLink: MachineLink, label: String): String? {
        disconnect()
        _state.update { it.copy(connecting = true, label = label, machineState = "", position = null) }
        newLink.lineListener = { line -> onLine(line) }
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { newLink.connect() }
            link = newLink
            lastReportAt = System.currentTimeMillis()
            _state.update { it.copy(connected = true, connecting = false) }
            log("Connected to $label")
            startPolling(newLink)
            null
        } catch (e: MachineLinkException) {
            failConnect(newLink, e.message ?: "Could not connect")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            failConnect(newLink, "Timed out connecting to the machine")
        }
    }

    private fun failConnect(l: MachineLink, message: String): String {
        try { l.disconnect() } catch (ignored: Exception) {}
        _state.update { it.copy(connecting = false, connected = false) }
        log("! $message")
        return message
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        val l = link
        link = null
        if (l != null) {
            try { l.disconnect() } catch (ignored: Exception) {}
            log("Disconnected")
        }
        _state.update { it.copy(connected = false, connecting = false, machineState = "", position = null, busy = false) }
    }

    /** Streams [gcode] and waits for every "ok". Returns null on success, else the machine's or link's message. */
    suspend fun send(gcode: String): String? {
        val l = link ?: return "Not connected to a machine"
        if (jobActive) return "A job is running. Pause or stop it first."
        return sendLock.withLock {
            _state.update { it.copy(busy = true) }
            try {
                for (line in GcodeText.prepareForStreaming(gcode)) log("> $line")
                l.sendGcode(gcode) { _, _ -> }
                null
            } catch (e: JobStoppedException) {
                "Stopped"
            } catch (e: MachineLinkException) {
                log("! ${e.message}")
                e.message ?: "Machine error"
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Emergency stop / soft reset (0x18). Never waits behind the send queue. */
    fun reset() {
        link?.stop()
        log("> [reset]")
    }

    fun feedHold() { link?.pause(); log("> [hold]") }
    fun cycleResume() { link?.resume(); log("> [resume]") }

    private fun startPolling(l: MachineLink) {
        pollJob = scope.launch {
            while (true) {
                l.requestStatus()
                delay(POLL_MS)
                val silent = System.currentTimeMillis() - lastReportAt
                if (silent > SILENCE_LIMIT_MS && _state.value.connected) {
                    log("! The machine stopped reporting status. Check power and range, then reconnect.")
                    _state.update { it.copy(connected = false, machineState = "No response") }
                    return@launch
                }
            }
        }
    }

    private fun onLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        if (text.startsWith("<")) {
            lastReportAt = System.currentTimeMillis()
            parseStatus(text)
        } else if (text.startsWith("[PRB:")) {
            parseProbe(text)
            log("< $text")
        } else {
            log("< $text")
        }
    }

    /** `[PRB:1.000,2.000,-3.500:1]` -> the probe's contact position; the trailing `:1`/`:0` is whether it actually triggered. */
    private fun parseProbe(text: String) {
        val body = text.removePrefix("[PRB:").removeSuffix("]")
        val coordPart = body.substringBefore(':')
        val contactPart = body.substringAfter(':', "1")
        val nums = coordPart.split(',').mapNotNull { it.toDoubleOrNull() }
        if (nums.size < 2) return
        val pos = MachinePosition(nums[0], nums[1], nums.getOrElse(2) { 0.0 }, isWork = false)
        _state.update { it.copy(lastProbe = pos, lastProbeContact = contactPart.trim() != "0") }
    }

    /** Clears the last probe result before starting a new probe move, so a stale one can't be mistaken for the new one. */
    fun clearProbeResult() { _state.update { it.copy(lastProbe = null) } }

    /** `<Idle|MPos:1.000,2.000,0.000|FS:0,0>` -> state + position. Unknown formats are ignored. */
    private fun parseStatus(text: String) {
        val body = text.removePrefix("<").removeSuffix(">")
        val parts = body.split('|')
        if (parts.isEmpty()) return
        val stateWord = parts[0].substringBefore(':')
        var pos: MachinePosition? = null
        for (part in parts) {
            val isMachine = part.startsWith("MPos:")
            val isWork = part.startsWith("WPos:")
            if (!isMachine && !isWork) continue
            val nums = part.substringAfter(':').split(',').mapNotNull { it.toDoubleOrNull() }
            if (nums.size >= 2) pos = MachinePosition(nums[0], nums[1], nums.getOrElse(2) { 0.0 }, isWork)
        }
        _state.update { s -> s.copy(machineState = stateWord, position = pos ?: s.position) }
    }
}
