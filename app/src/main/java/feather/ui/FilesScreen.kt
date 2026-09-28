@file:OptIn(ExperimentalMaterial3Api::class)

package feather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import feather.link.FeatherUiState
import feather.link.FeatherViewModel
import java.text.DateFormat
import java.util.Date

/** Projects, import and export, and the app's settings in one place. */
@Composable
internal fun FilesScreen(
    state: FeatherUiState,
    vm: FeatherViewModel,
    files: FileActions,
    openDialog: (Dlg) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { vm.refreshProjects() }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Files", style = MaterialTheme.typography.titleLarge)

        SectionHeader("Current drawing")
        PanelCard {
            Text(state.documentName ?: "Untitled drawing", style = MaterialTheme.typography.titleMedium)
            Text("${state.shapes.size} shape${if (state.shapes.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
            ButtonRow {
                OutlinedButton(onClick = { vm.newDocument() }) { Text("New") }
                OutlinedButton(onClick = files.open) { Text("Open...") }
                OutlinedButton(onClick = { state.documentUri?.let(vm::saveTo) }, enabled = state.documentUri != null) { Text("Save") }
                OutlinedButton(onClick = files.saveAs) { Text("Save as...") }
            }
            ButtonRow {
                Button(onClick = { openDialog(Dlg.SAVE_PROJECT) }) { Text("Save to Projects") }
                OutlinedButton(onClick = files.exportGcode, enabled = state.shapes.isNotEmpty()) { Text("Export G-code...") }
            }
            Text(
                "Open accepts .feather drawings, pictures (traced to outlines) and .gcode files (previewed in Simulate).",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionHeader("Import a picture")
        PanelCard {
            Text("Photograph a drawing or part, or pick an image, and trace it into cutting outlines.", style = MaterialTheme.typography.bodySmall)
            ButtonRow {
                Button(onClick = files.takePhoto) { Text("Take photo") }
                OutlinedButton(onClick = files.pickPhoto) { Text("Choose picture") }
            }
        }

        SectionHeader("Projects")
        if (state.projects.isEmpty()) {
            Text("No saved projects yet. Use Save to Projects to keep drawings inside the app.", style = MaterialTheme.typography.bodySmall)
        }
        state.projects.forEach { p ->
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.modifiedMs)) +
                                "  " + (p.sizeBytes / 1024 + 1) + " KB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { vm.openProject(p.path) }) { Text("Open") }
                    TextButton(onClick = { vm.deleteProject(p.path) }) { Text("Delete") }
                }
            }
        }

        SectionHeader("Settings")
        PanelCard {
            SettingRow("Show grid", state.showGrid) { vm.toggleGrid() }
            SettingRow("Snap to grid", state.snapToGrid) { vm.toggleSnap() }
            SettingRow("Full screen", state.fullscreen) { vm.setFullscreen(!state.fullscreen) }
            OutlinedButton(onClick = { openDialog(Dlg.CALIBRATE) }) { Text("Calibrate screen...") }
            Text(
                "Rotate the phone for a two-pane landscape layout: the drawing board beside its tools, and the machine's position, jog pad and console side by side.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Rounga 0.3.0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
