package com.voltik.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class LevelAudioProcessorTest {
    private fun processor(): LevelAudioProcessor = LevelAudioProcessor().also {
        it.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        it.flush()
    }

    @Test fun emptyMedia3BufferDoesNotThrowAndLeavesNoOutput() {
        val processor = processor()
        // This exact shared instance caused put(source) to target itself.
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(0, processor.output.remaining())
    }

    @Test fun pcmIsPassedThroughWithoutCorruptionAndUpdatesAmplitude() {
        val processor = processor()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(16_384.toShort()).putShort((-16_384).toShort()).flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, input.remaining())
        assertEquals(4, output.remaining())
        assertEquals(16_384, output.short.toInt())
        assertEquals(-16_384, output.short.toInt())
        assertTrue("PCM level should be updated", PlaybackAudioLevel.value > 0f)
    }
}
