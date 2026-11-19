package com.voltik.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LevelAudioPcmInstrumentedTest {
    private lateinit var processor: LevelAudioProcessor

    @Before fun setup() {
        PlaybackPcmCapture.tap.setEnabled(false)
        processor = LevelAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
    }

    @After fun teardown() {
        PlaybackPcmCapture.tap.setEnabled(false)
        processor.reset()
    }

    @Test fun emptyInputDoesNotThrowWhenCaptureIsEnabled() {
        PlaybackPcmCapture.tap.setEnabled(true)
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        assertEquals(0, PlaybackPcmCapture.tap.snapshot(10).size)
    }

    @Test fun stereoCapturePreservesEveryOutputByteAndPosition() {
        PlaybackPcmCapture.tap.setEnabled(true)
        val expected = byteArrayOf(0, 64, 0, 64, 0, -64, 0, -64)
        val input = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN)
        input.put(expected).flip()
        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining())
        output.get(actual)
        assertArrayEquals(expected, actual)
        assertEquals(0, input.remaining())
        assertArrayEquals(floatArrayOf(.5f, -.5f), PlaybackPcmCapture.tap.snapshot(10), 0.0001f)
        assertEquals(44100, PlaybackPcmCapture.tap.sampleRateHz)
    }

    @Test fun disabledCaptureStillPassesAudioAndDoesNotAccumulateSamples() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val input = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN)
        input.put(expected).flip()
        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining())
        output.get(actual)
        assertArrayEquals(expected, actual)
        assertEquals(0, PlaybackPcmCapture.tap.snapshot(10).size)
    }

    @Test fun flushDropsStalePcmAndKeepsCaptureWorking() {
        PlaybackPcmCapture.tap.setEnabled(true)
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(8192).putShort(8192).flip()
        processor.queueInput(input)
        assertEquals(1, PlaybackPcmCapture.tap.snapshot(10).size)
        processor.flush()
        assertEquals(0, PlaybackPcmCapture.tap.snapshot(10).size)
        val next = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        next.putShort(16384).putShort(16384).flip()
        processor.queueInput(next)
        assertArrayEquals(floatArrayOf(.5f), PlaybackPcmCapture.tap.snapshot(10), 0.0001f)
    }
}
