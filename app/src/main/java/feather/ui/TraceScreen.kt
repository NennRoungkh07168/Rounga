@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import feather.link.TraceState

/**
 * Photo to outlines. The preview is exactly what will be added to the drawing, at the size chosen,
 * so what you tune here is what the machine will follow.
 */
@Composable
internal fun TraceScreen(state: FeatherUiState, trace: TraceState, vm: FeatherViewModel, wide: Boolean) {
    val controls: @Composable () -> Unit = { TraceControls(state, trace, vm) }
    val preview: @Composable (Modifier) -> Unit = { m -> TracePreview(trace, m) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.cancelTrace() }) { Text("Cancel") }
            Text("Trace picture", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { vm.commitTrace() }, enabled = !trace.loading && trace.preview.isNotEmpty()) { Text("Add to drawing") }
        }
        if (trace.loading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (wide) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                preview(Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
            }
        } else {
            preview(Modifier.weight(1f).fillMaxWidth())
            Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { controls() }
        }
    }
}

@Composable
private fun TracePreview(trace: TraceState, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.background(Color.White)) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val wMm = trace.widthMm
            val hMm = trace.heightMm
            if (wMm <= 0.0 || hMm <= 0.0) return@Canvas
            val scale = minOf(size.width / wMm.toFloat(), size.height / hMm.toFloat())
            val drawW = (wMm * scale).toFloat()
            val drawH = (hMm * scale).toFloat()
            val left = (size.width - drawW) / 2f
            val top = (size.height - drawH) / 2f
            // picture frame
            drawRect(Color(0xFFE0E0E0), Offset(left, top), androidx.compose.ui.geometry.Size(drawW, drawH), style = Stroke(width = 1.5f))
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (poly in trace.preview) {
                val pts = poly.points
                if (pts.size < 2) continue
                val path = Path()
                for (i in pts.indices) {
                    val x = left + (pts[i].x.raw / 1000.0 * scale).toFloat()
                    val y = top + drawH - (pts[i].y.raw / 1000.0 * scale).toFloat() // world is Y-up
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = stroke)
            }
        }
        if (trace.working) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).width(24.dp))
        }
    }
}

@Composable
private fun TraceControls(state: FeatherUiState, trace: TraceState, vm: FeatherViewModel) {
    val profile = state.devices.activeDevice?.profile
    val bedX = profile?.travelMm(feather.model.Axis.X) ?: 0.0
    val bedY = profile?.travelMm(feather.model.Axis.Y) ?: 0.0
    var widthText by remember(trace.imageWidthPx) { mutableStateOf(fmtNum(trace.widthMm)) }
    val widthValue = parseMm(widthText)?.takeIf { it > 0.0 && it <= 5000.0 }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${trace.preview.size} outline${if (trace.preview.size == 1) "" else "s"}, ${trace.pointCount} points",
            style = MaterialTheme.typography.titleSmall,
        )
        if (trace.preview.isEmpty() && !trace.working) {
            Text("Nothing found. Move the threshold, or switch Invert if the artwork is light on dark.", style = MaterialTheme.typography.bodySmall)
        }
        if (trace.pointCount > 20_000) {
            Text("That is a lot of points and will make a long job. Raise Simplify or Ignore specks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        LiveSlider(
            label = "Threshold: ${trace.threshold}",
            value = trace.threshold.toFloat(),
            range = 0f..255f,
            onCommit = { v -> vm.updateTrace { it.copy(threshold = v.toInt().coerceIn(0, 255)) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.updateTrace { it.copy(threshold = it.autoThreshold) } }) { Text("Auto (${trace.autoThreshold})") }
            Text("Light on dark", modifier = Modifier.weight(1f).padding(start = 12.dp))
            Switch(checked = trace.invert, onCheckedChange = { on -> vm.updateTrace { it.copy(invert = on) } })
        }

        NumberField(
            label = "Width (mm)", value = widthText,
            onChange = { t ->
                widthText = t
                parseMm(t)?.takeIf { it > 0.0 && it <= 5000.0 }?.let { w -> vm.updateTrace { it.copy(widthMm = w) } }
            },
            suffix = "mm",
        )
        val fits = profile == null || (trace.widthMm <= bedX && trace.heightMm <= bedY)
        val bedText = when {
            profile == null -> ""
            fits -> ". Bed ${fmtNum(bedX)} x ${fmtNum(bedY)} mm."
            else -> ". Bed ${fmtNum(bedX)} x ${fmtNum(bedY)} mm: too big for this machine."
        }
        Text(
            "Height ${fmtNum(Math.round(trace.heightMm * 10) / 10.0)} mm$bedText",
            style = MaterialTheme.typography.bodySmall,
            color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (widthValue == null) Text("Enter a width between 0 and 5000 mm.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        LiveSlider(
            label = "Simplify: ${fmtNum(trace.simplifyUm / 1000.0)} mm",
            value = trace.simplifyUm.toFloat(),
            range = 10f..1000f,
            onCommit = { v -> vm.updateTrace { it.copy(simplifyUm = v.toLong().coerceAtLeast(10L)) } },
        )
        LiveSlider(
            label = "Ignore specks: under ${trace.despeckle} points",
            value = trace.despeckle.toFloat(),
            range = 4f..200f,
            onCommit = { v -> vm.updateTrace { it.copy(despeckle = v.toInt().coerceAtLeast(4)) } },
        )
        Text(
            "Outlines are cut along the edges of the dark areas. The result is centred on the active machine's bed when it fits.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** A slider that moves freely and recomputes only when the finger lifts. */
@Composable
private fun LiveSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onCommit(v) })
    }
}
