@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import feather.interior.Camera3D
import feather.interior.ItemType
import feather.interior.PlanExport
import feather.interior.PlanGeometry
import feather.interior.Render3D
import feather.link.FeatherViewModel
import feather.model.Axis
import feather.studio.InteriorModel
import feather.studio.InteriorState
import kotlin.math.min

private val ITEM_COLORS = listOf(
    0xFF6C8EBF, 0xFFB5838D, 0xFF9DB4C0, 0xFFC69C6D, 0xFFE9C46A, 0xFF52B788,
    0xFFE76F51, 0xFFF1E3D3, 0xFF6D6875, 0xFFFFFFFF, 0xFF264653, 0xFFD62828,
).map { it.toInt() }
private val FLOOR_COLORS = listOf(
    0xFFD9B99B, 0xFFB08968, 0xFFE5DCC5, 0xFFB8B8B8, 0xFF9AA5A0, 0xFF7A8B99, 0xFFC8D5B9,
).map { it.toInt() }
private val WALL_COLORS = listOf(
    0xFFF3EFE6, 0xFFFFFFFF, 0xFFDDE7F0, 0xFFF4D9D0, 0xFFD8E2DC, 0xFFEFE3F7, 0xFFFFE8B0,
).map { it.toInt() }

private enum class InteriorView(val label: String) { PLAN("Plan"), ROOM3D("3D view") }

/** Lay out a room in plan view, then look around it in 3D. The plan can be drawn onto the board at a scale. */
@Composable
internal fun InteriorScreen(vm: FeatherViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val model = vm.interior
    val st by model.state.collectAsState()
    var view by remember { mutableStateOf(InteriorView.PLAN) }
    var camera by remember { mutableStateOf(Camera3D()) }
    var showRoom by remember { mutableStateOf(false) }
    var showPlot by remember { mutableStateOf(false) }

    val actions: @Composable () -> Unit = {
        ButtonRow {
            if (!wide) InteriorView.values().forEach { v ->
                FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) })
            }
            OutlinedButton(onClick = { model.undo() }, enabled = st.canUndo) { Text("Undo") }
            OutlinedButton(onClick = { showRoom = true }) { Text("Room...") }
            Button(onClick = { showPlot = true }) { Text("Plan to drawing") }
        }
    }
    val plan: @Composable (Modifier) -> Unit = { m -> PlanCanvas(st, model, m) }
    val view3d: @Composable (Modifier) -> Unit = { m -> View3D(st, camera, { camera = it }, m) }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette(model)
            Inspector(st, model)
        }
    }

    if (wide) {
        Row(modifier = modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                actions()
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan(Modifier.weight(1f).fillMaxHeight())
                    view3d(Modifier.weight(1f).fillMaxHeight())
                }
            }
            Column(modifier = Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
        }
    } else {
        Column(modifier = modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            actions()
            if (view == InteriorView.PLAN) plan(Modifier.weight(1f).fillMaxWidth()) else view3d(Modifier.weight(1f).fillMaxWidth())
            Box(modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) { controls() }
        }
    }

    if (showRoom) {
        RoomDialog(st, model) { showRoom = false }
    }
    if (showPlot) {
        PlotDialog(vm, st) { showPlot = false }
    }
}

// ---- Plan view ------------------------------------------------------------------------------------------

@Composable
private fun PlanCanvas(st: InteriorState, model: InteriorModel, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val room = st.scene.room
    val margin = 24f
    val scale = if (box.width == 0) 1f else
        min((box.width - 2 * margin) / room.widthCm.toFloat(), (box.height - 2 * margin) / room.depthCm.toFloat()).coerceAtLeast(0.01f)
    val ox = (box.width - room.widthCm.toFloat() * scale) / 2f
    val oy = (box.height - room.depthCm.toFloat() * scale) / 2f

    val curScene by rememberUpdatedState(st.scene)
    val curScale by rememberUpdatedState(scale)
    val curOx by rememberUpdatedState(ox)
    val curOy by rememberUpdatedState(oy)
    val grab = remember { doubleArrayOf(0.0, 0.0) }
    val dragging = remember { booleanArrayOf(false) }

    fun worldX(px: Float) = ((px - curOx) / curScale).toDouble()
    fun worldY(py: Float) = ((py - curOy) / curScale).toDouble()
    fun snap(v: Double) = Math.round(v / 5.0) * 5.0

    Canvas(
        modifier = modifier
            .background(Color(0xFFE9F1F2), RoundedCornerShape(12.dp))
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { p ->
                    model.select(PlanGeometry.itemAt(curScene, worldX(p.x), worldY(p.y))?.id)
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        val hit = PlanGeometry.itemAt(curScene, worldX(p.x), worldY(p.y))
                        if (hit != null) {
                            model.select(hit.id)
                            model.beginMove()
                            grab[0] = hit.xCm - worldX(p.x)
                            grab[1] = hit.yCm - worldY(p.y)
                            dragging[0] = true
                        } else {
                            dragging[0] = false
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragging[0]) {
                            change.consume()
                            model.moveSelectedTo(snap(worldX(change.position.x) + grab[0]), snap(worldY(change.position.y) + grab[1]))
                        }
                    },
                    onDragEnd = { if (dragging[0]) model.endMove(); dragging[0] = false },
                    onDragCancel = { if (dragging[0]) model.endMove(); dragging[0] = false },
                )
            },
    ) {
        fun sx(x: Double) = ox + x.toFloat() * scale
        fun sy(y: Double) = oy + y.toFloat() * scale
        val left = sx(0.0); val top = sy(0.0)
        val right = sx(room.widthCm); val bottom = sy(room.depthCm)

        drawRect(Color(room.floorColor), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top))
        val grid = Color(0x22000000)
        var gx = 50.0
        while (gx < room.widthCm) { drawLine(grid, Offset(sx(gx), top), Offset(sx(gx), bottom), 1f); gx += 50.0 }
        var gy = 50.0
        while (gy < room.depthCm) { drawLine(grid, Offset(left, sy(gy)), Offset(right, sy(gy)), 1f); gy += 50.0 }

        val ordered = st.scene.items.sortedBy { if (it.type == ItemType.RUG) 0 else 1 }
        for (item in ordered) {
            val corners = PlanGeometry.corners(item)
            val path = Path()
            corners.forEachIndexed { i, c -> if (i == 0) path.moveTo(sx(c.first), sy(c.second)) else path.lineTo(sx(c.first), sy(c.second)) }
            path.close()
            val selected = item.id == st.selectedId
            drawPath(path, Color(item.color).copy(alpha = if (item.type == ItemType.RUG) 0.75f else 1f))
            drawPath(path, if (selected) Color(0xFF3D5AFE) else Color(0x66000000), style = Stroke(width = if (selected) 4f else 1.5f, join = StrokeJoin.Round))
            // The front edge is drawn heavier, so you can see which way a sofa or door faces.
            val f0 = corners[3]; val f1 = corners[2]
            drawLine(Color(0xAA000000), Offset(sx(f0.first), sy(f0.second)), Offset(sx(f1.first), sy(f1.second)), strokeWidth = 4f)
        }
        // Walls on top.
        drawRect(
            Color(room.wallColor).copy(alpha = 1f), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(width = 8f),
        )
        drawRect(
            Color(0x66000000), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(width = 1.5f),
        )
    }
}

// ---- 3D view --------------------------------------------------------------------------------------------

@Composable
private fun View3D(st: InteriorState, camera: Camera3D, onCamera: (Camera3D) -> Unit, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val cam by rememberUpdatedState(camera)
    val set by rememberUpdatedState(onCamera)
    val room = st.scene.room

    val polys = remember(st.scene, st.selectedId, camera, box) {
        if (box.width == 0 || box.height == 0) emptyList()
        else Render3D.render(st.scene, camera, box.width.toFloat(), box.height.toFloat(), st.selectedId)
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val c = cam
                    val d0 = if (c.distanceCm > 0.0) c.distanceCm else Render3D.defaultDistance(room)
                    val d1 = (d0 / zoom.toDouble()).coerceIn(150.0, 5000.0)
                    set(c.orbit(-pan.x * 0.4, pan.y * 0.3).copy(distanceCm = d1))
                }
            },
    ) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFEAF6F8), Color(0xFFCFE3E8))))
        for (p in polys) {
            val path = Path()
            path.moveTo(p.xs[0], p.ys[0])
            for (i in 1 until p.xs.size) path.lineTo(p.xs[i], p.ys[i])
            path.close()
            drawPath(path, Color(p.fill))
            drawPath(path, Color(p.edge), style = Stroke(width = p.edgeWidth, join = StrokeJoin.Round))
        }
    }
}

// ---- Palette and inspector ----------------------------------------------------------------------------------

@Composable
private fun Palette(model: InteriorModel) {
    PanelCard {
        Text("Add to the room", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ItemType.values().toList()) { t ->
                FilterChip(selected = false, onClick = { model.add(t) }, label = { Text(t.label) })
            }
        }
    }
}

@Composable
private fun Swatches(colors: List<Int>, current: Int?, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(colors) { c ->
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == current) 3.dp else 1.dp, if (c == current) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun Inspector(st: InteriorState, model: InteriorModel) {
    val item = st.selected
    PanelCard {
        if (item == null) {
            Text(
                "Tap an item in the plan to select it, drag it to move it, or add one from the list.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@PanelCard
        }
        Text("${item.name} (${fmtNum(item.widthCm)} x ${fmtNum(item.depthCm)} x ${fmtNum(item.heightCm)} cm)", style = MaterialTheme.typography.titleSmall)
        ButtonRow {
            OutlinedButton(onClick = { model.nudge(-10.0, 0.0) }) { Text("Left") }
            OutlinedButton(onClick = { model.nudge(10.0, 0.0) }) { Text("Right") }
            OutlinedButton(onClick = { model.nudge(0.0, -10.0) }) { Text("Back") }
            OutlinedButton(onClick = { model.nudge(0.0, 10.0) }) { Text("Forward") }
        }
        ButtonRow {
            OutlinedButton(onClick = { model.rotate(-90.0) }) { Text("Rotate left") }
            OutlinedButton(onClick = { model.rotate(90.0) }) { Text("Rotate right") }
            OutlinedButton(onClick = { model.rotate(15.0) }) { Text("+15") }
            OutlinedButton(onClick = { model.rotate(-15.0) }) { Text("-15") }
        }
        var w by remember(item.id, item.widthCm) { mutableStateOf(fmtNum(item.widthCm)) }
        var d by remember(item.id, item.depthCm) { mutableStateOf(fmtNum(item.depthCm)) }
        var h by remember(item.id, item.heightCm) { mutableStateOf(fmtNum(item.heightCm)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("W cm", w, { w = it }, Modifier.weight(1f))
            NumberField("D cm", d, { d = it }, Modifier.weight(1f))
            NumberField("H cm", h, { h = it }, Modifier.weight(1f))
        }
        val pw = parseMm(w); val pd = parseMm(d); val ph = parseMm(h)
        ButtonRow {
            Button(onClick = { model.resize(pw!!, pd!!, ph!!) }, enabled = pw != null && pd != null && ph != null) { Text("Apply size") }
            OutlinedButton(onClick = { model.duplicate() }) { Text("Duplicate") }
            OutlinedButton(onClick = { model.delete() }) { Text("Delete") }
        }
        Text("Colour", style = MaterialTheme.typography.labelMedium)
        Swatches(ITEM_COLORS, item.color) { model.setColor(it) }
    }
}

// ---- Dialogs ------------------------------------------------------------------------------------------------

@Composable
private fun RoomDialog(st: InteriorState, model: InteriorModel, onClose: () -> Unit) {
    val room = st.scene.room
    var w by remember { mutableStateOf(fmtNum(room.widthCm)) }
    var d by remember { mutableStateOf(fmtNum(room.depthCm)) }
    var h by remember { mutableStateOf(fmtNum(room.heightCm)) }
    val pw = parseMm(w); val pd = parseMm(d); val ph = parseMm(h)
    val valid = pw != null && pd != null && ph != null && pw in 150.0..3000.0 && pd in 150.0..3000.0 && ph in 150.0..3000.0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Width cm", w, { w = it }, Modifier.weight(1f))
                    NumberField("Depth cm", d, { d = it }, Modifier.weight(1f))
                    NumberField("Height cm", h, { h = it }, Modifier.weight(1f))
                }
                if (!valid) Text("Sizes must be between 150 and 3000 cm.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Floor", style = MaterialTheme.typography.labelMedium)
                Swatches(FLOOR_COLORS, room.floorColor) { model.setFloorColor(it) }
                Text("Walls", style = MaterialTheme.typography.labelMedium)
                Swatches(WALL_COLORS, room.wallColor) { model.setWallColor(it) }
                ButtonRow {
                    OutlinedButton(onClick = { model.clearRoom(); onClose() }) { Text("Empty room") }
                    OutlinedButton(onClick = { model.loadStarter(); onClose() }) { Text("Sample room") }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { model.setRoom(pw!!, pd!!, ph!!); onClose() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun PlotDialog(vm: FeatherViewModel, st: InteriorState, onClose: () -> Unit) {
    val ui by vm.uiState.collectAsState()
    val bed = ui.devices.activeDevice?.profile
    var den by remember { mutableStateOf(50) }
    val (pw, ph) = PlanExport.paperSizeMm(st.scene.room, den)
    val bedX = bed?.travelMm(Axis.X) ?: 0.0
    val bedY = bed?.travelMm(Axis.Y) ?: 0.0
    val fits = bed == null || (pw + 20 <= bedX && ph + 20 <= bedY)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Plan to drawing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Adds the room outline and every item's footprint to the drawing board, ready to plot, engrave or cut at a drawing scale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PlanExport.scales) { s -> FilterChip(selected = den == s, onClick = { den = s }, label = { Text("1:$s") }) }
                }
                Text("Paper size: ${fmtNum(Math.round(pw).toDouble())} x ${fmtNum(Math.round(ph).toDouble())} mm")
                if (bed != null) {
                    Text(
                        if (fits) "Fits ${ui.devices.activeDevice?.name}'s bed." else "Too big for ${ui.devices.activeDevice?.name}'s ${fmtNum(bedX)} x ${fmtNum(bedY)} mm bed. Pick a smaller scale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.plotInterior(den); onClose() }) { Text("Add to drawing") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
