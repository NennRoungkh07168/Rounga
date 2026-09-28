@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.link.JobState
import feather.link.LinkMode
import feather.link.MachinePosition
import feather.link.SessionState
import feather.model.Axis
import java.util.Locale

@Composable
internal fun MachineScreen(
    state: FeatherUiState,
    vm: FeatherViewModel,
    wide: Boolean,
    onFindMachine: () -> Unit,
    onConnect: () -> Unit,
    files: FileActions,
    modifier: Modifier = Modifier,
) {
    val session by vm.session.state.collectAsState()

    if (wide) {
        Row(modifier = modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionCard(state, vm, session, onFindMachine, onConnect)
                PositionCard(session)
                JogCard(state, vm, session)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JobCard(state, vm, session, files)
                ConsoleCard(vm, session, Modifier.height(280.dp))
            }
        }
    } else {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionCard(state, vm, session, onFindMachine, onConnect)
            PositionCard(session)
            JobCard(state, vm, session, files)
            JogCard(state, vm, session)
            ConsoleCard(vm, session, Modifier.height(220.dp))
        }
    }
}

// ---- Connection ---------------------------------------------------------------------------------------

@Composable
private fun ConnectionCard(
    state: FeatherUiState,
    vm: FeatherViewModel,
    session: SessionState,
    onFindMachine: () -> Unit,
    onConnect: () -> Unit,
) {
    val running = state.jobState == JobState.RUNNING || state.jobState == JobState.PAUSED
    val device = state.devices.activeDevice
    var picking by remember { mutableStateOf(false) }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device?.name ?: "No machine", style = MaterialTheme.typography.titleMedium)
                Text(
                    device?.let { "${it.activeTool?.name ?: "no tool"}, ${it.controller.displayName}" } ?: "Create one in Devices",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                OutlinedButton(onClick = { picking = true }, enabled = !running && state.devices.devices.size > 1) { Text("Switch") }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    state.devices.devices.forEach { d ->
                        DropdownMenuItem(text = { Text(d.name) }, onClick = { picking = false; vm.selectDevice(d.id) })
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(LinkMode.values().toList()) { mode ->
                FilterChip(
                    selected = state.linkMode == mode,
                    onClick = { vm.setLinkMode(mode) },
                    enabled = !running && !session.connected,
                    label = { Text(linkLabel(mode)) },
                )
            }
        }
        when (state.linkMode) {
            LinkMode.WIFI -> {
                var portText by remember(state.wifiPort) { mutableStateOf(state.wifiPort.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.wifiHost,
                        onValueChange = { vm.setWifiHost(it) },
                        label = { Text("Controller IP") },
                        singleLine = true,
                        enabled = !running && !session.connected,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { t ->
                            portText = t.filter { c -> c.isDigit() }.take(5)
                            portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { vm.setWifiPort(it) }
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        enabled = !running && !session.connected,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
            LinkMode.BLUETOOTH -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (state.bleAddress.isBlank()) "No machine selected" else "${state.bleName ?: "Machine"} (${state.bleAddress})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onFindMachine, enabled = !running && !session.connected) { Text("Find machine") }
            }
            LinkMode.SD_CARD -> Text(
                "Exports a .gcode file to a place you choose. Copy it to the machine's SD card. Live control needs Bluetooth or Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.linkMode != LinkMode.SD_CARD) {
            ButtonRow {
                if (session.connected) {
                    OutlinedButton(onClick = { vm.disconnectMachine() }, enabled = !running) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect, enabled = !session.connecting && !running) {
                        Text(if (session.connecting) "Connecting..." else "Connect")
                    }
                }
            }
        }
    }
}

private fun linkLabel(m: LinkMode) = when (m) {
    LinkMode.BLUETOOTH -> "Bluetooth"
    LinkMode.WIFI -> "Wi-Fi"
    LinkMode.SD_CARD -> "File / SD"
}

// ---- Position -----------------------------------------------------------------------------------------

@Composable
private fun PositionCard(session: SessionState) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (session.connected) session.machineState.ifBlank { "Waiting for status" } else session.machineState.ifBlank { "Not connected" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                color = if (session.machineState.startsWith("Alarm")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                session.position?.let { if (it.isWork) "Work position" else "Machine position" } ?: "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val p = session.position
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Axis.values().take(3).forEachIndexed { i, axis ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(axis.name, style = MaterialTheme.typography.labelMedium)
                    Text(coord(p, i), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun coord(p: MachinePosition?, index: Int): String {
    if (p == null) return "--"
    val v = when (index) { 0 -> p.x; 1 -> p.y; else -> p.z }
    return String.format(Locale.ROOT, "%.3f", v)
}

// ---- Jog ----------------------------------------------------------------------------------------------

@Composable
private fun JogCard(state: FeatherUiState, vm: FeatherViewModel, session: SessionState) {
    val enabled = session.connected && !session.busy && state.jobState != JobState.RUNNING && state.jobState != JobState.PAUSED
    var step by remember { mutableStateOf(1.0) }
    val maxFeed = state.devices.activeDevice?.profile?.maxFeedMmPerMin(Axis.X)?.takeIf { it < 100_000.0 } ?: 3000.0
    var feedText by remember(maxFeed) { mutableStateOf(fmtNum(minOf(1000.0, maxFeed))) }
    val feed = parseMm(feedText)?.takeIf { it > 0.0 }?.coerceAtMost(maxFeed) ?: 0.0
    val hasZ = state.devices.activeDevice?.profile?.hasAxis(Axis.Z) ?: true
    val canJog = enabled && feed > 0.0

    PanelCard {
        Text("Jog", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.1, 1.0, 10.0, 50.0).forEach { v ->
                FilterChip(selected = step == v, onClick = { step = v }, label = { Text("${fmtNum(v)} mm") })
            }
        }
        NumberField("Feed (mm/min)", feedText, { feedText = it })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.jog(0.0, step, 0.0, feed) }, enabled = canJog) { Text("Y+") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.jog(-step, 0.0, 0.0, feed) }, enabled = canJog) { Text("X-") }
                    Button(onClick = { vm.jog(step, 0.0, 0.0, feed) }, enabled = canJog) { Text("X+") }
                }
                Button(onClick = { vm.jog(0.0, -step, 0.0, feed) }, enabled = canJog) { Text("Y-") }
            }
            if (hasZ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.jog(0.0, 0.0, step, feed) }, enabled = canJog) { Text("Z+") }
                    Button(onClick = { vm.jog(0.0, 0.0, -step, feed) }, enabled = canJog) { Text("Z-") }
                }
            }
        }
        ButtonRow {
            OutlinedButton(onClick = { vm.homeMachine() }, enabled = enabled) { Text("Home") }
            OutlinedButton(onClick = { vm.unlockMachine() }, enabled = enabled) { Text("Unlock") }
            OutlinedButton(onClick = { vm.setOriginHere(false) }, enabled = enabled) { Text("Set origin XY") }
            if (hasZ) OutlinedButton(onClick = { vm.sendCommand(feather.model.MachineCommands.setZeroZ(state.devices.activeDevice?.controller?.dialect ?: feather.model.GcodeDialect.GRBL)) }, enabled = enabled) { Text("Set Z0") }
            OutlinedButton(onClick = { vm.goToOrigin() }, enabled = enabled) { Text("Go to origin") }
        }
        if (!session.connected) Text("Connect to jog the machine.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Job ----------------------------------------------------------------------------------------------

@Composable
private fun JobCard(state: FeatherUiState, vm: FeatherViewModel, session: SessionState, files: FileActions) {
    val running = state.jobState == JobState.RUNNING || state.jobState == JobState.PAUSED
    PanelCard {
        Text("Job", style = MaterialTheme.typography.titleSmall)
        ButtonRow {
            Button(
                enabled = !running,
                onClick = {
                    val target = vm.currentLiveTarget()
                    if (target == null) files.exportGcode() else vm.start(target)
                },
            ) { Text(if (state.linkMode == LinkMode.SD_CARD) "Export" else "Start") }
            OutlinedButton(
                enabled = running && state.linkMode != LinkMode.SD_CARD,
                onClick = { if (state.jobState == JobState.PAUSED) vm.resume() else vm.pause() },
            ) { Text(if (state.jobState == JobState.PAUSED) "Resume" else "Pause") }
            Button(
                enabled = running || session.connected,
                onClick = { vm.emergencyStop() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("STOP") }
        }
        Text("${state.jobState.name}  ${state.statusMessage}", style = MaterialTheme.typography.bodySmall)
        val (sent, total) = state.progress
        if (total > 0) {
            LinearProgressIndicator(progress = { sent.toFloat() / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("$sent / $total lines", style = MaterialTheme.typography.bodySmall)
        }
        if (session.connected) {
            Text("STOP resets the controller. If it then reports an alarm, use Unlock.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---- Console ------------------------------------------------------------------------------------------

@Composable
private fun ConsoleCard(vm: FeatherViewModel, session: SessionState, modifier: Modifier) {
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(session.log.size) {
        if (session.log.isNotEmpty()) listState.animateScrollToItem(session.log.size - 1)
    }
    fun send() {
        val text = command.trim()
        if (text.isEmpty()) return
        vm.sendCommand(text)
        command = ""
    }
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Console", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.session.clearLog() }, enabled = session.log.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(session.log) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (line.startsWith("!")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                singleLine = true,
                placeholder = { Text("G-code, e.g. G0 X10") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { send() }, enabled = session.connected && command.isNotBlank()) { Text("Send") }
        }
    }
}
