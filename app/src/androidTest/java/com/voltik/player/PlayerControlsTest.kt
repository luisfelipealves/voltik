package com.voltik.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voltik.player.ui.UiActions
import com.voltik.player.ui.UiAlbum
import com.voltik.player.ui.UiFolder
import com.voltik.player.ui.UiPlayer
import com.voltik.player.ui.UiSong
import com.voltik.player.ui.VoltikApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    @get:Rule val compose = createComposeRule()
    private val album = UiAlbum("test", "Album", "Artist", listOf(UiSong("content://test/1", "Track", "Artist")), false)
    private val actions = UiActions(
        addFolder = {}, removeFolder = {}, refresh = {}, playSong = { _, _ -> }, setCompilation = { _, _ -> },
        togglePlay = {}, previous = {}, next = {}, seekTo = {}, toggleShuffle = {},
        cycleRepeat = {}, setKeepAwake = {}
    )

    @Test fun controlsHideWhilePlayingAndTouchRevealsThemButPauseKeepsThem() {
        val player = mutableStateOf(UiPlayer(title = "Track", artist = "Artist", playing = true))
        compose.mainClock.autoAdvance = false
        compose.setContent { VoltikApp(listOf(album), listOf(UiFolder("folder", "Music")), player.value, false, false, null, actions) }
        compose.onNodeWithTag("album-test").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("album-play").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6500)
        compose.onNodeWithTag("player-screen").assertDoesNotExist()
        compose.onNodeWithTag("visual-surface").performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
        compose.runOnIdle { player.value = player.value.copy(playing = false) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(7000)
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
    }
}

