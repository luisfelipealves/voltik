package com.voltik.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorMessageTest {
    @Test fun permissionFailureIdentifiesAccessAndShowsSafeCode() {
        val message = playbackErrorMessage("ERROR_CODE_IO_NO_PERMISSION", "SecurityException")
        assertTrue(message.contains("acesso"))
        assertTrue(message.contains("ERROR_CODE_IO_NO_PERMISSION"))
    }

    @Test fun decodeFailureSuggestsAnotherTrack() {
        val message = playbackErrorMessage("ERROR_CODE_DECODING_FAILED", "DecoderException")
        assertTrue(message.contains("decodificar"))
        assertTrue(message.contains("Tente outro MP3"))
    }

    @Test fun privatePathsAndUrisAreNotReflectedIntoMessage() {
        val secret = "content://private/music/track.mp3"
        val message = playbackErrorMessage(secret, secret)
        assertFalse(message.contains(secret))
        assertTrue(message.contains("ERRO_DESCONHECIDO"))
    }

    @Test fun audioDeviceErrorIdentifiesOutput() {
        val message = playbackErrorMessage("ERROR_CODE_AUDIO_TRACK_INIT_FAILED", null)
        assertTrue(message.contains("saída de áudio"))
        assertFalse(message.contains("null"))
    }
}
