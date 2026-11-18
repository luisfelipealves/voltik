package com.voltik.player

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voltik.player.ui.UiActions
import com.voltik.player.ui.UiAlbum
import com.voltik.player.ui.UiFolder
import com.voltik.player.ui.UiPlayer
import com.voltik.player.ui.UiSong
import com.voltik.player.ui.VoltikApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoltikNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val album = UiAlbum(
        id = "album-test", title = "Um álbum", artist = "Um artista",
        songs = listOf(UiSong("content://test/1", "Primeira", "Um artista"),
            UiSong("content://test/2", "Segunda", "Um artista")), isCompilation = false
    )
    private var selected: Pair<String, Int>? = null
    private var folderChooserOpened = false

    private fun actions() = UiActions(
        addFolder = { folderChooserOpened = true }, removeFolder = {}, refresh = {},
        playSong = { a, index -> selected = a.id to index }, setCompilation = { _, _ -> },
        togglePlay = {}, previous = {}, next = {}, seekTo = {}, toggleShuffle = {},
        cycleRepeat = {}, setKeepAwake = {}
    )

    private fun show(albums: List<UiAlbum>, folders: List<UiFolder>) {
        compose.setContent { VoltikApp(albums, folders, UiPlayer(), false, false, null, actions()) }
    }

    @Test fun emptyLibraryOffersFolderChooser() {
        show(emptyList(), emptyList())
        compose.onNodeWithText("Selecionar pasta de músicas").performClick()
        compose.runOnIdle { assertEquals(true, folderChooserOpened) }
    }

    @Test fun albumOpenDoesNotPlayAndAlbumPlayStartsFirstTrackAndOpensPlayer() {
        show(listOf(album), listOf(UiFolder("content://folder", "Músicas")))
        compose.onNodeWithTag("album-album-test").performClick()
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("album-play").performClick()
        compose.runOnIdle { assertEquals("album-test" to 0, selected) }
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
    }

    @Test fun selectingSecondTrackPlaysItAndOpensPlayer() {
        show(listOf(album), listOf(UiFolder("content://folder", "Músicas")))
        compose.onNodeWithTag("album-album-test").performClick()
        compose.onNodeWithTag("track-1").assertExists().performClick()
        compose.runOnIdle { assertEquals("album-test" to 1, selected) }
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
    }
}
