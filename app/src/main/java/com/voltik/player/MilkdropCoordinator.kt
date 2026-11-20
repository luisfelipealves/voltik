package com.voltik.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.voltik.player.ui.VisualPreset
import com.voltik.player.ui.VisualUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.neerdael.projectm.core.ProjectMJNI

/** Activity owns this coordinator and cancels [scope] on destruction. All entrypoints run on Main. */
class MilkdropCoordinator(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val manager = MilkdropManager(app)
    private val prefs = app.getSharedPreferences("visual_choice", Context.MODE_PRIVATE)
    private var generation = 0L
    private var panelToken: String? = null
    private var glIsReady = false
    private var activationJob: Job? = null
    private var timeoutJob: Job? = null
    private var importJob: Job? = null

    var state by mutableStateOf(VisualUiState())
        private set
    var expectedPreset by mutableStateOf<MilkdropManager.Preset?>(null)
        private set
    var activationBaseline by mutableStateOf(0L)
        private set

    fun restore() {
        val operation = ++generation
        scope.launch {
            try {
                val presets = withContext(Dispatchers.IO) { manager.available() }
                if (operation != generation) return@launch
                state = state.copy(presets = presets.map { VisualPreset(it.id, it.name) })
                val id = prefs.getString("preset_id", null)
                if (prefs.getString("mode", "voltik") == "milk" && id != null) {
                    if (!supported() || presets.none { it.id == id }) {
                        fallback("Visual importado indisponível. Exibindo Órbita Voltik.")
                    } else {
                        state = state.copy(activePresetId = id, busy = true,
                            message = "Visual experimental: alguns presets podem não ser compatíveis.")
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { if (operation == generation) fallback("Não foi possível carregar os presets importados.") }
        }
    }

    fun importPreset(uri: Uri) {
        if (state.busy || importJob?.isActive == true) return
        val operation = ++generation
        state = state.copy(busy = true, message = null)
        importJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { manager.import(uri) }
                val presets = withContext(Dispatchers.IO) { manager.available() }
                if (operation != generation) return@launch
                state = state.copy(presets = presets.map { VisualPreset(it.id, it.name) }, busy = false,
                    message = "Preset importado. Selecione-o para experimentar; a compatibilidade não é garantida.")
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { if (operation == generation) state = state.copy(busy = false, message = "Não foi possível importar o arquivo. Selecione um .milk válido de até 8 MB.") }
        }
    }

    fun importUnavailable() { state = state.copy(message = "O seletor de arquivos não está disponível neste aparelho.") }

    fun chooseVoltik() {
        invalidate()
        prefs.edit().putString("mode", "voltik").remove("preset_id").apply()
        state = state.copy(activePresetId = null, busy = false, message = null)
    }

    fun choosePreset(id: String) {
        if (state.presets.none { it.id == id }) { state = state.copy(message = "Preset não encontrado."); return }
        if (!supported()) { fallback("Visual importado exige aparelho ARM e OpenGL ES 3.0. Exibindo Órbita Voltik."); return }
        invalidate(preservePanel = true)
        state = state.copy(activePresetId = id, busy = true, message = "Preparando visual experimental…")
        // A new preset can use the same already-ready GL surface. Surface readiness
        // is per panel, not per preset, so a second onGlReady is not guaranteed.
        panelToken?.let { if (glIsReady) glReady(it) else armGlTimeout(it) }
    }

    /** Token is unique for a specific panel instance; it protects against callbacks from old GL views. */
    fun panelAttached(token: String) {
        if (state.activePresetId == null) return
        activationJob?.cancel(); timeoutJob?.cancel()
        panelToken = token
        glIsReady = false
        expectedPreset = null
        activationBaseline = 0L
        state = state.copy(busy = true)
        armGlTimeout(token)
    }

    fun panelDisposed(token: String) {
        if (panelToken != token) return
        panelToken = null
        glIsReady = false
        activationJob?.cancel(); timeoutJob?.cancel()
        expectedPreset = null
        if (state.activePresetId != null) state = state.copy(busy = true)
    }

    /** Called only after the GL renderer reports onSurfaceChanged with usable dimensions. */
    fun glReady(token: String) {
        val id = state.activePresetId ?: return
        if (panelToken != token || activationJob?.isActive == true) return
        glIsReady = true
        timeoutJob?.cancel()
        expectedPreset = null
        state = state.copy(busy = true, message = "Preparando visual experimental…")
        val operation = generation
        activationJob = scope.launch {
            try {
                val preset = withContext(Dispatchers.IO) { manager.available().firstOrNull { it.id == id } }
                if (preset == null) { if (current(operation, id, token)) fallback("Preset não encontrado. Exibindo Órbita Voltik."); return@launch }
                // Baseline is sampled immediately before activate, not after it.
                val result = withContext(Dispatchers.IO) {
                    val baseline = ProjectMJNI.getRenderedFrameSerial()
                    baseline to manager.activate(id)
                }
                if (!current(operation, id, token)) return@launch
                if (!result.second) { fallback("Não foi possível preparar o preset. Exibindo Órbita Voltik."); return@launch }
                activationBaseline = result.first
                expectedPreset = preset
                state = state.copy(message = "Aguardando o primeiro quadro do visual experimental…")
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(15_000)
                    if (current(operation, id, token) && state.busy)
                        fallback("O visual não respondeu a tempo. Exibindo Órbita Voltik.")
                }
            } catch (e: CancellationException) { throw e }
              catch (_: LinkageError) { if (current(operation, id, token)) fallback("Motor visual incompatível. Exibindo Órbita Voltik.") }
              catch (_: Exception) { if (current(operation, id, token)) fallback("Não foi possível iniciar o visual. Exibindo Órbita Voltik.") }
        }
    }

    /** This callback indicates a matching native frame, not full preset correctness. */
    fun presetRendered(id: String, token: String) {
        if (panelToken != token || state.activePresetId != id || expectedPreset?.id != id) return
        timeoutJob?.cancel()
        prefs.edit().putString("mode", "milk").putString("preset_id", id).apply()
        state = state.copy(busy = false, message = "Visual experimental: efeitos podem variar entre aparelhos.")
    }

    fun panelError(id: String, token: String, detail: String) {
        if (panelToken == token && state.activePresetId == id) fallback(detail)
    }

    private fun current(operation: Long, id: String, token: String) =
        generation == operation && state.activePresetId == id && panelToken == token

    private fun armGlTimeout(token: String) {
        val id = state.activePresetId ?: return
        val operation = generation
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(15_000)
            if (current(operation, id, token) && !glIsReady)
                fallback("A superfície visual não ficou pronta. Exibindo Órbita Voltik.")
        }
    }

    private fun invalidate(preservePanel: Boolean = false) {
        generation++
        if (!preservePanel) { panelToken = null; glIsReady = false }
        activationJob?.cancel(); timeoutJob?.cancel(); importJob?.cancel()
        expectedPreset = null
    }

    private fun fallback(detail: String) {
        invalidate()
        prefs.edit().putString("mode", "voltik").remove("preset_id").apply()
        state = state.copy(activePresetId = null, busy = false, message = detail)
    }

    private fun supported(): Boolean {
        if (!MilkdropManager.nativeSupported()) return false
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
    }
}
