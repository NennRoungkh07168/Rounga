@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import feather.core.JobSettings
import feather.core.Micrometers
import feather.link.BlePermissions
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.link.LinkMode
import feather.link.LinkTarget
import java.io.File

/** The five top-level areas. Design is the drawing board; the rest are what the design needs to become a part. */
internal enum class Tab(val label: String, val icon: ImageVector) {
    DESIGN("Design", Icons.Filled.Brush),
    DEVICES("Devices", Icons.Filled.Memory),
    TOOLS("Tools", Icons.Filled.Build),
    MACHINE("Machine", Icons.Filled.PlayArrow),
    FILES("Files", Icons.Filled.Folder),
}

/** Full-screen pages that sit on top of the tabs and close with Back. */
internal sealed interface Overlay {
    object Simulate : Overlay
    data class Profile(val deviceId: String) : Overlay
    data class ToolEdit(val deviceId: String, val toolId: String) : Overlay
}

internal enum class Dlg { JOB, CALIBRATE, EXACT, BLE, STOCK, SAVE_PROJECT }

/** What the Design tab is for right now. */
internal enum class DesignMode(val label: String) {
    DRAWING("Drawing"),
    INTERIOR("Interior 3D"),
    PHOTO("Photo"),
}

private enum class AfterPermission { SCAN, CONNECT }

/** File pickers, gathered so every screen can offer the same actions. */
internal class FileActions(
    val open: () -> Unit,
    val saveAs: () -> Unit,
    val exportGcode: () -> Unit,
    val pickPhoto: () -> Unit,
    val takePhoto: () -> Unit,
    val takeMacroPhoto: () -> Unit,
)

@Composable
fun FeatherScreen(viewModel: FeatherViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.DESIGN) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var afterPermission by remember { mutableStateOf(AfterPermission.SCAN) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var mode by remember { mutableStateOf(DesignMode.DRAWING) }
    var showSplash by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** Pictures go to the photo editor when it is open, otherwise straight to the tracer. */
    fun routePicture(uri: Uri, macro: Boolean = false) {
        if (tab == Tab.DESIGN && mode == DesignMode.PHOTO) viewModel.photo.load(uri, macro = macro) else viewModel.startTrace(uri, macro)
    }

    FullscreenEffect(state.fullscreen)

    LaunchedEffect(state.simulateRequested) {
        if (state.simulateRequested) {
            overlay = Overlay.Simulate
            viewModel.consumeSimulateRequest()
        }
    }
    BackHandler(enabled = state.trace != null || overlay != null) {
        if (state.trace != null) viewModel.cancelTrace() else overlay = null
    }

    // ---- Pickers ---------------------------------------------------------------------
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openFrom(uri)
    }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.start(LinkTarget.Export(uri))
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) routePicture(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) routePicture(uri)
    }
    val macroCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) routePicture(uri, macro = true)
    }
    val savePngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch { viewModel.photo.saveTo(uri)?.let { viewModel.report(it) } ?: viewModel.report("Saved the edited picture") }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            if (afterPermission == AfterPermission.CONNECT) {
                viewModel.connectMachine()
            } else {
                dialog = Dlg.BLE
                viewModel.startScan()
            }
        } else {
            viewModel.report("Bluetooth permission is needed to find machines")
        }
    }

    fun findMachine() {
        afterPermission = AfterPermission.SCAN
        if (!BlePermissions.hasRequiredPermissions(context)) {
            permissionLauncher.launch(BlePermissions.requiredPermissions())
            return
        }
        if (!BlePermissions.isBluetoothEnabled(context)) {
            viewModel.report("Turn Bluetooth on first")
            return
        }
        dialog = Dlg.BLE
        viewModel.startScan()
    }

    fun connectMachine() {
        if (state.linkMode == LinkMode.BLUETOOTH) {
            if (state.bleAddress.isBlank()) { findMachine(); return }
            if (!BlePermissions.hasRequiredPermissions(context)) {
                afterPermission = AfterPermission.CONNECT
                permissionLauncher.launch(BlePermissions.requiredPermissions())
                return
            }
            if (!BlePermissions.isBluetoothEnabled(context)) {
                viewModel.report("Turn Bluetooth on first")
                return
            }
        }
        viewModel.connectMachine()
    }

    fun launchCamera(macro: Boolean) {
        try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraUri = uri
            if (macro) macroCameraLauncher.launch(uri) else cameraLauncher.launch(uri)
        } catch (e: Exception) {
            viewModel.report("No camera available (${e.message})")
        }
    }
    val files = FileActions(
        open = { openLauncher.launch(arrayOf("*/*")) },
        saveAs = { saveAsLauncher.launch("drawing.feather") },
        exportGcode = { exportLauncher.launch("output.gcode") },
        pickPhoto = { photoLauncher.launch("image/*") },
        takePhoto = { launchCamera(macro = false) },
        takeMacroPhoto = { launchCamera(macro = true) },
    )

    // ---- Layout ------------------------------------------------------------------------
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth > maxHeight
            val trace = state.trace
            val currentOverlay = overlay
            when {
                trace != null -> TraceScreen(state, trace, viewModel, wide)
                currentOverlay != null -> when (currentOverlay) {
                    is Overlay.Simulate -> SimulateScreen(state, wide) { overlay = null }
                    is Overlay.Profile -> ProfileEditorScreen(state, viewModel, currentOverlay.deviceId) { overlay = null }
                    is Overlay.ToolEdit -> ToolEditorScreen(state, viewModel, currentOverlay.deviceId, currentOverlay.toolId) { overlay = null }
                }
                else -> MainShell(
                    state = state,
                    viewModel = viewModel,
                    wide = wide,
                    tab = tab,
                    onTab = { tab = it },
                    mode = mode,
                    onMode = { mode = it },
                    onSavePng = { savePngLauncher.launch("edited.png") },
                    files = files,
                    openDialog = { dialog = it },
                    onFindMachine = { findMachine() },
                    onConnect = { connectMachine() },
                    onSimulate = { overlay = Overlay.Simulate },
                    onEditProfile = { overlay = Overlay.Profile(it) },
                    onEditTool = { d, t -> overlay = Overlay.ToolEdit(d, t) },
                )
            }
        }
        if (showSplash) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RoungaSplash { showSplash = false }
            }
        }
      }
    }

    // ---- Dialogs ---------------------------------------------------------------------------
    when (dialog) {
        Dlg.JOB -> JobSettingsDialog(
            current = state.settings,
            onDismiss = { dialog = null },
            onApply = { viewModel.updateSettings(it); dialog = null },
        )
        Dlg.CALIBRATE -> CalibrateDialog(
            displayedUm = state.measurement?.lengthUm ?: 0L,
            onDismiss = { dialog = null },
            onApply = { if (viewModel.calibrateFromMeasurement(it)) dialog = null },
        )
        Dlg.EXACT -> ExactShapeDialog(
            onDismiss = { dialog = null },
            onAddRect = { x, y, w, h -> if (viewModel.addRectangle(x, y, w, h)) dialog = null },
            onAddCircle = { x, y, d -> if (viewModel.addCircle(x, y, d)) dialog = null },
        )
        Dlg.STOCK -> StockDialog(
            current = state.stock,
            onDismiss = { dialog = null },
            onApply = { viewModel.setStock(it); dialog = null },
        )
        Dlg.SAVE_PROJECT -> NameDialog(
            title = "Save to Projects",
            initial = state.documentName?.removeSuffix(".feather") ?: "My project",
            confirm = "Save",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.saveProject(it); dialog = null },
        )
        Dlg.BLE -> AlertDialog(
            onDismissRequest = { viewModel.stopScan(); dialog = null },
            title = { Text("Bluetooth machines") },
            text = {
                Column {
                    if (state.scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.bleDevices.isEmpty()) {
                        Text(if (state.scanning) "Scanning..." else "No named devices found. Check the machine is powered and advertising.")
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(state.bleDevices, key = { it.address }) { d ->
                            TextButton(onClick = { viewModel.selectBleDevice(d); dialog = null }) {
                                Text("${d.name}  ${d.address}  ${d.rssi} dBm")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.startScan() }, enabled = !state.scanning) { Text("Rescan") } },
            dismissButton = { TextButton(onClick = { viewModel.stopScan(); dialog = null }) { Text("Close") } },
        )
        null -> Unit
    }
}

@Composable
private fun MainShell(
    state: FeatherUiState,
    viewModel: FeatherViewModel,
    wide: Boolean,
    tab: Tab,
    onTab: (Tab) -> Unit,
    mode: DesignMode,
    onMode: (DesignMode) -> Unit,
    onSavePng: () -> Unit,
    files: FileActions,
    openDialog: (Dlg) -> Unit,
    onFindMachine: () -> Unit,
    onConnect: () -> Unit,
    onSimulate: () -> Unit,
    onEditProfile: (String) -> Unit,
    onEditTool: (String, String) -> Unit,
) {
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier) {
            when (tab) {
                Tab.DESIGN -> Column(modifier = Modifier.weight(1f)) {
                    ModeBar(mode, onMode)
                    when (mode) {
                        DesignMode.DRAWING -> DesignScreen(
                            state = state, vm = viewModel, wide = wide, files = files,
                            openDialog = openDialog, onSimulate = onSimulate, onGoMachine = { onTab(Tab.MACHINE) },
                            modifier = Modifier.weight(1f),
                        )
                        DesignMode.INTERIOR -> InteriorScreen(viewModel, wide, Modifier.weight(1f))
                        DesignMode.PHOTO -> PhotoEditorScreen(viewModel, wide, files, onSavePng, Modifier.weight(1f))
                    }
                }
                Tab.DEVICES -> DevicesScreen(
                    state = state, vm = viewModel,
                    onEditProfile = onEditProfile, onEditTool = onEditTool,
                    modifier = Modifier.weight(1f),
                )
                Tab.TOOLS -> ToolsScreen(
                    state = state, vm = viewModel,
                    openDialog = openDialog, onConnect = onConnect, onSimulate = onSimulate,
                    onGoTab = onTab, onEditTool = onEditTool,
                    modifier = Modifier.weight(1f),
                )
                Tab.MACHINE -> MachineScreen(
                    state = state, vm = viewModel, wide = wide,
                    onFindMachine = onFindMachine, onConnect = onConnect, files = files,
                    modifier = Modifier.weight(1f),
                )
                Tab.FILES -> FilesScreen(
                    state = state, vm = viewModel, files = files, openDialog = openDialog,
                    modifier = Modifier.weight(1f),
                )
            }
            StatusStrip(state.statusMessage)
        }
    }

    if (wide) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Tab.values().forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
            content(Modifier.weight(1f).fillMaxSize())
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxWidth())
            NavigationBar {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeBar(mode: DesignMode, onMode: (DesignMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DesignMode.values().forEach { m ->
            androidx.compose.material3.FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
        }
    }
}

@Composable
private fun StatusStrip(message: String) {
    if (message.isBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Hides the system bars while [on] (swipe from an edge to peek at them), so the board gets the whole screen. */
@Composable
private fun FullscreenEffect(on: Boolean) {
    val view = LocalView.current
    LaunchedEffect(on) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

// ---- Dialogs shared by several screens -------------------------------------------------------

@Composable
internal fun JobSettingsDialog(current: JobSettings, onDismiss: () -> Unit, onApply: (JobSettings) -> Unit) {
    var depth by remember { mutableStateOf(current.totalDepthUm.toGcodeMm()) }
    var step by remember { mutableStateOf(current.stepDownUm.toGcodeMm()) }
    var feed by remember { mutableStateOf(current.feedRateMmPerMin.toString()) }
    var plunge by remember { mutableStateOf(current.plungeRateMmPerMin.toString()) }
    var safe by remember { mutableStateOf(current.safeHeightUm.toGcodeMm()) }
    var optimize by remember { mutableStateOf(current.optimizeTravel) }

    val d = parseMm(depth); val st = parseMm(step); val f = parseMm(feed); val p = parseMm(plunge); val sh = parseMm(safe)
    val candidate = if (d != null && st != null && f != null && p != null && sh != null) {
        current.copy(
            totalDepthUm = Micrometers.fromMillimeters(d),
            stepDownUm = Micrometers.fromMillimeters(st),
            feedRateMmPerMin = f,
            plungeRateMmPerMin = p,
            safeHeightUm = Micrometers.fromMillimeters(sh),
            optimizeTravel = optimize,
        )
    } else null
    val errors = candidate?.validationErrors() ?: listOf("Enter a valid number in every field.")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feed rates and depth") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                NumberField("Total depth (mm, multiple of 0.02)", depth, { depth = it })
                NumberField("Step-down per pass (mm, multiple of 0.02)", step, { step = it })
                NumberField("Feed rate (mm/min)", feed, { feed = it })
                NumberField("Plunge rate (mm/min)", plunge, { plunge = it })
                NumberField("Safe height (mm)", safe, { safe = it })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = optimize, onCheckedChange = { optimize = it })
                    Text("Optimise travel order")
                }
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = errors.isEmpty(), onClick = { candidate?.let(onApply) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun CalibrateDialog(displayedUm: Long, onDismiss: () -> Unit, onApply: (Double) -> Unit) {
    var actual by remember { mutableStateOf("") }
    val value = parseMm(actual)?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate screen") },
        text = {
            if (displayedUm <= 0L) {
                Text(
                    "Choose the Measure tool and drag along a ruler held against the screen " +
                        "(the longer the better), then open this again.",
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The app shows your segment as ${Micrometers(displayedUm).toGcodeMm()} mm. Enter the length your ruler shows.")
                    NumberField("Real length (mm)", actual, { actual = it })
                    Text(
                        "Calibrate before drawing: existing shapes keep their real-world dimensions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = displayedUm > 0L && value != null, onClick = { value?.let(onApply) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ExactShapeDialog(
    onDismiss: () -> Unit,
    onAddRect: (x: Double, y: Double, w: Double, h: Double) -> Unit,
    onAddCircle: (x: Double, y: Double, d: Double) -> Unit,
) {
    var circle by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf("0") }
    var y by remember { mutableStateOf("0") }
    var w by remember { mutableStateOf("10") }
    var h by remember { mutableStateOf("10") }
    val px = parseMm(x); val py = parseMm(y); val pw = parseMm(w); val ph = parseMm(h)
    val valid = px != null && py != null && pw != null && pw > 0.0 && (circle || (ph != null && ph > 0.0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exact shape") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = !circle, onClick = { circle = false }, label = { Text("Rectangle") })
                    androidx.compose.material3.FilterChip(selected = circle, onClick = { circle = true }, label = { Text("Circle") })
                }
                NumberField(if (circle) "Centre X (mm)" else "Corner X (mm)", x, { x = it })
                NumberField(if (circle) "Centre Y (mm)" else "Corner Y (mm)", y, { y = it })
                NumberField(if (circle) "Diameter (mm)" else "Width (mm)", w, { w = it })
                if (!circle) NumberField("Height (mm)", h, { h = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (circle) onAddCircle(px!!, py!!, pw!!) else onAddRect(px!!, py!!, pw!!, ph!!)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun StockDialog(current: feather.link.StockDims?, onDismiss: () -> Unit, onApply: (feather.link.StockDims?) -> Unit) {
    var w by remember { mutableStateOf(current?.let { fmtNum(it.widthMm) } ?: "300") }
    var h by remember { mutableStateOf(current?.let { fmtNum(it.heightMm) } ?: "200") }
    var t by remember { mutableStateOf(current?.let { fmtNum(it.thicknessMm) } ?: "10") }
    val pw = parseMm(w)?.takeIf { it > 0.0 }
    val ph = parseMm(h)?.takeIf { it > 0.0 }
    val pt = parseMm(t)?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stock dimensions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "The raw material, with its lower-left corner at the machine origin. It is drawn as a dashed outline on the board.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("Width X (mm)", w, { w = it })
                NumberField("Depth Y (mm)", h, { h = it })
                NumberField("Thickness Z (mm)", t, { t = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = pw != null && ph != null && pt != null,
                onClick = { onApply(feather.link.StockDims(pw!!, ph!!, pt!!)) },
            ) { Text("Apply") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onApply(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
