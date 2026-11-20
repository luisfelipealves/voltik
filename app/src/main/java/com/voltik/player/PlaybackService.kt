package com.voltik.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** The most recent RMS amplitude from this player's decoded PCM, 0..1. */
object PlaybackAudioLevel {
    @Volatile var value: Float = 0f
        private set
    internal fun update(newValue: Float) { value = newValue.coerceIn(0f, 1f) }
}

/** PCM tap: off unless a visible visualizer opts in; no Activity retained. */
object PlaybackPcmCapture {
    @JvmField val tap = PcmCapture()
}

@OptIn(UnstableApi::class)
internal class LevelAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 may call queueInput(EMPTY_BUFFER) while draining. Before any
        // data has been processed, replaceOutputBuffer(0) returns the same
        // shared EMPTY_BUFFER and put(inputBuffer) would copy a buffer to itself.
        if (!inputBuffer.hasRemaining()) return
        // This is an observational tap. A visualization bug must not stop audio.
        try {
            PlaybackPcmCapture.tap.accept(inputBuffer, inputAudioFormat.channelCount)
        } catch (_: RuntimeException) {
            PlaybackPcmCapture.tap.setEnabled(false)
        }
        val sampleBuffer = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0
        while (sampleBuffer.remaining() >= 2) {
            val sample = sampleBuffer.short.toDouble() / 32768.0
            sum += sample * sample
            count++
        }
        if (count > 0) PlaybackAudioLevel.update(sqrt(sum / count).toFloat())
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        PlaybackAudioLevel.update(0f)
        PlaybackPcmCapture.tap.clear()
        PlaybackPcmCapture.tap.setSampleRateHz(inputAudioFormat.sampleRate)
    }
    override fun onReset() {
        PlaybackAudioLevel.update(0f)
        PlaybackPcmCapture.tap.clear()
        PlaybackPcmCapture.tap.setSampleRateHz(0)
    }
}

/** Owns the player and media session so playback continues when the UI closes. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(LevelAudioProcessor()))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        val player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // ExoPlayer's public stop() abandons audio focus. Defer to
                    // allow the original error to reach the media controller.
                    Handler(Looper.getMainLooper()).post {
                        if (playerError != null) stop()
                    }
                }
            })
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        PlaybackAudioLevel.update(0f)
        PlaybackPcmCapture.tap.setEnabled(false)
        PlaybackPcmCapture.tap.clear()
        super.onDestroy()
    }
}
