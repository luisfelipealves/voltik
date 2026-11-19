package com.voltik.player

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test. CI must first place a real tagged MP3 at
 * /sdcard/Music/VoltikSafFixture/fixture.mp3, on a fresh English emulator.
 * This drives the *real* DocumentsUI folder chooser and persists its grant.
 * Never run this fixture-creating workflow against a user's physical phone.
 */
@RunWith(AndroidJUnit4::class)
class SafPlaybackInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context get() = instrumentation.targetContext

    @Test fun realSafMp3PlaysDirectlyAndThroughVoltikService() {
        // The helper workflow provisions a disposable Android emulator only.
        assumeTrue("Use only the disposable emulator fixture", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk_gphone"))

        compose.onNodeWithText("Selecionar pasta de músicas").performClick()
        selectFixtureDirectory()

        // Use the app's real persisted URI and scanner, not a fabricated content URI.
        var album: MusicLibrary.Album? = null
        val library = MusicLibrary(context)
        val until = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < until && album == null) {
            album = library.scan().albums.firstOrNull { it.title == "SAF Album" }
            if (album == null) SystemClock.sleep(250)
        }
        assertNotNull("SAF Album was not indexed from the granted tree; folders=${library.folders()}", album)
        assertTrue("Folder access must be persisted", context.contentResolver.persistedUriPermissions.any { it.isReadPermission && library.folders().contains(it.uri) })
        val song = album!!.songs.single { it.title == "Voltik SAF song" }
        assertTrue("Expected real SAF content URI, got ${song.uri}", song.uri.scheme == "content")

        // Isolate file/grant/codec problems from the custom PCM processor.
        val directError = AtomicReference<String?>(null)
        lateinit var direct: ExoPlayer
        instrumentation.runOnMainSync {
            direct = ExoPlayer.Builder(context).build()
            direct.addListener(errorListener("direct", directError))
            direct.setMediaItem(MediaItem.fromUri(song.uri))
            direct.prepare()
            direct.play()
        }
        try { assertAdvances("Direct ExoPlayer", { direct }, directError) }
        finally { instrumentation.runOnMainSync { direct.release() } }

        // Now drive the exact app flow and verify the MediaSessionService player.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("SAF Album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("SAF Album").performClick()
        compose.onNodeWithText("Voltik SAF song").performClick()
        compose.onNodeWithText("Tocando agora").assertExists()

        val serviceError = AtomicReference<String?>(null)
        val connected = CountDownLatch(1)
        val servicePlayer = AtomicReference<Player?>()
        lateinit var controller: PlaybackController
        instrumentation.runOnMainSync {
            controller = PlaybackController(context)
            controller.connect(onConnected = { player ->
                servicePlayer.set(player)
                player.addListener(errorListener("service", serviceError))
                connected.countDown()
            }, onError = { error ->
                serviceError.set("controller connection: ${error.javaClass.name}: ${error.message}")
                connected.countDown()
            })
        }
        try {
            assertTrue("MediaSessionService did not connect", connected.await(15, TimeUnit.SECONDS))
            assertNotNull("MediaSessionService connect error: ${serviceError.get()}", servicePlayer.get())
            assertAdvances("Voltik service with PCM processor", { servicePlayer.get()!! }, serviceError)
        } finally { instrumentation.runOnMainSync { controller.close() } }
    }

    private fun errorListener(label: String, error: AtomicReference<String?>) = object : Player.Listener {
        override fun onPlayerError(exception: PlaybackException) {
            error.set("$label code=${exception.errorCodeName} (${exception.errorCode}), cause=${exception.cause?.javaClass?.name}: ${exception.cause?.message}, exception=${exception.message}")
            Log.e("VoltikSafTest", error.get().orEmpty(), exception)
        }
    }

    private fun assertAdvances(label: String, player: () -> Player, error: AtomicReference<String?>) {
        val until = SystemClock.elapsedRealtime() + 15_000
        var playing = false
        var position = 0L
        while (SystemClock.elapsedRealtime() < until && error.get() == null) {
            instrumentation.runOnMainSync {
                playing = player().isPlaying
                position = player().currentPosition
                player().playerError?.let { exception ->
                    error.set("$label code=${exception.errorCodeName} (${exception.errorCode}), cause=${exception.cause?.javaClass?.name}: ${exception.cause?.message}, exception=${exception.message}")
                }
            }
            if (playing && position > 250) return
            SystemClock.sleep(100)
        }
        assertTrue("$label failed to advance: playing=$playing position=$position error=${error.get()}", false)
    }

    private fun selectFixtureDirectory() {
        val timeout = 15_000L
        if (device.wait(Until.hasObject(By.text("Music")), 3_000)) {
            device.findObject(By.text("Music")).click()
        } else {
            val drawer = device.wait(Until.findObject(By.descContains("Show roots")), timeout)
                ?: device.findObject(By.descContains("navigation drawer"))
                ?: throw AssertionError("DocumentsUI navigation drawer not found")
            drawer.click()
            val storage = device.wait(Until.findObject(By.text("Internal storage")), 3_000)
                ?: device.findObject(By.textContains("sdk_gphone"))
                ?: device.findObject(By.textContains("Android SDK built for"))
                ?: throw AssertionError("Internal storage root not found")
            storage.click()
            val music = device.wait(Until.findObject(By.text("Music")), timeout)
                ?: throw AssertionError("Music directory not found")
            music.click()
        }
        val folder = device.wait(Until.findObject(By.text("VoltikSafFixture")), timeout)
            ?: throw AssertionError("VoltikSafFixture directory not found: verify adb push")
        folder.click()
        val use = device.wait(Until.findObject(By.textContains("USE THIS FOLDER")), timeout)
            ?: device.findObject(By.textContains("Use this folder"))
            ?: throw AssertionError("SAF Use this folder action not found")
        use.click()
        val allow = device.wait(Until.findObject(By.text("ALLOW")), 3_000)
            ?: device.findObject(By.text("Allow"))
        allow?.click()
        device.wait(Until.hasObject(By.pkg(context.packageName)), timeout)
    }
}
