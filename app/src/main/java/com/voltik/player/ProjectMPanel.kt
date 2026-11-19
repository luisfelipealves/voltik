package com.voltik.player

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.neerdael.projectm.core.ProjectMJNI
import nl.neerdael.projectm.core.VisualizerRenderer
import nl.neerdael.projectm.core.VisualizerView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** Caller must display this only for selected MilkDrop mode, after an explicit user action. */
@Composable
fun ProjectMPanel(
    lifecycle: Lifecycle,
    isPlaying: Boolean,
    pcmSnapshot: () -> FloatArray,
    enablePcmCapture: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onGlReady: () -> Unit,
    expectedPreset: MilkdropManager.Preset?,
    activationBaseline: Long,
    onPresetRendered: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val errorCallback by rememberUpdatedState(onError)
    val snapshotCallback by rememberUpdatedState(pcmSnapshot)
    val enableCallback by rememberUpdatedState(enablePcmCapture)
    val glReadyCallback by rememberUpdatedState(onGlReady)
    val renderedCallback by rememberUpdatedState(onPresetRendered)
    val attempt = remember(context) { ProjectMViewHolder.create(context) }
    val holder = attempt.holder
    if (holder == null) {
        val error = attempt.error ?: "Visualização indisponível."
        LaunchedEffect(error) { errorCallback(error) }
        Text(error, modifier = modifier)
        return
    }

    SideEffect {
        holder.updateCallbacks(
            expectedPreset, activationBaseline,
            { glReadyCallback() }, { id -> renderedCallback(id) }, { error -> errorCallback(error) }
        )
    }

    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.view.onResume()
        onDispose {
            enableCallback(false)
            lifecycle.removeObserver(observer)
            holder.detach()
            // Engine ownership stays held until release succeeds on the GL thread. If the
            // GL thread is dead or blocked, a new renderer is refused instead of racing it.
            holder.view.onResume()
            holder.view.queueEvent { holder.releaseOnGlThread() }
            holder.view.onPause()
        }
    }
    LaunchedEffect(lifecycle, isPlaying) {
        try {
            while (isActive) {
                val feed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isPlaying
                enableCallback(feed)
                if (feed) {
                    val samples = snapshotCallback()
                    if (samples.isNotEmpty()) {
                        val wave = ByteArray(samples.size) { i ->
                            (128 + (samples[i].coerceIn(-1f, 1f) * 127f).roundToInt()).coerceIn(0, 255).toByte()
                        }
                        ProjectMJNI.addWaveform(wave, wave.size)
                    }
                }
                delay(33)
            }
        } catch (_: UnsatisfiedLinkError) {
            errorCallback("Não foi possível carregar o motor de visualização neste aparelho.")
        } catch (_: RuntimeException) {
            errorCallback("Não foi possível atualizar a visualização. Retornando ao modo Voltik.")
        } finally { enableCallback(false) }
    }
    AndroidView(factory = { holder.view }, modifier = modifier.fillMaxSize())
}

private class ProjectMViewHolder private constructor(
    val renderer: VisualizerRenderer,
    val view: VisualizerView,
) {
    private data class Expected(val name: String, val id: String, val baseline: Long, val posted: AtomicBoolean = AtomicBoolean(false))
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    @Volatile private var expected: Expected? = null
    @Volatile private var readyCallback: () -> Unit = {}
    @Volatile private var renderedCallback: (String) -> Unit = {}
    @Volatile private var errorCallback: (String) -> Unit = {}
    private val errorPosted = AtomicBoolean(false)
    private var announcedSurface = false // GL thread only
    @Volatile private var callbacksInstalled = false
    @Volatile private var surfaceReady = false
    private val surfaceGeneration = AtomicInteger(0)
    private val notifiedGeneration = AtomicInteger(-1)

    fun updateCallbacks(preset: MilkdropManager.Preset?, baseline: Long, ready: () -> Unit,
                        rendered: (String) -> Unit, onError: (String) -> Unit) {
        readyCallback = ready
        renderedCallback = rendered
        errorCallback = onError
        callbacksInstalled = true
        val target = preset?.let { "custom/${it.id}/${it.name}" }
        val current = expected
        if (target == null) expected = null
        else if (current == null || current.name != target || current.baseline != baseline)
            expected = Expected(target, preset.id, baseline)
        reportSurfaceReady()
    }

    fun detach() { alive = false; expected = null }

    private fun fail(message: String) {
        if (errorPosted.compareAndSet(false, true)) handler.post { if (alive) errorCallback(message) }
    }

    private fun reportSurfaceReady() {
        if (!callbacksInstalled || !surfaceReady) return
        val generation = surfaceGeneration.get()
        if (notifiedGeneration.getAndSet(generation) != generation) {
            handler.post { if (alive && surfaceReady && surfaceGeneration.get() == generation) readyCallback() }
        }
    }

    private val bridge = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                surfaceReady = false
                surfaceGeneration.incrementAndGet()
                announcedSurface = false
                renderer.onSurfaceCreated(gl, config)
            }
            catch (_: UnsatisfiedLinkError) { fail("Não foi possível carregar o motor de visualização.") }
            catch (_: RuntimeException) { fail("Não foi possível iniciar a visualização.") }
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                renderer.onSurfaceChanged(gl, width, height)
                if (!announcedSurface && width > 0 && height > 0 && !errorPosted.get()) {
                    announcedSurface = true
                    surfaceReady = true
                    reportSurfaceReady()
                }
            } catch (_: UnsatisfiedLinkError) { fail("Não foi possível carregar o motor de visualização.") }
              catch (_: RuntimeException) { fail("Não foi possível preparar a visualização.") }
        }
        override fun onDrawFrame(gl: GL10?) {
            if (errorPosted.get()) return
            try {
                renderer.onDrawFrame(gl)
                val pending = expected ?: return
                if (!pending.posted.get() && ProjectMJNI.getCurrentPresetName() == pending.name &&
                    ProjectMJNI.getRenderedFrameSerial() > pending.baseline && pending.posted.compareAndSet(false, true)) {
                    handler.post { if (alive && expected === pending) renderedCallback(pending.id) }
                }
            } catch (_: UnsatisfiedLinkError) { fail("Não foi possível carregar o motor de visualização.") }
              catch (_: RuntimeException) { fail("Não foi possível desenhar o preset.") }
        }
    }

    fun releaseOnGlThread() {
        // Only clear the lease after a successful native release, never on a timeout.
        try { renderer.release(); lease.set(false) } catch (_: UnsatisfiedLinkError) { } catch (_: RuntimeException) { }
    }

    data class Attempt(val holder: ProjectMViewHolder?, val error: String?)

    companion object {
        private val lease = AtomicBoolean(false)

        fun create(context: Context): Attempt {
            if (!MilkdropManager.nativeSupported()) return Attempt(null, "Preset importado indisponível nesta arquitetura.")
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if ((am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) < 0x30000)
                return Attempt(null, "Preset importado exige OpenGL ES 3.0 neste aparelho.")
            if (!lease.compareAndSet(false, true))
                return Attempt(null, "Visualização ainda está sendo encerrada. Tente novamente.")
            var glThreadMayExist = false
            return try {
                MilkdropManager.ensureEngine(context.applicationContext)
                val renderer = VisualizerRenderer(object : VisualizerRenderer.StatsListener {
                    override fun onFpsSample(fps: Float) = Unit
                    override fun onPresetChanged() = Unit
                })
                val view = VisualizerView(context)
                val holder = ProjectMViewHolder(renderer, view)
                glThreadMayExist = true
                view.start(holder.bridge)
                Attempt(holder, null)
            } catch (_: UnsatisfiedLinkError) {
                if (!glThreadMayExist) lease.set(false)
                Attempt(null, "Não foi possível carregar o motor de visualização neste aparelho.")
            } catch (_: RuntimeException) {
                if (!glThreadMayExist) lease.set(false)
                Attempt(null, "Não foi possível iniciar a visualização. Tente novamente.")
            }
        }
    }
}
