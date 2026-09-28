@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import feather.link.FeatherViewModel
import feather.studio.Adjust
import feather.studio.CropRect
import feather.studio.PhotoEditor
import feather.studio.PhotoState
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private enum class PhotoTool(val label: String, val icon: ImageVector) {
    ACTIONS("Actions", Icons.Filled.AutoFixHigh),
    PRESETS("Presets", Icons.Filled.Palette),
    CROP("Crop", Icons.Filled.Crop),
    EDIT("Edit", Icons.Filled.Tune),
    MASKING("Masking", Icons.Filled.Brush),
    REMOVE("Remove", Icons.Filled.Delete),
}

/**
 * Prepare a picture before it becomes cutting paths: Actions, Presets, Crop, Edit, Masking, Remove.
 * "Trace" sends the edited picture straight into the outline tracer.
 */
@Composable
internal fun PhotoEditorScreen(
    vm: FeatherViewModel,
    wide: Boolean,
    files: FileActions,
    onSavePng: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = vm.photo
    val s by editor.state.collectAsState()
    var tool by remember { mutableStateOf(PhotoTool.EDIT) }
    var brush by remember { mutableStateOf(0.03f) }
    var guessResult by remember { mutableStateOf<List<feather.model.MaterialGuess>?>(null) }
    var guessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun runGuess() {
        guessing = true
        try {
            val bmp = editor.exportBitmap()
            if (bmp == null) {
                guessResult = emptyList()
            } else {
                val w = bmp.width; val h = bmp.height
                val argb = IntArray(w * h)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                val gray = IntArray(argb.size) { feather.core.ImageTracer.luminance(argb[it]) }
                guessResult = feather.model.MaterialGuesser.guess(argb, w, h, gray)
            }
        } finally {
            guessing = false
        }
    }

    if (!s.hasImage) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (s.loading) {
                CircularProgressIndicator()
                Text("Opening the picture...")
            } else {
                Text("Photo editor", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Crop, brighten, paint out what you do not want and remove a plain background, then trace it into cutting paths.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ButtonRow {
                    Button(onClick = files.pickPhoto) { Text("Choose picture") }
                    OutlinedButton(onClick = files.takePhoto) { Text("Take photo") }
                    OutlinedButton(onClick = files.takeMacroPhoto) { Text("Macro photo") }
                }
                Text(
                    "Macro: for a small object. Hold the phone close (or use a clip-on macro lens), fill the frame, and light it evenly.",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (s.message.isNotBlank()) Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val header: @Composable () -> Unit = {
        ButtonRow {
            OutlinedButton(onClick = files.pickPhoto) { Text("New") }
            OutlinedButton(onClick = files.takePhoto) { Text("Camera") }
            OutlinedButton(onClick = { editor.undo() }, enabled = s.canUndo) { Text("Undo") }
            OutlinedButton(onClick = { editor.reset() }) { Text("Reset") }
            OutlinedButton(onClick = onSavePng) { Text("Save copy") }
            Button(onClick = { scope.launch { editor.exportBitmap()?.let { vm.startTraceFromBitmap(it) } } }) { Text("Trace") }
        }
    }
    val image: @Composable (Modifier) -> Unit = { m -> PhotoCanvas(s, editor, tool, brush, m) }
    val panel: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolPanel(tool, s, editor, brush) { brush = it }
            ToolBar(tool) { tool = it }
        }
    }

    if (wide) {
        Row(modifier = modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                header()
                image(Modifier.weight(1f).fillMaxWidth())
            }
            Column(modifier = Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { panel() }
        }
    } else {
        Column(modifier = modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            image(Modifier.weight(1f).fillMaxWidth())
            Box(modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) { panel() }
        }
    }

    guessResult?.let { guesses -> MaterialGuessDialog(guesses) { guessResult = null } }
}

// ---- Picture and overlays ---------------------------------------------------------------------------

@Composable
private fun PhotoCanvas(s: PhotoState, editor: PhotoEditor, tool: PhotoTool, brush: Float, modifier: Modifier) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }

    // Where the picture sits inside the canvas (letterboxed).
    val iw = s.widthPx.coerceAtLeast(1)
    val ih = s.heightPx.coerceAtLeast(1)
    val fit = if (box.width == 0) 1f else min(box.width.toFloat() / iw, box.height.toFloat() / ih)
    val dw = iw * fit
    val dh = ih * fit
    val left = (box.width - dw) / 2f
    val top = (box.height - dh) / 2f

    val currentBrush by rememberUpdatedState(brush)
    val currentLeft by rememberUpdatedState(left)
    val currentTop by rememberUpdatedState(top)
    val currentDw by rememberUpdatedState(dw)
    val currentDh by rememberUpdatedState(dh)

    Canvas(
        modifier = modifier
            .background(Color(0xFFE9F1F2), RoundedCornerShape(12.dp))
            .onSizeChanged { box = it }
            .pointerInput(tool) {
                if (tool != PhotoTool.MASKING) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> stroke.clear(); stroke.add(p) },
                    onDrag = { change, _ -> change.consume(); stroke.add(change.position) },
                    onDragEnd = {
                        val xs = FloatArray(stroke.size) { ((stroke[it].x - currentLeft) / currentDw).coerceIn(0f, 1f) }
                        val ys = FloatArray(stroke.size) { ((stroke[it].y - currentTop) / currentDh).coerceIn(0f, 1f) }
                        editor.eraseStroke(xs, ys, currentBrush)
                        stroke.clear()
                    },
                    onDragCancel = { stroke.clear() },
                )
            },
    ) {
        val img = s.preview ?: return@Canvas
        drawImage(
            image = img,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
        )
        if (tool == PhotoTool.CROP) {
            val c = s.crop
            val cl = left + c.left * dw
            val ct = top + c.top * dh
            val cr = left + c.right * dw
            val cb = top + c.bottom * dh
            val dim = Color(0x88000000)
            drawRect(dim, Offset(left, top), Size(dw, ct - top))
            drawRect(dim, Offset(left, cb), Size(dw, top + dh - cb))
            drawRect(dim, Offset(left, ct), Size(cl - left, cb - ct))
            drawRect(dim, Offset(cr, ct), Size(left + dw - cr, cb - ct))
            drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = Stroke(width = 3f))
            val third = Color(0x99FFFFFF)
            for (i in 1..2) {
                drawLine(third, Offset(cl + (cr - cl) * i / 3f, ct), Offset(cl + (cr - cl) * i / 3f, cb), strokeWidth = 1f)
                drawLine(third, Offset(cl, ct + (cb - ct) * i / 3f), Offset(cr, ct + (cb - ct) * i / 3f), strokeWidth = 1f)
            }
        }
        if (tool == PhotoTool.MASKING && stroke.isNotEmpty()) {
            val width = brush * 2f * max(dw, dh)
            val paint = Color(0x99E8590C)
            if (stroke.size == 1) {
                drawCircle(paint, radius = width / 2f, center = stroke[0])
            } else {
                for (i in 1 until stroke.size) drawLine(paint, stroke[i - 1], stroke[i], strokeWidth = width, cap = StrokeCap.Round)
            }
        }
    }
}

// ---- Bottom toolbar (Actions, Presets, Crop, Edit, Masking, Remove) -----------------------------------

@Composable
private fun ToolBar(current: PhotoTool, onPick: (PhotoTool) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PhotoTool.values().forEach { t ->
            val tint = if (t == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onPick(t) }.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(t.icon, contentDescription = t.label, tint = tint)
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

@Composable
private fun ToolPanel(tool: PhotoTool, s: PhotoState, editor: PhotoEditor, brush: Float, onBrush: (Float) -> Unit) {
    PanelCard {
        when (tool) {
            PhotoTool.ACTIONS -> {
                Text("Quick fixes", style = MaterialTheme.typography.labelLarge)
                ButtonRow {
                    Button(onClick = { editor.autoEnhance() }) { Text("Auto enhance") }
                    OutlinedButton(onClick = { editor.setAdjust { it.copy(grayscale = !it.grayscale) } }) {
                        Text(if (s.adjust.grayscale) "Gray: on" else "Gray: off")
                    }
                    OutlinedButton(onClick = { editor.setAdjust { it.copy(invert = !it.invert) } }) {
                        Text(if (s.adjust.invert) "Invert: on" else "Invert: off")
                    }
                }
                ButtonRow {
                    OutlinedButton(onClick = { editor.rotate(-90) }) { Text("Rotate left") }
                    OutlinedButton(onClick = { editor.rotate(90) }) { Text("Rotate right") }
                    OutlinedButton(onClick = { editor.flip(true) }) { Text("Flip L/R") }
                    OutlinedButton(onClick = { editor.flip(false) }) { Text("Flip U/D") }
                }
                OutlinedButton(onClick = { editor.edgeSketch() }) { Text("Pencil sketch (edges only)") }
                Text("Sketch turns a shaded photo into lines, which trace far better than filled tones.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { scope.launch { runGuess() } }, enabled = !guessing) {
                    Text(if (guessing) "Looking..." else "Guess material (beta)")
                }
            }
            PhotoTool.PRESETS -> {
                Text("Looks", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PhotoEditor.presets) { (name, adjust) ->
                        FilterChip(selected = s.adjust == adjust, onClick = { editor.applyPreset(adjust) }, label = { Text(name) })
                    }
                }
                Text(
                    "Engrave, Punch and Stencil give the high-contrast black and white that lasers and tracing like best.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PhotoTool.CROP -> {
                Text("Crop", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<Pair<String, Float?>>("Full" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
                        .forEach { (label, ratio) ->
                            item { FilterChip(selected = false, onClick = { editor.setCropAspect(ratio) }, label = { Text(label) }) }
                        }
                }
                CropSlider("Left", s.crop.left, 0f, 0.9f) { editor.setCrop(s.crop.copy(left = it)) }
                CropSlider("Right", s.crop.right, 0.1f, 1f) { editor.setCrop(s.crop.copy(right = it)) }
                CropSlider("Top", s.crop.top, 0f, 0.9f) { editor.setCrop(s.crop.copy(top = it)) }
                CropSlider("Bottom", s.crop.bottom, 0.1f, 1f) { editor.setCrop(s.crop.copy(bottom = it)) }
                Button(onClick = { editor.applyCrop() }, enabled = !s.crop.isFull) { Text("Apply crop") }
            }
            PhotoTool.EDIT -> {
                EditSlider("Brightness", s.adjust.brightness, -1f, 1f) { v -> editor.setAdjust { it.copy(brightness = v) } }
                EditSlider("Contrast", s.adjust.contrast, -1f, 1f) { v -> editor.setAdjust { it.copy(contrast = v) } }
                EditSlider("Saturation", s.adjust.saturation, -1f, 1f) { v -> editor.setAdjust { it.copy(saturation = v) } }
                EditSlider("Warmth", s.adjust.warmth, -1f, 1f) { v -> editor.setAdjust { it.copy(warmth = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hard black and white", modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.adjust.hardThreshold >= 0,
                        onCheckedChange = { on -> editor.setAdjust { it.copy(hardThreshold = if (on) 128 else -1) } },
                    )
                }
                if (s.adjust.hardThreshold >= 0) {
                    EditSlider("Cut-off level", s.adjust.hardThreshold / 255f, 0f, 1f) { v ->
                        editor.setAdjust { it.copy(hardThreshold = (v * 255f).toInt().coerceIn(0, 255)) }
                    }
                }
                TextButton(onClick = { editor.applyPreset(Adjust()) }) { Text("Reset adjustments") }
            }
            PhotoTool.MASKING -> {
                Text("Paint over anything you do not want traced. It turns white.", style = MaterialTheme.typography.bodyMedium)
                EditSlider("Brush size", brush, 0.005f, 0.12f, onBrush)
                Text("Each stroke is one Undo step.", style = MaterialTheme.typography.bodySmall)
            }
            PhotoTool.REMOVE -> {
                var tolerance by remember { mutableStateOf(40f) }
                Text("Remove a plain background", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Works from the picture's edges inward, painting every connected area of a similar colour white. Best on a plain wall or sheet of paper.",
                    style = MaterialTheme.typography.bodySmall,
                )
                EditSlider("Tolerance", tolerance, 5f, 120f) { tolerance = it }
                Button(onClick = { editor.removeBackground(tolerance.toInt()) }) { Text("Remove background") }
            }
        }
    }
}

@Composable
private fun EditSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun CropSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
    }
}
