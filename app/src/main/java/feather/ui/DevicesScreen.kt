@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.model.Axis
import feather.model.ConnectionType
import feather.model.Controller
import feather.model.Device
import feather.model.MachineType
import feather.model.ParamChange
import feather.model.Presets
import feather.model.ProfileParams
import feather.model.ToolHead
import feather.model.ToolParams
import java.util.UUID

private fun connectionLabel(c: ConnectionType) = when (c) {
    ConnectionType.BLE -> "Bluetooth"
    ConnectionType.WIFI -> "Wi-Fi"
    ConnectionType.USB -> "USB"
    ConnectionType.SD -> "File / SD"
}

private fun sizeText(d: Device): String {
    val p = d.profile
    val z = if (p.hasAxis(Axis.Z)) " x ${fmtNum(p.travelMm(Axis.Z))}" else ""
    return "${fmtNum(p.travelMm(Axis.X))} x ${fmtNum(p.travelMm(Axis.Y))}$z mm"
}

// ---- My Devices -----------------------------------------------------------------------------

@Composable
internal fun DevicesScreen(
    state: FeatherUiState,
    vm: FeatherViewModel,
    onEditProfile: (String) -> Unit,
    onEditTool: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var addToolFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Device?>(null) }
    var qrFor by remember { mutableStateOf<String?>(null) }

    val devices = state.devices.devices.sortedByDescending { it.favorite }
    val activeId = state.devices.activeDeviceId

    Column(modifier = modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val doScan = rememberQrScanner(vm) { vm.report(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = doScan) { Text("Scan QR") }
            Button(onClick = { creating = true }) { Text("+ Create machine") }
        }
        if (devices.isEmpty()) {
            Text("No machines yet. Create one to set its size, controller and tools.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(devices, key = { it.id }) { d ->
                DeviceCard(
                    device = d,
                    active = d.id == activeId,
                    onUse = { vm.selectDevice(d.id) },
                    onStar = { vm.toggleFavorite(d.id) },
                    onProfile = { onEditProfile(d.id) },
                    onEditTool = { toolId -> onEditTool(d.id, toolId) },
                    onPickTool = { toolId -> vm.setActiveTool(d.id, toolId) },
                    onAddTool = { addToolFor = d.id },
                    onDelete = { deleting = d },
                    onShareQr = { qrFor = d.id },
                )
            }
        }
    }

    if (creating) {
        NewDeviceDialog(
            onDismiss = { creating = false },
            onCreate = { vm.addDevice(it); creating = false },
        )
    }
    addToolFor?.let { deviceId ->
        val device = vm.deviceById(deviceId)
        if (device == null) addToolFor = null else AddToolDialog(
            onDismiss = { addToolFor = null },
            onAdd = { family ->
                val id = "tool-" + UUID.randomUUID().toString().take(8)
                val n = device.tools.count { ToolParams.familyName(it) == family } + 1
                vm.saveTool(deviceId, ToolParams.newOfFamily(family, id, "$family $n"))
                addToolFor = null
            },
        )
    }
    qrFor?.let { deviceId ->
        val device = vm.deviceById(deviceId)
        val text = device?.let { vm.deviceQrText(deviceId) }
        if (device == null || text == null) { qrFor = null } else QrDialog(text, device.name) { qrFor = null }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text("Its profile and tools are removed from this phone. Drawings are not affected.") },
            confirmButton = { TextButton(onClick = { vm.removeDevice(d.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    active: Boolean,
    onUse: () -> Unit,
    onStar: () -> Unit,
    onProfile: () -> Unit,
    onEditTool: (String) -> Unit,
    onPickTool: (String) -> Unit,
    onAddTool: () -> Unit,
    onDelete: () -> Unit,
    onShareQr: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onStar) {
                    Icon(
                        imageVector = if (device.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (device.favorite) "Unpin" else "Pin to top",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Presets.controllerSummary(device.controller)}, ${sizeText(device)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (active) Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "${connectionLabel(device.connection)}: " + when {
                    device.connection == ConnectionType.SD -> "exports a .gcode file"
                    device.address.isBlank() -> "not paired yet (use Machine > Find machine)"
                    else -> device.address
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Tools (tap one to make it active)", style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(device.tools, key = { it.id }) { t ->
                    FilterChip(selected = t.id == device.activeToolId, onClick = { onPickTool(t.id) }, label = { Text(t.name) })
                }
            }
            ButtonRow {
                if (!active) Button(onClick = onUse) { Text("Use") }
                OutlinedButton(onClick = onProfile) { Text("Profile") }
                OutlinedButton(
                    onClick = { device.activeToolId?.let(onEditTool) },
                    enabled = device.activeToolId != null,
                ) { Text("Tool settings") }
                OutlinedButton(onClick = onAddTool) { Text("+ Tool") }
                OutlinedButton(onClick = onShareQr) { Text("Share QR") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NewDeviceDialog(onDismiss: () -> Unit, onCreate: (Device) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MachineType.CNC_ROUTER) }
    var controller by remember { mutableStateOf(Controller.ARDUINO_NANO_GRBL) }
    var connection by remember { mutableStateOf(ConnectionType.BLE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create machine") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("Machine type", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(MachineType.values().toList()) { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.displayName) })
                    }
                }
                Text("Controller", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Controller.values().toList()) { c ->
                        FilterChip(selected = controller == c, onClick = { controller = c }, label = { Text(Presets.controllerSummary(c)) })
                    }
                }
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ConnectionType.BLE, ConnectionType.WIFI, ConnectionType.SD).forEach { c ->
                        FilterChip(selected = connection == c, onClick = { connection = c }, label = { Text(connectionLabel(c)) })
                    }
                }
                Text(
                    "Starts with sensible limits for the type. Adjust travel, feed rates and steps/mm in the machine's Profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(Presets.blank(type, name.trim().ifBlank { type.displayName }, controller, connection)) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddToolDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tool") },
        text = {
            Column {
                ToolParams.families.forEach { f -> TextButton(onClick = { onAdd(f) }) { Text(f) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Shared "review what will be copied" list -------------------------------------------------

/**
 * Every parameter that Copy would write, one row each, with the target's current value beside the new one.
 * Nothing is copied that is not ticked here.
 */
@Composable
private fun ReviewList(changes: List<ParamChange>, selected: MutableList<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                selected.clear(); selected.addAll(changes.filter { it.differs }.map { it.param.key })
            }) { Text("Changed only") }
            TextButton(onClick = { selected.clear(); selected.addAll(changes.map { it.param.key }) }) { Text("All") }
            TextButton(onClick = { selected.clear() }) { Text("None") }
        }
        changes.forEach { c ->
            val key = c.param.key
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = key in selected,
                    onCheckedChange = { on -> if (on) { if (key !in selected) selected.add(key) } else selected.remove(key) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.param.label, style = MaterialTheme.typography.bodyMedium)
                    val now = c.currentValue
                    val unit = if (c.param.unit.isEmpty()) "" else " ${c.param.unit}"
                    Text(
                        if (!c.differs) "same: ${fmtNum(c.param.value)}$unit"
                        else "${if (now == null) "-" else fmtNum(now)} to ${fmtNum(c.param.value)}$unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.differs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---- Machine profile editor ---------------------------------------------------------------------

@Composable
internal fun ProfileEditorScreen(state: FeatherUiState, vm: FeatherViewModel, deviceId: String, onBack: () -> Unit) {
    val device = state.devices.devices.firstOrNull { it.id == deviceId }
    if (device == null) { SideEffect { onBack() }; return }
    val rows = ProfileParams.rows(device.profile)
    val values = remember(deviceId) { mutableStateMapOf<String, String>().also { m -> rows.forEach { m[it.key] = fmtNum(it.value) } } }
    var name by remember(deviceId) { mutableStateOf(device.name) }
    var type by remember(deviceId) { mutableStateOf(device.profile.machineType) }
    var controller by remember(deviceId) { mutableStateOf(device.controller) }
    var coordinate by remember(deviceId) { mutableStateOf(device.profile.coordinateSystem) }
    var copying by remember { mutableStateOf(false) }

    val problems = ArrayList<String>()
    for (r in rows) {
        val v = parseMm(values[r.key] ?: "")
        if (v == null) { problems += "${r.label} needs a number"; continue }
        val positiveOnly = r.key.endsWith(".travel") || r.key.endsWith(".steps") || r.key.endsWith(".feed") || r.key.endsWith(".accel")
        if (positiveOnly && v <= 0.0) problems += "${r.label} must be greater than 0"
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Machine profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            SectionHeader("Machine")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MachineType.values().toList()) { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.displayName) })
                }
            }
            SectionHeader("Controller")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Controller.values().toList()) { c ->
                    FilterChip(selected = controller == c, onClick = { controller = c }, label = { Text(Presets.controllerSummary(c)) })
                }
            }
            SectionHeader("Work coordinate system")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("G54", "G55", "G56", "G57").forEach { cs ->
                    FilterChip(selected = coordinate == cs, onClick = { coordinate = cs }, label = { Text(cs) })
                }
            }
            for (axis in Axis.values()) {
                val axisRows = rows.filter { it.key.startsWith(axis.name + ".") }
                if (axisRows.isEmpty()) continue
                SectionHeader("${axis.name} axis")
                axisRows.forEach { r ->
                    if (r.key.endsWith(".soft")) {
                        SwitchRow("Soft limits", values[r.key] == "1") { values[r.key] = if (it) "1" else "0" }
                    } else {
                        NumberField(
                            label = r.label, value = values[r.key] ?: "", onChange = { values[r.key] = it },
                            suffix = r.unit.ifEmpty { null },
                        )
                    }
                }
            }
            SectionHeader("Homing")
            SwitchRow("Machine has homing switches", values["homing"] == "1") { values["homing"] = if (it) "1" else "0" }
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        ButtonRow {
            OutlinedButton(onClick = { copying = true }, enabled = state.devices.devices.size > 1) { Text("Copy settings...") }
            Button(
                enabled = problems.isEmpty() && name.isNotBlank(),
                onClick = {
                    var profile = device.profile
                    for (r in rows) parseMm(values[r.key] ?: "")?.let { profile = ProfileParams.withValue(profile, r.key, it) }
                    vm.updateDevice(
                        device.copy(
                            name = name.trim(),
                            controller = controller,
                            profile = profile.copy(name = name.trim(), machineType = type, coordinateSystem = coordinate),
                        ),
                    )
                    onBack()
                },
            ) { Text("Save") }
        }
    }

    if (copying) {
        CopyProfileDialog(
            source = device,
            others = state.devices.devices.filter { it.id != device.id },
            onDismiss = { copying = false },
            onApply = { targetId, keys -> vm.copyProfileSettings(device.id, targetId, keys); copying = false },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Step 1 pick the machine to copy onto; step 2 review and tick exactly which parameters go across. */
@Composable
private fun CopyProfileDialog(source: Device, others: List<Device>, onDismiss: () -> Unit, onApply: (String, Set<String>) -> Unit) {
    var target by remember { mutableStateOf<Device?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val chosen = target
    val changes = if (chosen == null) emptyList() else ProfileParams.preview(source.profile, chosen.profile)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chosen == null) "Copy ${source.name} to..." else "Apply to ${chosen.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (chosen == null) {
                    others.forEach { d ->
                        TextButton(onClick = {
                            target = d
                            selected.clear()
                            selected.addAll(ProfileParams.preview(source.profile, d.profile).filter { it.differs }.map { it.param.key })
                        }) { Text(d.name) }
                    }
                } else {
                    Text("Only ticked settings are copied. ${chosen.name} keeps everything else.", style = MaterialTheme.typography.bodySmall)
                    ReviewList(changes, selected)
                }
            }
        },
        confirmButton = {
            if (chosen != null) TextButton(enabled = selected.isNotEmpty(), onClick = { onApply(chosen.id, selected.toSet()) }) {
                Text("Apply ${selected.size}")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (chosen != null) target = null else onDismiss() }) { Text(if (chosen != null) "Back" else "Cancel") }
        },
    )
}

// ---- Tool settings editor ---------------------------------------------------------------------------

@Composable
internal fun ToolEditorScreen(state: FeatherUiState, vm: FeatherViewModel, deviceId: String, toolId: String, onBack: () -> Unit) {
    val device = state.devices.devices.firstOrNull { it.id == deviceId }
    val tool = device?.tools?.firstOrNull { it.id == toolId }
    if (device == null || tool == null) { SideEffect { onBack() }; return }

    val rows = ToolParams.rows(tool)
    val values = remember(toolId) { mutableStateMapOf<String, String>().also { m -> rows.forEach { m[it.key] = fmtNum(it.value) } } }
    var name by remember(toolId) { mutableStateOf(tool.name) }
    var copying by remember { mutableStateOf(false) }

    val problems = ArrayList<String>()
    for (r in rows) if (parseMm(values[r.key] ?: "") == null) problems += "${r.label} needs a number"

    fun buildTool(): ToolHead {
        var t = ToolParams.withName(tool, name.trim())
        for (r in rows) parseMm(values[r.key] ?: "")?.let { t = ToolParams.withValue(t, r.key, it) }
        return t
    }

    val hasSameFamilyTarget = state.devices.devices.any { d -> d.tools.any { it.id != toolId && ToolParams.sameFamily(it, tool) } }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Tool settings", style = MaterialTheme.typography.titleLarge)
                Text("${device.name}, ${ToolParams.familyName(tool)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            rows.forEach { r ->
                NumberField(label = r.label, value = values[r.key] ?: "", onChange = { values[r.key] = it }, suffix = r.unit.ifEmpty { null })
            }
            if (tool is ToolHead.Laser) {
                Text(
                    "Laser output: S${(tool.defaultPowerPercent / 100.0 * tool.pwmMax).toInt()} of S${tool.pwmMax} at the power set here. " +
                        "Match PWM max to your controller's \$30 setting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        ButtonRow {
            OutlinedButton(onClick = { copying = true }, enabled = hasSameFamilyTarget) { Text("Copy settings...") }
            if (device.activeToolId != tool.id) OutlinedButton(onClick = { vm.setActiveTool(deviceId, toolId) }) { Text("Make active") }
            if (device.tools.size > 1) TextButton(onClick = { vm.removeTool(deviceId, toolId); onBack() }) { Text("Delete") }
            Button(
                enabled = problems.isEmpty() && name.isNotBlank(),
                onClick = { vm.saveTool(deviceId, buildTool()); onBack() },
            ) { Text("Save") }
        }
    }

    if (copying) {
        CopyToolDialog(
            source = buildTool(),
            sourceLabel = "${device.name} / ${tool.name}",
            targets = state.devices.devices.flatMap { d ->
                d.tools.filter { it.id != toolId && ToolParams.sameFamily(it, tool) }.map { d to it }
            },
            onDismiss = { copying = false },
            onApply = { targetDeviceId, targetToolId, keys ->
                vm.copyToolSettings(buildTool(), targetDeviceId, targetToolId, keys)
                copying = false
            },
        )
    }
}

/** Copy from this tool, apply to another of the same kind (Laser 10W to Laser 20W), with every parameter listed first. */
@Composable
private fun CopyToolDialog(
    source: ToolHead,
    sourceLabel: String,
    targets: List<Pair<Device, ToolHead>>,
    onDismiss: () -> Unit,
    onApply: (String, String, Set<String>) -> Unit,
) {
    var target by remember { mutableStateOf<Pair<Device, ToolHead>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val chosen = target
    val changes = if (chosen == null) emptyList() else ToolParams.preview(source, chosen.second)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chosen == null) "Apply $sourceLabel to..." else "Apply to ${chosen.second.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (chosen == null) {
                    if (targets.isEmpty()) Text("No other ${ToolParams.familyName(source).lowercase()} tools to copy onto.")
                    targets.forEach { (d, t) ->
                        TextButton(onClick = {
                            target = d to t
                            selected.clear()
                            selected.addAll(ToolParams.preview(source, t).filter { it.differs }.map { it.param.key })
                        }) { Text("${t.name}  (${d.name})") }
                    }
                } else {
                    Text(
                        "Copying from $sourceLabel. Only ticked settings are written; the name and everything else stay as they are.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ReviewList(changes, selected)
                }
            }
        },
        confirmButton = {
            if (chosen != null) {
                TextButton(enabled = selected.isNotEmpty(), onClick = { onApply(chosen.first.id, chosen.second.id, selected.toSet()) }) {
                    Text("Apply ${selected.size}")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (chosen != null) target = null else onDismiss() }) { Text(if (chosen != null) "Back" else "Cancel") }
        },
    )
}
