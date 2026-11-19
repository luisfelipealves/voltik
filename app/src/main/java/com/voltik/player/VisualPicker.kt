package com.voltik.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Preset importado; ID opaco e nome fornecidos por MilkdropManager. */
data class VisualPreset(val id: String, val name: String)

/**
 * `activePresetId == null` é a órbita Voltik. Um ID torna o slot ProjectM
 * visível para que o host possa ativá-lo após a superfície estar pronta.
 * Isso não significa que o arquivo foi interpretado ou renderizado com êxito.
 */
data class VisualUiState(
    val activePresetId: String? = null,
    val presets: List<VisualPreset> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

data class VisualUiActions(
    val importPreset: () -> Unit,
    val chooseVoltik: () -> Unit,
    val choosePreset: (String) -> Unit
)

/**
 * A seleção e a importação são comandos dirigidos ao host. Nenhuma seleção
 * do sistema ou inicialização nativa acontece só por renderizar o seletor.
 */
@Composable
fun VisualPicker(state: VisualUiState, actions: VisualUiActions) {
    var open by remember { mutableStateOf(false) }
    val current = state.presets.firstOrNull { it.id == state.activePresetId }?.name
    TextButton(onClick = { open = true }) {
        Text(if (state.activePresetId == null) "Visual: Voltik" else "Visual: ${current ?: "preset importado"}", maxLines = 1)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Visualização") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("A compatibilidade dos arquivos .milk é experimental; alguns podem não funcionar ou aparecer com tela preta.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { actions.chooseVoltik(); open = false }) {
                        Text(if (state.activePresetId == null) "✓ Órbita Voltik" else "Órbita Voltik")
                    }
                    state.presets.forEach { preset ->
                        TextButton(enabled = !state.busy, onClick = { actions.choosePreset(preset.id); open = false }) {
                            Text(if (state.activePresetId == preset.id) "✓ ${preset.name}" else preset.name, maxLines = 2)
                        }
                    }
                    TextButton(enabled = !state.busy, onClick = { open = false; actions.importPreset() }) { Text("Importar arquivo .milk") }
                    if (state.busy) CircularProgressIndicator()
                    if (!state.message.isNullOrBlank()) Text(state.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Fechar") } }
        )
    }
}
