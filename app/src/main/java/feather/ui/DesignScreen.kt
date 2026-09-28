@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import feather.core.BoardCalibration
import feather.core.Geometry
import feather.core.Micrometers
import feather.core.PointUm
import feather.core.Shape
import feather.core.ShapeOps
import feather.core.Viewport
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.link.JobReport
import feather.link.Tool
import feather.model.Axis
import feather.model.MachineProfile
import java.util.Locale
import kotlin.math.roundToInt

// Drawing-board colours: light paper, soft cool grid, a dark-slate ink, vivid accents for what you are working on.
private val PAPER = Color(0xFFFDFEFE)
private val GRID_MINOR = Color(0xFFE6F0F2)
private val GRID_MAJOR = Color(0xFFC9DCDF)
private val AXIS = Color(0xFF00ACC1)
private val INK = Color(0xFF1F2E33)
private val DRAFT = Color(0xFFE8590C)
private val MEASURE = Color(0xFF00897B)
private val SELECTED = Color(0xFF3D5AFE)
private val BED_FILL = Color(0x1A00ACC1)
private val BED_EDGE = Color(0xFF00ACC1)
private val STOCK_EDGE = Color(0xFFFF9800)

/**
 * The contextual workflow under the board: Draw -> Select -> Transform -> Trace -> Simplify -> Toolpath.
 * Each stage swaps the panel below the board; the drawing tool chips stay put.
 */
internal enum class Stage(val label: String) {
    DRAW("Draw"), SELECT("Select"), TRANSFORM("Transform"), LAYERS("Layers"), TRACE("Trace"), SIMPLIFY("Simplify"), TOOLPATH("Toolpath"),
}

@Composable
internal fun DesignScreen(
    state: FeatherUiState,
    vm: FeatherViewModel,
    wide: Boolean,
    files: FileActions,
    openDialog: (Dlg) -> Unit,
    onSimulate: () -> Unit,
    onGoMachine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by remember { mutableStateOf(Stage.DRAW) }

    fun chooseStage(s: Stage) {
        stage = s
        when (s) {
            Stage.DRAW -> if (state.tool == Tool.SELECT) vm.setTool(Tool.FREEHAND)
            Stage.SELECT, Stage.TRANSFORM, Stage.SIMPLIFY, Stage.LAYERS -> vm.setTool(Tool.SELECT)
            Stage.TRACE, Stage.TOOLPATH -> Unit
        }
    }

    val panel: @Composable () -> Unit = {
        when (stage) {
            Stage.DRAW -> DrawPanel(state, vm, openDialog)
            Stage.SELECT -> SelectPanel(state, vm)
            Stage.TRANSFORM -> TransformPanel(state, vm)
            Stage.LAYERS -> LayersPanel(state, vm)
            Stage.TRACE -> TracePanel(files)
            Stage.SIMPLIFY -> SimplifyPanel(state, vm)
            Stage.TOOLPATH -> ToolpathPanel(state, vm, files, openDialog, onSimulate, onGoMachine)
        }
    }

    if (wide) {
        Row(modifier = modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DesignMenuBar(state, vm, files, openDialog, onSimulate)
                ToolChips(state, vm)
                BoardBox(state, vm, Modifier.weight(1f).fillMaxWidth())
                Text(readout(state), style = MaterialTheme.typography.labelLarge)
            }
            Column(
                modifier = Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageBar(stage, ::chooseStage)
                panel()
            }
        }
    } else {
        Column(modifier = modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesignMenuBar(state, vm, files, openDialog, onSimulate)
            ToolChips(state, vm)
            BoardBox(state, vm, Modifier.weight(1f).fillMaxWidth())
            Text(readout(state), style = MaterialTheme.typography.labelLarge)
            StageBar(stage, ::chooseStage)
            Box(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { panel() }
        }
    }
}

// ---- Menus and chips ---------------------------------------------------------------------

@Composable
private fun DesignMenuBar(
    state: FeatherUiState,
    vm: FeatherViewModel,
    files: FileActions,
    openDialog: (Dlg) -> Unit,
    onSimulate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoungaWordmark(progress = 1f, showQuill = false, modifier = Modifier.width(92.dp).height(38.dp), strokeWidth = 9f)
        if (state.documentName != null) {
            Text(
                text = state.documentName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        MenuButton("File") { close ->
            Item("New", close) { vm.newDocument() }
            Item("Open...", close) { files.open() }
            Item("Save", close, enabled = state.documentUri != null) { state.documentUri?.let(vm::saveTo) }
            Item("Save as...", close) { files.saveAs() }
            Item("Save to Projects...", close) { openDialog(Dlg.SAVE_PROJECT) }
            Item("Trace a picture...", close) { files.pickPhoto() }
            Item("Export G-code...", close) { files.exportGcode() }
        }
        MenuButton("Edit") { close ->
            Item("Undo", close, enabled = state.canUndo) { vm.undo() }
            Item("Redo", close, enabled = state.canRedo) { vm.redo() }
            Item("Select all", close, enabled = state.shapes.isNotEmpty()) { vm.selectAll() }
            Item("Add exact shape...", close) { openDialog(Dlg.EXACT) }
            Item("Clear all", close, enabled = state.shapes.isNotEmpty()) { vm.clearShapes() }
        }
        MenuButton("View") { close ->
            Item(if (state.showGrid) "Grid: on" else "Grid: off", close) { vm.toggleGrid() }
            Item(if (state.snapToGrid) "Snap to grid: on" else "Snap to grid: off", close) { vm.toggleSnap() }
            Item("Zoom in", close) { vm.zoomBy(1.5) }
            Item("Zoom out", close) { vm.zoomBy(1 / 1.5) }
            Item("Fit drawing", close) { vm.fitToDrawing() }
            Item("Reset view", close) { vm.resetView() }
            Item(if (state.fullscreen) "Exit full screen" else "Full screen", close) { vm.setFullscreen(!state.fullscreen) }
        }
        MenuButton("Build") { close ->
            Item("Feed rates and depth...", close) { openDialog(Dlg.JOB) }
            Item("Check job", close, enabled = state.shapes.isNotEmpty()) { vm.analyzeJob() }
            Item("Simulate", close, enabled = state.shapes.isNotEmpty()) { if (vm.analyzeJob() != null) onSimulate() }
            Item("Calibrate screen...", close) { openDialog(Dlg.CALIBRATE) }
        }
        TextButton(onClick = { vm.undo() }, enabled = state.canUndo) { Text("Undo") }
        TextButton(onClick = { vm.redo() }, enabled = state.canRedo) { Text("Redo") }
    }
}

@Composable
private fun MenuButton(label: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label v") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun Item(text: String, close: () -> Unit, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = { close(); onClick() })
}

@Composable
private fun ToolChips(state: FeatherUiState, vm: FeatherViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(Tool.values().toList()) { tool ->
            FilterChip(
                selected = state.tool == tool,
                onClick = { vm.setTool(tool) },
                label = { Text(toolLabel(tool)) },
            )
        }
    }
}

@Composable
private fun StageBar(current: Stage, onPick: (Stage) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(Stage.values().toList()) { s ->
            FilterChip(selected = current == s, onClick = { onPick(s) }, label = { Text(s.label) })
        }
    }
}

private fun toolLabel(t: Tool) = when (t) {
    Tool.PAN -> "Pan"
    Tool.SELECT -> "Select"
    Tool.FREEHAND -> "Draw"
    Tool.LINE -> "Line"
    Tool.RECT -> "Rect"
    Tool.CIRCLE -> "Circle"
    Tool.MEASURE -> "Measure"
}

private fun mm(um: Long) = Micrometers(um).toGcodeMm()

private fun readout(s: FeatherUiState): String {
    val m = s.measurement
    val c = s.cursor
    return when {
        m != null -> "L ${mm(m.lengthUm)}  dX ${mm(m.dxUm)}  dY ${mm(m.dyUm)} mm  " +
            "${String.format(Locale.ROOT, "%.2f", m.angleDegrees)} deg"
        c != null -> "X ${c.x.toGcodeMm()}  Y ${c.y.toGcodeMm()} mm"
        else -> "Zoom ${(s.viewport.zoom * 100).roundToInt()}%  grid ${mm(s.viewport.gridSpacingUm(s.calibration))} mm"
    }
}

// ---- Stage panels -----------------------------------------------------------------------------

@Composable
private fun DrawPanel(state: FeatherUiState, vm: FeatherViewModel, openDialog: (Dlg) -> Unit) {
    PanelCard {
        Text("Draw with one finger; pinch with two to zoom and pan.", style = MaterialTheme.typography.bodySmall)
        ButtonRow {
            OutlinedButton(onClick = { openDialog(Dlg.EXACT) }) { Text("Exact shape...") }
            OutlinedButton(onClick = { vm.fitToDrawing() }) { Text("Fit") }
            OutlinedButton(onClick = { vm.toggleSnap() }) { Text(if (state.snapToGrid) "Snap: on" else "Snap: off") }
        }
    }
}

@Composable
private fun SelectPanel(state: FeatherUiState, vm: FeatherViewModel) {
    val n = state.selection.size
    PanelCard {
        Text(
            if (n == 0) "Tap a shape to select it. Drag a selected shape to move it." else "$n selected. Drag on a selected shape to move it.",
            style = MaterialTheme.typography.bodySmall,
        )
        ButtonRow {
            OutlinedButton(onClick = { vm.selectAll() }, enabled = state.shapes.isNotEmpty()) { Text("Select all") }
            OutlinedButton(onClick = { vm.clearSelection() }, enabled = n > 0) { Text("Clear") }
            OutlinedButton(onClick = { vm.deleteSelection() }, enabled = n > 0) { Text("Delete") }
        }
    }
}

@Composable
private fun TransformPanel(state: FeatherUiState, vm: FeatherViewModel) {
    var step by remember { mutableStateOf(1.0) }
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var xText by remember { mutableStateOf("") }
    var yText by remember { mutableStateOf("") }
    val n = state.selection.size
    val bounds = Geometry.bounds(state.selection.filter { it in state.shapes.indices }.map { state.shapes[it] })
    PanelCard {
        if (n == 0 || bounds == null) {
            Text("Select something first, then move, scale, rotate or mirror it here.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.selectAll() }, enabled = state.shapes.isNotEmpty()) { Text("Select all") }
        } else {
            Text("$n selected: ${fmtNum(bounds.width / 1000.0)} x ${fmtNum(bounds.height / 1000.0)} mm", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.1, 1.0, 10.0).forEach { v ->
                    FilterChip(selected = step == v, onClick = { step = v }, label = { Text("${fmtNum(v)} mm") })
                }
            }
            ButtonRow {
                OutlinedButton(onClick = { vm.nudgeSelection(-step, 0.0) }) { Text("Left") }
                OutlinedButton(onClick = { vm.nudgeSelection(step, 0.0) }) { Text("Right") }
                OutlinedButton(onClick = { vm.nudgeSelection(0.0, step) }) { Text("Up") }
                OutlinedButton(onClick = { vm.nudgeSelection(0.0, -step) }) { Text("Down") }
            }
            ButtonRow {
                OutlinedButton(onClick = { vm.scaleSelection(0.9) }) { Text("-10%") }
                OutlinedButton(onClick = { vm.scaleSelection(1.1) }) { Text("+10%") }
                OutlinedButton(onClick = { vm.scaleSelection(0.5) }) { Text("Half") }
                OutlinedButton(onClick = { vm.scaleSelection(2.0) }) { Text("Double") }
            }
            ButtonRow {
                OutlinedButton(onClick = { vm.rotateSelection(90.0) }) { Text("90 left") }
                OutlinedButton(onClick = { vm.rotateSelection(-90.0) }) { Text("90 right") }
                OutlinedButton(onClick = { vm.rotateSelection(15.0) }) { Text("15 left") }
                OutlinedButton(onClick = { vm.rotateSelection(-15.0) }) { Text("15 right") }
            }
            ButtonRow {
                OutlinedButton(onClick = { vm.mirrorSelection(true) }) { Text("Mirror L/R") }
                OutlinedButton(onClick = { vm.mirrorSelection(false) }) { Text("Mirror U/D") }
                OutlinedButton(onClick = { vm.duplicateSelection() }) { Text("Duplicate") }
                OutlinedButton(onClick = { vm.deleteSelection() }) { Text("Delete") }
            }
            SectionHeader("Exact size (mm)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("Width (mm)", widthText, { widthText = it }, Modifier.weight(1f))
                Button(
                    onClick = { parseMm(widthText)?.let { vm.resizeSelectionToWidth(it) } },
                    enabled = parseMm(widthText)?.let { it > 0.0 } == true,
                ) { Text("Set") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("Height (mm)", heightText, { heightText = it }, Modifier.weight(1f))
                Button(
                    onClick = { parseMm(heightText)?.let { vm.resizeSelectionToHeight(it) } },
                    enabled = parseMm(heightText)?.let { it > 0.0 } == true,
                ) { Text("Set") }
            }
            Text(
                "Sizing is proportional (a Circle has no separate width/height to stretch independently).",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionHeader("Exact position (mm, centre of selection)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField("X (mm)", xText, { xText = it }, Modifier.weight(1f))
                NumberField("Y (mm)", yText, { yText = it }, Modifier.weight(1f))
                Button(
                    onClick = { val x = parseMm(xText); val y = parseMm(yText); if (x != null && y != null) vm.moveSelectionTo(x, y) },
                    enabled = parseMm(xText) != null && parseMm(yText) != null,
                ) { Text("Move") }
            }
            SectionHeader("Color")
            ColorSwatchRow(onPick = { vm.setSelectionColor(it) })
        }
    }
}

/** Small tappable color circles — six presets, enough to tell objects apart without a full picker. */
@Composable
private fun ColorSwatchRow(onPick: (Int) -> Unit) {
    val swatches = listOf(
        -0x1000000,   // black
        -0x1000000 or 0xE53935,   // red
        -0x1000000 or 0x43A047,   // green
        -0x1000000 or 0x1E88E5,   // blue
        -0x1000000 or 0xFB8C00,   // orange
        -0x1000000 or 0x8E24AA,   // purple
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        swatches.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(argb) },
            )
        }
    }
}

/** Every shape on the board, top-to-bottom in draw order (top of the list = drawn last = on top). */
@Composable
private fun LayersPanel(state: FeatherUiState, vm: FeatherViewModel) {
    PanelCard {
        if (state.shapes.isEmpty()) {
            Text("No shapes yet — draw something, add an exact shape, or trace a photo.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Tap a row to select it in Transform. Top of the list is drawn on top.", style = MaterialTheme.typography.bodySmall)
            // Shown back-to-front (top of list = last in the array = drawn on top), matching how it looks on the board.
            state.shapes.indices.sortedDescending().forEach { i ->
                val shape = state.shapes[i]
                val selected = i in state.selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { vm.setSelection(setOf(i)) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(shape.colorArgb), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text(shapeLabel(shape), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButtonText(if (shape.hidden) "Show" else "Hide") { vm.setShapeHidden(i, !shape.hidden) }
                    IconButtonText("\u2191") { vm.moveShapeOrder(i, towardFront = true) }   // ↑ toward front
                    IconButtonText("\u2193") { vm.moveShapeOrder(i, towardFront = false) }  // ↓ toward back
                }
            }
        }
    }
}

@Composable
private fun IconButtonText(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun shapeLabel(s: Shape): String = when (s) {
    is Shape.Line -> "Line"
    is Shape.Rect -> "Rectangle"
    is Shape.Circle -> "Circle"
    is Shape.Polyline -> "Path (${s.points.size} pts)"
}

@Composable
private fun TracePanel(files: FileActions) {
    PanelCard {
        Text("Turn a photo or drawing into something the machine can cut.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Photo, grayscale, threshold, outlines, simplify, size, G-code. Dark shapes on a plain light background trace best.",
            style = MaterialTheme.typography.bodySmall,
        )
        ButtonRow {
            Button(onClick = files.takePhoto) { Text("Take photo") }
            OutlinedButton(onClick = files.pickPhoto) { Text("Choose picture") }
        }
    }
}

@Composable
private fun SimplifyPanel(state: FeatherUiState, vm: FeatherViewModel) {
    var tolMm by remember { mutableStateOf(0.1) }
    val target = if (state.selection.isEmpty()) state.shapes else state.selection.filter { it in state.shapes.indices }.map { state.shapes[it] }
    val points = target.sumOf { ShapeOps.vertexCount(it) }
    PanelCard {
        Text(
            "Fewer points give smoother, faster toolpaths. ${if (state.selection.isEmpty()) "All shapes" else "Selection"}: $points points.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.05, 0.1, 0.25, 0.5).forEach { v ->
                FilterChip(selected = tolMm == v, onClick = { tolMm = v }, label = { Text("${fmtNum(v)} mm") })
            }
        }
        Button(
            enabled = state.shapes.isNotEmpty(),
            onClick = {
                if (state.selection.isEmpty()) vm.selectAll()
                vm.simplifySelection(Math.round(tolMm * 1000.0))
            },
        ) { Text(if (state.selection.isEmpty()) "Simplify all" else "Simplify selected") }
    }
}

@Composable
private fun ToolpathPanel(
    state: FeatherUiState,
    vm: FeatherViewModel,
    files: FileActions,
    openDialog: (Dlg) -> Unit,
    onSimulate: () -> Unit,
    onGoMachine: () -> Unit,
) {
    val device = state.devices.activeDevice
    val s = state.settings
    PanelCard {
        Text(
            if (device == null) "No machine selected. Pick one in Devices."
            else "${device.name}, ${device.activeTool?.name ?: "no tool"}, bed " +
                "${fmtNum(device.profile.travelMm(Axis.X))} x ${fmtNum(device.profile.travelMm(Axis.Y))} mm",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "Depth ${s.totalDepthUm.toGcodeMm()} mm in ${s.stepDownUm.toGcodeMm()} mm passes, feed ${fmtNum(s.feedRateMmPerMin)} mm/min",
            style = MaterialTheme.typography.bodySmall,
        )
        ButtonRow {
            OutlinedButton(onClick = { openDialog(Dlg.JOB) }) { Text("Feed rates...") }
            Button(onClick = { vm.analyzeJob() }, enabled = state.shapes.isNotEmpty()) { Text("Check job") }
        }
        state.jobReport?.let { ReportSummary(it) }
        ButtonRow {
            OutlinedButton(
                enabled = state.shapes.isNotEmpty(),
                onClick = { if (vm.analyzeJob() != null) onSimulate() },
            ) { Text("Simulate") }
            OutlinedButton(onClick = files.exportGcode, enabled = state.shapes.isNotEmpty()) { Text("Export G-code...") }
            Button(onClick = onGoMachine) { Text("Send to machine") }
        }
    }
}

/** Time, length and any problems for a checked job. Shared by the Toolpath stage and the Tools screen. */
@Composable
internal fun ReportSummary(report: JobReport) {
    val st = report.stats
    Text(
        "${report.source}: ${st.moves} moves, about ${st.timeText()}. Cutting ${fmtNum(Math.round(st.cutLengthMm).toDouble())} mm, travel ${fmtNum(Math.round(st.rapidLengthMm).toDouble())} mm.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (report.problems.isEmpty()) {
        Text("Fits the machine. No problems found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    } else {
        report.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

// ---- Board -------------------------------------------------------------------------------------

@Composable
private fun BoardBox(state: FeatherUiState, vm: FeatherViewModel, modifier: Modifier) {
    Box(modifier = modifier) {
        BoardCanvas(state, vm, Modifier.fillMaxSize())
        IconButton(onClick = { vm.setFullscreen(!state.fullscreen) }, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(
                imageVector = if (state.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = "Full screen",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One finger draws with the current tool (or pans with the Pan tool); two
 * fingers always pinch-zoom and pan. Starting a second finger cancels the
 * stroke in progress so a pinch never leaves a stray mark.
 */
private fun Modifier.boardGestures(vm: FeatherViewModel, tool: Tool): Modifier = pointerInput(tool) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drawing = tool != Tool.PAN
        var multiTouch = false
        if (drawing) vm.beginDrag(down.position.x, down.position.y)
        do {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) {
                if (drawing) { vm.cancelDrag(); drawing = false }
                multiTouch = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid()
                if (zoom != 1f || pan != Offset.Zero) vm.onTransform(centroid.x, centroid.y, pan.x, pan.y, zoom)
                event.changes.forEach { it.consume() }
            } else if (!multiTouch) {
                val change = event.changes.firstOrNull { it.pressed } ?: event.changes.first()
                if (tool == Tool.PAN) {
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) vm.onTransform(change.position.x, change.position.y, delta.x, delta.y, 1f)
                } else if (drawing) {
                    vm.dragTo(change.position.x, change.position.y)
                }
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (drawing) vm.endDrag()
    }
}

@Composable
private fun BoardCanvas(state: FeatherUiState, vm: FeatherViewModel, modifier: Modifier) {
    val vp = state.viewport
    val cal = state.calibration
    val profile = state.devices.activeDevice?.profile
    Canvas(
        modifier = modifier
            .clipToBounds()
            .background(PAPER)
            .onSizeChanged { vm.onCanvasSize(it.width, it.height) }
            .boardGestures(vm, state.tool),
    ) {
        if (state.showGrid) drawGrid(vp, cal)
        if (profile != null) drawBed(profile, vp, cal)
        state.stock?.let { drawStock(it.widthMm, it.heightMm, vp, cal) }
        state.shapes.forEachIndexed { i, shape ->
            if (shape.hidden) return@forEachIndexed   // Layers panel: hidden shapes are skipped, not deleted
            if (i in state.selection) drawShape(shape, vp, cal, SELECTED, 5f) else drawShape(shape, vp, cal, Color(shape.colorArgb), 3f)
        }
        if (state.selection.isNotEmpty()) drawSelectionBox(state, vp, cal)
        state.draft?.let { drawShape(it, vp, cal, DRAFT, 3f) }
        state.measurement?.let { m ->
            val a = Offset(vp.worldToScreenX(m.a.x, cal).toFloat(), vp.worldToScreenY(m.a.y, cal).toFloat())
            val b = Offset(vp.worldToScreenX(m.b.x, cal).toFloat(), vp.worldToScreenY(m.b.y, cal).toFloat())
            drawLine(MEASURE, a, b, strokeWidth = 3f)
            drawCircle(MEASURE, radius = 7f, center = a)
            drawCircle(MEASURE, radius = 7f, center = b)
        }
    }
}

private fun DrawScope.drawGrid(vp: Viewport, cal: BoardCalibration) {
    val spacing = vp.gridSpacingUm(cal)
    val s = vp.pxPerMm(cal)
    val left = vp.originXUm
    val right = left + (size.width / s * 1000.0).toLong()
    val top = vp.originYUm
    val bottom = top - (size.height / s * 1000.0).toLong()
    val major = spacing * 10

    var gx = Math.floorDiv(left, spacing) * spacing
    while (gx <= right) {
        val x = vp.worldToScreenX(Micrometers(gx), cal).toFloat()
        val color = if (gx == 0L) AXIS else if (gx % major == 0L) GRID_MAJOR else GRID_MINOR
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (gx == 0L) 2f else 1f)
        gx += spacing
    }
    var gy = Math.floorDiv(bottom, spacing) * spacing
    while (gy <= top) {
        val y = vp.worldToScreenY(Micrometers(gy), cal).toFloat()
        val color = if (gy == 0L) AXIS else if (gy % major == 0L) GRID_MAJOR else GRID_MINOR
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = if (gy == 0L) 2f else 1f)
        gy += spacing
    }
}

/** The active machine's working area, so you can see whether the design fits before pressing Start. */
private fun DrawScope.drawBed(profile: MachineProfile, vp: Viewport, cal: BoardCalibration) {
    val tx = profile.travelMm(Axis.X)
    val ty = profile.travelMm(Axis.Y)
    if (tx <= 0.0 || ty <= 0.0) return
    val s = vp.pxPerMm(cal)
    val left = vp.worldToScreenX(Micrometers(0L), cal).toFloat()
    val bottom = vp.worldToScreenY(Micrometers(0L), cal).toFloat()
    val topLeft = Offset(left, bottom - (ty * s).toFloat())
    val boxSize = Size((tx * s).toFloat(), (ty * s).toFloat())
    drawRect(BED_FILL, topLeft, boxSize)
    drawRect(BED_EDGE, topLeft, boxSize, style = Stroke(width = 3f))
}

private fun DrawScope.drawStock(widthMm: Double, heightMm: Double, vp: Viewport, cal: BoardCalibration) {
    val s = vp.pxPerMm(cal)
    val left = vp.worldToScreenX(Micrometers(0L), cal).toFloat()
    val bottom = vp.worldToScreenY(Micrometers(0L), cal).toFloat()
    val topLeft = Offset(left, bottom - (heightMm * s).toFloat())
    val boxSize = Size((widthMm * s).toFloat(), (heightMm * s).toFloat())
    drawRect(
        STOCK_EDGE, topLeft, boxSize,
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

private fun DrawScope.drawSelectionBox(state: FeatherUiState, vp: Viewport, cal: BoardCalibration) {
    val picked = state.selection.filter { it in state.shapes.indices }.map { state.shapes[it] }
    val b = Geometry.bounds(picked) ?: return
    val x0 = vp.worldToScreenX(Micrometers(b.minX), cal).toFloat()
    val x1 = vp.worldToScreenX(Micrometers(b.maxX), cal).toFloat()
    val y0 = vp.worldToScreenY(Micrometers(b.maxY), cal).toFloat()
    val y1 = vp.worldToScreenY(Micrometers(b.minY), cal).toFloat()
    drawRect(
        SELECTED, Offset(x0, y0), Size(x1 - x0, y1 - y0),
        style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
    )
}

private fun DrawScope.drawShape(shape: Shape, vp: Viewport, cal: BoardCalibration, color: Color, width: Float) {
    fun pt(p: PointUm) = Offset(vp.worldToScreenX(p.x, cal).toFloat(), vp.worldToScreenY(p.y, cal).toFloat())
    val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

    if (shape is Shape.Circle) {
        val r = (vp.worldToScreenX(shape.center.x + shape.radiusUm, cal) - vp.worldToScreenX(shape.center.x, cal)).toFloat()
        drawCircle(color, radius = r, center = pt(shape.center), style = stroke)
        return
    }
    val points: List<PointUm> = when (shape) {
        is Shape.Line -> listOf(shape.a, shape.b)
        is Shape.Rect -> {
            val tl = shape.topLeft
            val tr = tl.copy(x = tl.x + shape.widthUm)
            val br = tr.copy(y = tl.y + shape.heightUm)
            val bl = tl.copy(y = tl.y + shape.heightUm)
            listOf(tl, tr, br, bl, tl)
        }
        is Shape.Polyline -> shape.points
        is Shape.Circle -> return
    }
    if (points.size < 2) return
    val path = Path()
    val first = pt(points[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until points.size) {
        val o = pt(points[i])
        path.lineTo(o.x, o.y)
    }
    drawPath(path, color, style = stroke)
}
