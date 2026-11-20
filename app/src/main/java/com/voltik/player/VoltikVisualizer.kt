package com.voltik.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import com.voltik.player.PlaybackPcmCapture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Voltik's built-in visualization; [audioLevel] is the player's actual RMS level, 0..1. */
@Composable
fun VoltikVisualizer(audioLevel: Float, playing: Boolean, modifier: Modifier = Modifier) {
    // The level arrives less frequently than display frames; interpolate to avoid visible steps.
    val level by animateFloatAsState(
        targetValue = if (playing && audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(70), label = "audio level"
    )
    var time by remember { mutableFloatStateOf(0f) }
    var waveform by remember { mutableStateOf(FloatArray(0)) }
    DisposableEffect(playing) {
        PlaybackPcmCapture.tap.setEnabled(playing)
        onDispose { PlaybackPcmCapture.tap.setEnabled(false) }
    }
    LaunchedEffect(playing) {
        if (playing) {
            var lastFrame = 0L
            while (true) {
                withFrameNanos { now ->
                    if (lastFrame != 0L) time += ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.1f)
                    lastFrame = now
                    waveform = PlaybackPcmCapture.tap.snapshot(1024)
                }
                delay(25)
            }
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val unit = min(size.width, size.height)
        if (unit <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val strength = kotlin.math.sqrt(level)
        val lime = Color(0xFFC4F36A)
        val cyan = Color(0xFF83BFC3)
        val white = Color(0xFFD8D8DA)
        // Fine concentric traces provide depth without gradients or a solid filled disc.
        for (ring in 0 until 3) {
            val path = Path()
            val radius = unit * (0.18f + ring * 0.042f + strength * 0.075f)
            for (point in 0..160) {
                val angle = 2f * PI.toFloat() * point / 160f
                // Mirror the captured audio across the axes so the orbit stays balanced.
                val drift = cos(angle * (4 + ring * 2)) * sin(time * (0.55f + ring * 0.13f)) * (0.008f + strength * 0.035f)
                val ripple = cos(angle * (8 + ring * 2)) * sin(time * 0.9f) * strength * 0.018f
                val position = kotlin.math.abs(cos(angle))
                val sample = if (playing && waveform.isNotEmpty()) waveform[(position * (waveform.size - 1)).toInt().coerceIn(0, waveform.size - 1)] else 0f
                val soundWave = kotlin.math.tanh(sample * 3.5f) * (if (ring == 1) 0.14f else 0.075f)
                val r = (radius + unit * (drift + ripple + soundWave)).coerceIn(unit * 0.10f, unit * 0.46f)
                val x = center.x + r * cos(angle)
                val y = center.y + r * sin(angle)
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, when (ring) { 0 -> white; 1 -> lime; else -> cyan },
                alpha = when (ring) { 0 -> 0.72f; 1 -> 0.92f; else -> 0.6f },
                style = Stroke(width = unit * if (ring == 1) 0.004f else 0.0025f))
        }
        // Small radial marks create a restrained sense of motion around the central form.
        for (i in 0 until 48) {
            val angle = 2f * PI.toFloat() * i / 48f + time * 0.025f
            val wave = (sin(i * 1.7f + time * 1.1f) + 1f) / 2f
            val inner = unit * 0.34f
            val length = unit * (0.005f + strength * 0.040f * wave)
            val direction = Offset(cos(angle), sin(angle))
            drawLine(if (i % 4 == 0) lime else cyan,
                center + direction * inner, center + direction * (inner + length),
                alpha = if (i % 4 == 0) 0.8f else 0.35f,
                strokeWidth = unit * 0.003f, cap = StrokeCap.Round)
        }
        drawCircle(lime, radius = unit * (0.006f + strength * 0.004f), center = center, alpha = 0.9f)
    }
}
