package com.voltik.player

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.voltik.player.ui.UiActions
import com.voltik.player.ui.UiAlbum
import com.voltik.player.ui.UiFolder
import com.voltik.player.ui.UiPlayer
import com.voltik.player.ui.UiSong
import com.voltik.player.ui.VoltikApp
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots of production Compose UI with clearly labeled fixture media; emulator only. */
@RunWith(AndroidJUnit4::class)
class PortfolioScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureLibraryAndPlayer() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrument.targetContext
        val device = UiDevice.getInstance(instrument)
        val folder = context.getExternalFilesDir(null) ?: error("No emulator screenshot directory")
        val album = UiAlbum(
            id = "portfolio", title = "Álbum de demonstração", artist = "Artista de exemplo",
            songs = listOf(UiSong("content://portfolio/1", "Faixa de demonstração", "Artista de exemplo", 180000)),
            isCompilation = false
        )
        val player = mutableStateOf(UiPlayer())
        val actions = UiActions(
            addFolder = {}, removeFolder = {}, refresh = {},
            playSong = { _, _ -> player.value = UiPlayer(
                title = "Faixa de demonstração", artist = "Artista de exemplo",
                durationMs = 180000, positionMs = 55000, playing = true, audioLevel = 0.65f
            ) },
            setCompilation = { _, _ -> }, togglePlay = {}, previous = {}, next = {}, seekTo = {},
            toggleShuffle = {}, cycleRepeat = {}, setKeepAwake = {}
        )
        compose.setContent {
            VoltikApp(listOf(album), listOf(UiFolder("content://portfolio", "Demo")), player.value,
                false, false, null, actions)
        }
        compose.waitForIdle()
        assertTrue(device.takeScreenshot(File(folder, "voltik-library.png")))
        compose.onNodeWithTag("album-portfolio").performClick()
        compose.onNodeWithTag("album-play").performClick()
        compose.waitForIdle()
        SystemClock.sleep(600)
        assertTrue(device.takeScreenshot(File(folder, "voltik-player.png")))
    }
}
