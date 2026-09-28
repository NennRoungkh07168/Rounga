@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.link.SessionState
import feather.model.Axis
import feather.model.Device
import feather.model.GcodeDialect
import feather.model.ProfileParams
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot

private class ToolTile(
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private enum class ToolDlg { STEPS, SQUARE, PROBE_Z, EDGE, REPORT_CHECK, REPORT_TIME, REPORT_GCODE, MATERIALS, DEVICE_QR, AUTOLEVEL }

/**
 * Everything the machine needs besides the drawing, grouped the way a job flows:
 * calibrate, position the workpiece, choose the tool, make and check the toolpath, drive the machine.
 * Live actions need a connection; the rest work offline.
 */
@Composable
internal fun ToolsScreen(
    state: FeatherUiState,
    vm: FeatherViewModel,
    openDialog: (Dlg) -> Unit,
    onConnect: () -> Unit,
    onSimulate: () -> Unit,
    onGoTab: (Tab) -> Unit,
    onEditTool: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by vm.session.state.collectAsState()
    var dlg by remember { mutableStateOf<ToolDlg?>(null) }
    val doScan = rememberQrScanner(vm) { vm.report(it) }
    val device = state.devices.activeDevice
    val live = session.connected
    val liveHint = if (live) "" else "Connect first"

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Machine tools", style = MaterialTheme.typography.titleLarge)
        PanelCard {
            Text(
                if (device == null) "No machine selected. Choose one in Devices."
                else if (live) "${device.name}: connected, ${session.machineState.ifBlank { "waiting for status" }}"
                else "${device.name}: not connected. Actions marked live need a connection.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ButtonRow {
                if (live) OutlinedButton(onClick = { vm.disconnectMachine() }) { Text("Disconnect") }
                else Button(onClick = onConnect, enabled = device != null && !session.connecting) {
                    Text(if (session.connecting) "Connecting..." else "Connect")
                }
            }
        }

        SectionHeader("Calibration")
        TileGrid(
            listOf(
                ToolTile("Steps/mm", "Axis calibration", device != null) { dlg = ToolDlg.STEPS },
                ToolTile("Squareness", "Compare the diagonals") { dlg = ToolDlg.SQUARE },
                ToolTile("Backlash test", "Adds a test pattern to the drawing") { vm.addBacklashPattern() },
                ToolTile("Screen scale", "Match on-screen mm to a ruler") { openDialog(Dlg.CALIBRATE) },
            ),
        )

        SectionHeader("Workpiece")
        TileGrid(
            listOf(
                ToolTile("Set origin", if (live) "Here becomes X0 Y0" else liveHint, live) { vm.setOriginHere(false) },
                ToolTile("Probe Z", if (live) "Touch plate, set Z" else liveHint, live) { dlg = ToolDlg.PROBE_Z },
                ToolTile("Edge finder", if (live) "Probe X or Y, then zero" else liveHint, live) { dlg = ToolDlg.EDGE },
                ToolTile(
                    "Stock dimensions",
                    state.stock?.let { "${fmtNum(it.widthMm)} x ${fmtNum(it.heightMm)} x ${fmtNum(it.thicknessMm)} mm" } ?: "Not set",
                ) { openDialog(Dlg.STOCK) },
                ToolTile(
                    "Auto-level",
                    when {
                        !live -> liveHint
                        device == null || !device.profile.hasAxis(Axis.Z) -> "This machine has no Z axis"
                        state.heightMap != null -> "${if (state.useHeightMap) "On" else "Off"}: height varies ${fmtNum(state.heightMap!!.maxZ - state.heightMap!!.minZ)} mm"
                        else -> "Probe a grid to correct an uneven bed"
                    },
                    live && device?.profile?.hasAxis(Axis.Z) == true,
                ) { dlg = ToolDlg.AUTOLEVEL },
            ),
        )

        SectionHeader("Tool")
        TileGrid(
            listOf(
                ToolTile("Tool library", "Every tool on every machine") { onGoTab(Tab.DEVICES) },
                ToolTile(
                    "Tool settings",
                    device?.activeTool?.name ?: "No tool",
                    device?.activeToolId != null,
                ) { if (device != null && device.activeToolId != null) onEditTool(device.id, device.activeToolId!!) },
                ToolTile("Feed rates and depth", "${fmtNum(state.settings.feedRateMmPerMin)} mm/min") { openDialog(Dlg.JOB) },
            ),
        )

        SectionHeader("Material")
        TileGrid(
            listOf(
                ToolTile("Material library", "Wood, acrylic, MDF and more") { dlg = ToolDlg.MATERIALS },
                ToolTile("Scan QR", "Load a material or machine") { doScan() },
                ToolTile("Share this machine", device?.let { "Show a QR for ${it.name}" } ?: "Choose a machine first", device != null) { dlg = ToolDlg.DEVICE_QR },
            ),
        )

        SectionHeader("Manufacturing")
        val hasShapes = state.shapes.isNotEmpty()
        TileGrid(
            listOf(
                ToolTile("Generate toolpath", "Build and preview G-code", hasShapes) { if (vm.analyzeJob() != null) dlg = ToolDlg.REPORT_GCODE },
                ToolTile("Simulate", "Watch the tool run", hasShapes) { if (vm.analyzeJob() != null) onSimulate() },
                ToolTile("Estimate time", "Run time and path length", hasShapes) { if (vm.analyzeJob() != null) dlg = ToolDlg.REPORT_TIME },
                ToolTile(
                    "Optimize path",
                    if (state.settings.optimizeTravel) "On: shortest travel order" else "Off: drawing order",
                ) { vm.updateSettings(state.settings.copy(optimizeTravel = !state.settings.optimizeTravel)) },
                ToolTile("Validate G-code", "Check it fits the machine", hasShapes) { if (vm.analyzeJob() != null) dlg = ToolDlg.REPORT_CHECK },
            ),
        )

        SectionHeader("Machine")
        TileGrid(
            listOf(
                ToolTile("Home", if (live) "Run the homing cycle" else liveHint, live) { vm.homeMachine() },
                ToolTile("Unlock", if (live) "Clear an alarm" else liveHint, live) { vm.unlockMachine() },
                ToolTile("Jog", "Move the axes") { onGoTab(Tab.MACHINE) },
                ToolTile("Reset", if (live) "Soft reset, stops motion" else liveHint, live) { vm.emergencyStop() },
                ToolTile("Status", statusText(session)) { onGoTab(Tab.MACHINE) },
                ToolTile("Console", "Send G-code, read replies") { onGoTab(Tab.MACHINE) },
            ),
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    when (dlg) {
        ToolDlg.STEPS -> if (device != null) StepsDialog(
            device = device,
            connected = live,
            onDismiss = { dlg = null },
            onSave = { axis, steps ->
                vm.updateDevice(device.copy(profile = ProfileParams.withValue(device.profile, "${axis.name}.steps", steps)))
            },
            onSend = { command -> vm.sendCommand(command) },
        )
        ToolDlg.SQUARE -> SquarenessDialog(device) { dlg = null }
        ToolDlg.PROBE_Z -> ProbeZDialog(
            onDismiss = { dlg = null },
            onProbe = { depth, plate, feed -> vm.probeZ(depth, plate, feed); dlg = null },
        )
        ToolDlg.EDGE -> EdgeDialog(
            onDismiss = { dlg = null },
            onProbe = { axis, distance, feed -> vm.probeEdge(axis, distance, feed) },
            onZero = { axis -> vm.zeroAxis(axis) },
        )
        ToolDlg.REPORT_CHECK -> ReportDialog(state, "Validate G-code", showGcode = false) { dlg = null }
        ToolDlg.REPORT_TIME -> ReportDialog(state, "Estimate", showGcode = false) { dlg = null }
        ToolDlg.REPORT_GCODE -> ReportDialog(state, "Toolpath", showGcode = true) { dlg = null }
        ToolDlg.MATERIALS -> MaterialLibraryDialog(vm) { dlg = null }
        ToolDlg.AUTOLEVEL -> AutoLevelDialog(vm, device) { dlg = null }
        ToolDlg.DEVICE_QR -> {
            val text = vm.deviceQrText()
            if (text == null) { dlg = null } else QrDialog(text, device?.name ?: "Machine") { dlg = null }
        }
        null -> Unit
    }
}

private fun statusText(s: SessionState): String {
    if (!s.connected) return "Not connected"
    val p = s.position
    val where = if (p == null) "" else "  X${fmtNum(Math.round(p.x * 100) / 100.0)} Y${fmtNum(Math.round(p.y * 100) / 100.0)}"
    return s.machineState.ifBlank { "Waiting" } + where
}

@Composable
private fun TileGrid(tiles: List<ToolTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { t -> Tile(t, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(t: ToolTile, modifier: Modifier) {
    Card(
        onClick = t.onClick,
        enabled = t.enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp).heightIn(min = 52.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall)
            Text(t.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

// ---- Dialogs ---------------------------------------------------------------------------------------

@Composable
private fun ReportDialog(state: FeatherUiState, title: String, showGcode: Boolean, onDismiss: () -> Unit) {
    val report = state.jobReport
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            ) {
                if (report == null) {
                    Text("Nothing to report yet. Draw something first.")
                } else {
                    ReportSummary(report)
                    if (showGcode) {
                        val lines = report.gcode.lines()
                        Text(
                            lines.take(40).joinToString("\n") + if (lines.size > 40) "\n... ${lines.size - 40} more lines" else "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StepsDialog(
    device: Device,
    connected: Boolean,
    onDismiss: () -> Unit,
    onSave: (Axis, Double) -> Unit,
    onSend: (String) -> Unit,
) {
    val axes = device.profile.axes.keys.toList()
    var axis by remember { mutableStateOf(axes.firstOrNull() ?: Axis.X) }
    var commanded by remember { mutableStateOf("100") }
    var measured by remember { mutableStateOf("") }
    val current = device.profile.axes[axis]?.stepsPerMm ?: 80.0
    val c = parseMm(commanded)?.takeIf { it > 0.0 }
    val m = parseMm(measured)?.takeIf { it > 0.0 }
    val corrected = if (c != null && m != null) current * c / m else null
    val grbl = device.controller.dialect == GcodeDialect.GRBL
    val setting = when (axis) { Axis.X -> 100; Axis.Y -> 101; Axis.Z -> 102; else -> null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Steps/mm calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Command a move of a known length, measure what the axis really travelled, and the correct steps/mm is worked out for you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    axes.forEach { a -> FilterChip(selected = axis == a, onClick = { axis = a }, label = { Text(a.name) }) }
                }
                Text("Current: ${fmtNum(current)} steps/mm")
                NumberField("Commanded distance (mm)", commanded, { commanded = it })
                NumberField("Measured distance (mm)", measured, { measured = it })
                if (corrected != null) {
                    Text("New value: ${fmtNum(Math.round(corrected * 1000) / 1000.0)} steps/mm", style = MaterialTheme.typography.titleSmall)
                    if (abs(corrected / current - 1.0) > 0.2) {
                        Text("That is a large correction. Re-check both measurements.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (grbl && connected && setting != null && corrected != null) {
                    TextButton(onClick = {
                        val v = Math.round(corrected * 1000) / 1000.0
                        onSave(axis, v)
                        onSend("\$$setting=${String.format(Locale.ROOT, "%.3f", v)}")
                        onDismiss()
                    }) { Text("Save and send") }
                }
                TextButton(enabled = corrected != null, onClick = {
                    corrected?.let { onSave(axis, Math.round(it * 1000) / 1000.0) }
                    onDismiss()
                }) { Text("Save to profile") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SquarenessDialog(device: Device?, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(device?.profile?.travelMm(Axis.X)?.coerceAtMost(200.0)?.let { fmtNum(it) } ?: "100") }
    var h by remember { mutableStateOf(device?.profile?.travelMm(Axis.Y)?.coerceAtMost(200.0)?.let { fmtNum(it) } ?: "100") }
    var d1 by remember { mutableStateOf("") }
    var d2 by remember { mutableStateOf("") }
    val pw = parseMm(w)?.takeIf { it > 0.0 }
    val ph = parseMm(h)?.takeIf { it > 0.0 }
    val p1 = parseMm(d1)?.takeIf { it > 0.0 }
    val p2 = parseMm(d2)?.takeIf { it > 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Squareness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Cut or draw a rectangle of the size below, then measure both corner-to-corner diagonals. " +
                        "Diagonal 1 runs from the origin corner to the opposite corner; diagonal 2 is the other one.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("Rectangle width X (mm)", w, { w = it })
                NumberField("Rectangle height Y (mm)", h, { h = it })
                NumberField("Diagonal 1 (mm)", d1, { d1 = it })
                NumberField("Diagonal 2 (mm)", d2, { d2 = it })
                if (pw != null && ph != null && p1 != null && p2 != null) {
                    val sin = (p2 * p2 - p1 * p1) / (4.0 * pw * ph)
                    if (abs(sin) >= 1.0) {
                        Text("Those numbers cannot come from that rectangle. Re-check the measurements.", color = MaterialTheme.colorScheme.error)
                    } else {
                        val deg = Math.toDegrees(asin(sin))
                        val per100 = 100.0 * Math.tan(Math.toRadians(deg))
                        val ideal = hypot(pw, ph)
                        Text("Ideal diagonal: ${String.format(Locale.ROOT, "%.2f", ideal)} mm")
                        Text(
                            "Out of square by ${String.format(Locale.ROOT, "%.3f", abs(deg))} degrees " +
                                "(${String.format(Locale.ROOT, "%.2f", abs(per100))} mm per 100 mm).",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (abs(deg) < 0.1) "That is square enough for most work."
                            else "Adjust the frame until the diagonals match, then repeat.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ProbeZDialog(onDismiss: () -> Unit, onProbe: (Double, Double, Double) -> Unit) {
    var depth by remember { mutableStateOf("20") }
    var plate by remember { mutableStateOf("0") }
    var feed by remember { mutableStateOf("50") }
    val d = parseMm(depth)?.takeIf { it > 0.0 && it <= 200.0 }
    val p = parseMm(plate)?.takeIf { it >= 0.0 }
    val f = parseMm(feed)?.takeIf { it > 0.0 && it <= 500.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Probe Z") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Clip the probe lead to the tool and put the plate under it. The machine moves down slowly until contact, " +
                        "then sets Z to the plate thickness. Keep a hand near Stop.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("Maximum travel down (mm)", depth, { depth = it })
                NumberField("Plate thickness (mm)", plate, { plate = it })
                NumberField("Probe feed (mm/min)", feed, { feed = it })
            }
        },
        confirmButton = { TextButton(enabled = d != null && p != null && f != null, onClick = { onProbe(d!!, p!!, f!!) }) { Text("Probe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EdgeDialog(onDismiss: () -> Unit, onProbe: (String, Double, Double) -> Unit, onZero: (String) -> Unit) {
    var axis by remember { mutableStateOf("X") }
    var positive by remember { mutableStateOf(false) }
    var distance by remember { mutableStateOf("20") }
    var feed by remember { mutableStateOf("60") }
    val d = parseMm(distance)?.takeIf { it > 0.0 && it <= 200.0 }
    val f = parseMm(feed)?.takeIf { it > 0.0 && it <= 500.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edge finder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Probe toward the edge of the stock until contact, then zero that axis at the contact point. " +
                        "Jog by half the tool diameter afterwards if you want the tool centre on the edge.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = axis == "X", onClick = { axis = "X" }, label = { Text("X") })
                    FilterChip(selected = axis == "Y", onClick = { axis = "Y" }, label = { Text("Y") })
                    FilterChip(selected = !positive, onClick = { positive = false }, label = { Text("Toward -") })
                    FilterChip(selected = positive, onClick = { positive = true }, label = { Text("Toward +") })
                }
                NumberField("Maximum travel (mm)", distance, { distance = it })
                NumberField("Probe feed (mm/min)", feed, { feed = it })
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = d != null && f != null, onClick = { onProbe(axis, if (positive) d!! else -d!!, f!!) }) { Text("Probe") }
                TextButton(onClick = { onZero(axis) }) { Text("Zero $axis here") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- Auto-level (multi-point Z probing) --------------------------------------------------------------

@Composable
private fun AutoLevelDialog(vm: FeatherViewModel, device: Device?, onDismiss: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val autofocus = state.autofocus
    var cols by remember { mutableStateOf(3) }
    var rows by remember { mutableStateOf(3) }
    var margin by remember { mutableStateOf("15") }
    var depth by remember { mutableStateOf("10") }
    var feed by remember { mutableStateOf("60") }
    var retract by remember { mutableStateOf("5") }

    val pMargin = parseMm(margin)?.takeIf { it >= 0.0 }
    val pDepth = parseMm(depth)?.takeIf { it in 0.5..100.0 }
    val pFeed = parseMm(feed)?.takeIf { it in 5.0..500.0 }
    val pRetract = parseMm(retract)?.takeIf { it in 0.5..50.0 }
    val ready = pMargin != null && pDepth != null && pFeed != null && pRetract != null && !autofocus.running

    AlertDialog(
        onDismissRequest = { if (!autofocus.running) onDismiss() },
        title = { Text("Auto-level") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    (device?.let { "${it.name}: " } ?: "") +
                        "Probes a grid across the bed and corrects the toolpath's Z for an uneven bed or " +
                        "warped material — the same idea as CNC \"auto-level\" tools. Needs open clearance " +
                        "above the material and a probe wired to the controller.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Grid size", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 4, 5).forEach { n ->
                        FilterChip(selected = cols == n && rows == n, onClick = { cols = n; rows = n }, label = { Text("${n}x$n") }, enabled = !autofocus.running)
                    }
                }
                NumberField("Margin from the edges (mm)", margin, { margin = it }, enabled = !autofocus.running)
                NumberField("Max probe depth (mm)", depth, { depth = it }, enabled = !autofocus.running)
                NumberField("Probe feed (mm/min)", feed, { feed = it }, enabled = !autofocus.running)
                NumberField("Retract height between points (mm)", retract, { retract = it }, enabled = !autofocus.running)

                if (autofocus.running) {
                    LinearProgressIndicator(
                        progress = { if (autofocus.totalCount == 0) 0f else autofocus.doneCount.toFloat() / autofocus.totalCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (autofocus.message.isNotBlank()) Text(autofocus.message, style = MaterialTheme.typography.bodySmall)

                state.heightMap?.let { map ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Apply to G-code", modifier = Modifier.weight(1f))
                        Switch(checked = state.useHeightMap, onCheckedChange = { vm.setUseHeightMap(it) })
                    }
                    Text(
                        "Height varies ${fmtNum(map.maxZ - map.minZ)} mm across the probed points.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.clearHeightMap() }, enabled = !autofocus.running) { Text("Clear height map") }
                }
            }
        },
        confirmButton = {
            if (autofocus.running) {
                TextButton(onClick = { vm.cancelAutofocus() }) { Text("Cancel probing") }
            } else {
                TextButton(
                    enabled = ready,
                    onClick = { vm.runAutofocusMesh(cols, rows, pMargin!!, pDepth!!, pFeed!!, pRetract!!) },
                ) { Text("Start") }
            }
        },
        dismissButton = { TextButton(enabled = !autofocus.running, onClick = onDismiss) { Text("Close") } },
    )
}
