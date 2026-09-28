@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import feather.core.GcodeAnalysis
import feather.core.Move
import feather.link.FeatherUiState
import feather.model.Axis
import kotlinx.coroutines.delay

/**
 * Plays the generated toolpath over the machine's bed: rapids in grey, cuts in colour, the tool as a dot.
 * Anything outside the bed outline is a job the controller would refuse or crash on.
 */
@Composable
internal fun SimulateScreen(state: FeatherUiState, wide: Boolean, onBack: () -> Unit) {
    val report = state.jobReport
    val moves = remember(report?.gcode) { if (report == null) emptyList<Move>() else GcodeAnalysis.parse(report.gcode) }
    // Cumulative path length at the end of each move: scrubbing maps a distance to a position.
    val cumulative = remember(moves) {
        val out = DoubleArray(moves.size + 1)
        for (i in moves.indices) out[i + 1] = out[i] + moves[i].lengthMm
        out
    }
    val total = if (cumulative.isEmpty()) 0.0 else cumulative[cumulative.size - 1]
    val profile = state.devices.activeDevice?.profile

    var progress by remember(report?.gcode) { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(4) }

    LaunchedEffect(playing, speed, total) {
        while (playing && total > 0.0) {
            delay(33)
            // Real time at 1x would be minutes; the estimate sets the pace so 1x means "about as long as the job".
            val seconds = (report?.stats?.estimatedSeconds ?: 60.0).coerceAtLeast(1.0)
            val step = (33.0 / 1000.0) * speed / seconds
            val next = progress + step.toFloat()
            if (next >= 1f) { progress = 1f; playing = false } else progress = next
        }
    }

    val canvas: @Composable (Modifier) -> Unit = { m ->
        SimCanvas(moves, cumulative, total, progress, profile?.travelMm(Axis.X) ?: 0.0, profile?.travelMm(Axis.Y) ?: 0.0, m)
    }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (report != null) ReportSummary(report)
            Slider(value = progress, onValueChange = { progress = it; playing = false })
            Text("${(progress * 100).toInt()}% of the toolpath", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (progress >= 1f) progress = 0f; playing = !playing }, enabled = total > 0.0) {
                    Text(if (playing) "Pause" else "Play")
                }
                OutlinedButton(onClick = { progress = 0f; playing = false }) { Text("Restart") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 4, 16).forEach { s -> FilterChip(selected = speed == s, onClick = { speed = s }, label = { Text("${s}x") }) }
            }
            if (profile == null) Text("No machine selected, so no bed outline is shown.", style = MaterialTheme.typography.bodySmall)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Simulate", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        if (report == null) {
            Text("Nothing to simulate. Go to Design > Toolpath and check the job first.")
        } else if (wide) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                canvas(Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
            }
        } else {
            canvas(Modifier.weight(1f).fillMaxWidth())
            controls()
        }
    }
}

@Composable
private fun SimCanvas(
    moves: List<Move>,
    cumulative: DoubleArray,
    total: Double,
    progress: Float,
    bedX: Double,
    bedY: Double,
    modifier: Modifier,
) {
    val cutColor = MaterialTheme.colorScheme.primary
    val bedColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.background(Color(0xFFFDFEFE))) {
        if (moves.isEmpty()) return@Canvas
        // World bounds: the bed plus every move, so an out-of-bounds job is visible rather than clipped.
        var minX = 0.0; var minY = 0.0
        var maxX = if (bedX > 0.0) bedX else 1.0
        var maxY = if (bedY > 0.0) bedY else 1.0
        for (mv in moves) {
            minX = minOf(minX, mv.x0, mv.x1); maxX = maxOf(maxX, mv.x0, mv.x1)
            minY = minOf(minY, mv.y0, mv.y1); maxY = maxOf(maxY, mv.y0, mv.y1)
        }
        val margin = 16f
        val w = (maxX - minX).coerceAtLeast(1.0)
        val h = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf((size.width - 2 * margin) / w, (size.height - 2 * margin) / h).toFloat()
        val ox = (size.width - (w * scale).toFloat()) / 2f
        val oy = (size.height - (h * scale).toFloat()) / 2f
        fun sx(x: Double) = ox + ((x - minX) * scale).toFloat()
        fun sy(y: Double) = size.height - oy - ((y - minY) * scale).toFloat()

        if (bedX > 0.0 && bedY > 0.0) {
            drawRect(bedColor, Offset(sx(0.0), sy(bedY)), Size((bedX * scale).toFloat(), (bedY * scale).toFloat()), style = Stroke(width = 2f))
        }

        val target = progress.toDouble() * total
        // Whole path faintly, so you can see what is still to come.
        for (mv in moves) {
            drawLine(Color(0x22000000), Offset(sx(mv.x0), sy(mv.y0)), Offset(sx(mv.x1), sy(mv.y1)), strokeWidth = 1f)
        }
        var toolX = moves[0].x0
        var toolY = moves[0].y0
        for (i in moves.indices) {
            val mv = moves[i]
            if (cumulative[i] >= target) break
            val len = mv.lengthMm
            val frac = if (len <= 0.0) 1.0 else ((target - cumulative[i]) / len).coerceIn(0.0, 1.0)
            val ex = mv.x0 + (mv.x1 - mv.x0) * frac
            val ey = mv.y0 + (mv.y1 - mv.y0) * frac
            val color = if (mv.rapid) Color(0xFF9E9E9E) else cutColor
            drawLine(color, Offset(sx(mv.x0), sy(mv.y0)), Offset(sx(ex), sy(ey)), strokeWidth = if (mv.rapid) 1f else 3f)
            toolX = ex; toolY = ey
        }
        drawCircle(Color(0xFFD32F2F), radius = 7f, center = Offset(sx(toolX), sy(toolY)))
    }
}
