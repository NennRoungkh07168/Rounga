package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import feather.core.CadSketchGeometry
import feather.core.CadSketchProfile
import feather.core.CadSolid
import kotlin.math.abs
import kotlin.math.min

private enum class SketchTool { RECTANGLE, CIRCLE, LINE }

/** Touch-first 2D sketch plane. Closed profiles can be padded into the CAD solid model. */
@Composable
fun InteractiveSketcherDialog(
    onDismiss: () -> Unit,
    onCreatePad: (CadSolid.Kind, Double, Double, Double) -> Unit
) {
    var tool by remember { mutableStateOf(SketchTool.RECTANGLE) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    var profile by remember { mutableStateOf<CadSketchProfile?>(null) }
    var padHeight by remember { mutableStateOf("10") }
    var lineLength by remember { mutableStateOf<Double?>(null) }
    val height = padHeight.toDoubleOrNull()?.takeIf { it > 0.0 }
    val pxPerMm = 4f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("2D Sketcher") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Drag on the plane to draw. Grid snap: 1 mm · Units: mm", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = tool == SketchTool.RECTANGLE, onClick = { tool = SketchTool.RECTANGLE; profile = null; lineLength = null }, label = { Text("Rectangle") })
                    FilterChip(selected = tool == SketchTool.CIRCLE, onClick = { tool = SketchTool.CIRCLE; profile = null; lineLength = null }, label = { Text("Circle") })
                    FilterChip(selected = tool == SketchTool.LINE, onClick = { tool = SketchTool.LINE; profile = null; lineLength = null }, label = { Text("Line") })
                }
                Canvas(
                    Modifier.fillMaxWidth().height(250.dp)
                        .background(Color(0xFF101923), RoundedCornerShape(10.dp))
                        .pointerInput(tool) {
                            detectDragGestures(
                                onDragStart = { start = it; current = it; profile = null; lineLength = null },
                                onDrag = { change, _ -> current = change.position },
                                onDragEnd = {
                                    val a = start
                                    val b = current
                                    if (a != null && b != null) {
                                        when (tool) {
                                            SketchTool.RECTANGLE -> profile = CadSketchGeometry.rectangle(b.x-a.x, b.y-a.y, pxPerMm)
                                            SketchTool.CIRCLE -> profile = CadSketchGeometry.circle(b.x-a.x, b.y-a.y, pxPerMm)
                                            SketchTool.LINE -> lineLength = CadSketchGeometry.horizontalLineLength(b.x-a.x, pxPerMm)
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val spacing = pxPerMm * 10f
                    var x = 0f
                    while (x <= size.width) { drawLine(Color(0xFF263747), Offset(x, 0f), Offset(x, size.height), 1f); x += spacing }
                    var y = 0f
                    while (y <= size.height) { drawLine(Color(0xFF263747), Offset(0f, y), Offset(size.width, y), 1f); y += spacing }
                    drawLine(Color(0xFF2A9D8F), Offset(size.width/2, 0f), Offset(size.width/2, size.height), 2f)
                    drawLine(Color(0xFFE76F51), Offset(0f, size.height/2), Offset(size.width, size.height/2), 2f)
                    val a = start
                    val b = current
                    if (a != null && b != null) {
                        val left = min(a.x, b.x); val top = min(a.y, b.y)
                        val w = abs(a.x-b.x); val h = abs(a.y-b.y)
                        when (tool) {
                            SketchTool.RECTANGLE -> drawRect(Color(0xFF65B7D6), Offset(left, top), androidx.compose.ui.geometry.Size(w,h), style=Stroke(3f))
                            SketchTool.CIRCLE -> { val diameter = min(w,h); drawCircle(Color(0xFF65B7D6), diameter/2f, Offset(left+w/2f, top+h/2f), style=Stroke(3f)) }
                            SketchTool.LINE -> drawLine(Color(0xFFFFD166), a, b, 3f)
                        }
                    } else if (profile != null) {
                        val p = profile!!
                        val w = (p.widthMm * pxPerMm).toFloat(); val h = (p.depthMm * pxPerMm).toFloat()
                        val rect = androidx.compose.ui.geometry.Offset((size.width-w)/2, (size.height-h)/2)
                        if (p.kind == CadSolid.Kind.BOX) drawRect(Color(0xFF65B7D6), rect, androidx.compose.ui.geometry.Size(w,h), style=Stroke(3f))
                        else drawCircle(Color(0xFF65B7D6), min(w,h)/2, Offset(size.width/2,size.height/2), style=Stroke(3f))
                    }
                }
                when {
                    profile != null -> Text("Closed profile: ${profile!!.widthMm} × ${profile!!.depthMm} mm · horizontal/vertical edges constrained", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)
                    lineLength != null -> Text("Construction line: ${lineLength} mm · open profiles cannot be padded", style=MaterialTheme.typography.bodySmall)
                    else -> Text("Draw a rectangle or circle. Rectangle edges are horizontal/vertical; circle has equal radii.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value=padHeight, onValueChange={padHeight=it.filter { c -> c.isDigit() || c=='.' }}, label={Text("Pad height (mm)")}, singleLine=true)
                Text("Sketch constraints in this milestone: 1 mm grid snap, rectangle horizontal/vertical edges, and equal-radius circle. Full solver constraints (coincident, tangent, parallel, dimensional driving) are not yet included.", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled=profile != null && height != null, onClick={ val p=profile!!; onCreatePad(p.kind,p.widthMm,p.depthMm,height!!) }) { Text("Create Pad") }
        },
        dismissButton = { TextButton(onClick=onDismiss) { Text("Close") } }
    )
}
