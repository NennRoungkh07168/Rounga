@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import feather.link.FeatherViewModel
import feather.link.QrCodes
import feather.model.MaterialCategory
import feather.model.MaterialPreset
import kotlinx.coroutines.launch

/** Browse built-in and saved materials; tap one to set the active tool's cutting settings. */
@Composable
internal fun MaterialLibraryDialog(vm: FeatherViewModel, onDismiss: () -> Unit) {
    val list by vm.materials.all.collectAsState()
    var category by remember { mutableStateOf<MaterialCategory?>(null) }
    var showQrFor by remember { mutableStateOf<MaterialPreset?>(null) }
    val filtered = if (category == null) list else list.filter { it.category == category }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Material library") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaterialCategory.values().toList()) { c: MaterialCategory ->
                            FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c.label) })
                        }
                    }
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { m ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${m.name}, ${fmtNum(m.thicknessMm)} mm", style = MaterialTheme.typography.titleSmall)
                                        Text(m.summary(), style = MaterialTheme.typography.bodySmall)
                                        if (m.notes.isNotBlank()) Text(m.notes, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ButtonRow {
                                    Button(onClick = { vm.applyMaterial(m); onDismiss() }) { Text("Use") }
                                    OutlinedButton(onClick = { showQrFor = m }) { Text("Share QR") }
                                    if (!m.builtIn) TextButton(onClick = { vm.removeMaterial(m.id) }) { Text("Delete") }
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) Text("No materials in this category yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    showQrFor?.let { m -> QrDialog(text = vm.materialQrText(m), title = "${m.name} ${fmtNum(m.thicknessMm)} mm") { showQrFor = null } }
}

/** A generated QR code big enough to scan off the screen. */
@Composable
internal fun QrDialog(text: String, title: String, onDismiss: () -> Unit) {
    val bitmap = remember(text) { runCatching { QrCodes.generateBitmap(text) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap == null) {
                    Text("Could not generate a QR code for this.", color = MaterialTheme.colorScheme.error)
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                    )
                    Text("Scan this on another phone to load it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Launches Google's scan sheet and hands the result to [vm]. Call from a click handler; shows its own
 * progress and result dialogs. [onResult] gets the outcome message so the caller can also show it as a status line.
 */
@Composable
internal fun rememberQrScanner(vm: FeatherViewModel, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return {
        val activity = context.findActivity()
        if (activity == null) {
            onResult("Scanning isn't available here")
        } else {
            scope.launch {
                try {
                    val text = QrCodes.scan(activity)
                    if (text != null) onResult(vm.handleScannedQr(text))
                } catch (e: Exception) {
                    onResult("Could not open the scanner (${e.message})")
                }
            }
        }
    }
}

/**
 * Shows the top colour/texture guesses from MaterialGuesser, with the accuracy caveat first,
 * not as fine print at the end -- this is a rough steer, not an identification.
 */
@Composable
internal fun MaterialGuessDialog(guesses: List<feather.model.MaterialGuess>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Material guess (beta)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A rough guess from this photo's colour, texture and glare only -- not a chemical or " +
                        "composition analysis, and a camera cannot measure that. It will be wrong on painted, " +
                        "coated, or oddly lit material. Confirm by eye, then run a test cut.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (guesses.isEmpty()) {
                    Text("Could not read this picture.", color = MaterialTheme.colorScheme.error)
                } else {
                    guesses.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.category.label, style = MaterialTheme.typography.titleSmall)
                                if (g.reason.isNotBlank()) Text(g.reason, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${(g.confidence * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
