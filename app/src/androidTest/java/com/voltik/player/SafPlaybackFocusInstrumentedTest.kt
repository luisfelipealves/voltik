package com.voltik.player

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
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
import org.junit.Assert.assertEquals
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
class SafPlaybackFocusInstrumentedTest {
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
            // The fixture is only two seconds long; keep real playback active while
            // SystemUI publishes and the notification is inspected.
            instrumentation.runOnMainSync {
                servicePlayer.get()!!.repeatMode = Player.REPEAT_MODE_ONE
                servicePlayer.get()!!.seekTo(0)
                servicePlayer.get()!!.play()
            }
            assertAdvances("Voltik service with PCM processor", { servicePlayer.get()!! }, serviceError)
            assertActiveMediaNotification()
            verifyFocusReleasedAfterError(servicePlayer.get()!!, song.uri)
        } finally { instrumentation.runOnMainSync { controller.close() } }
    }

    /** Assert the real service publishes an OS-recognized media notification during playback. */
    private fun assertActiveMediaNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val until = SystemClock.elapsedRealtime() + 15_000
        var records = manager.activeNotifications.toList()
        fun matches(): Boolean = records.any { record ->
            val n = record.notification
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Voltik SAF song" &&
                n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
                (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        }
        while (SystemClock.elapsedRealtime() < until && !matches()) {
            SystemClock.sleep(100)
            records = manager.activeNotifications.toList()
        }
        val notificationDump = device.executeShellCommand("dumpsys notification --noredact")
        val sessionDump = device.executeShellCommand("dumpsys media_session")
        val details = records.joinToString { "id=${it.id}, title=${it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)}, mediaSession=${it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)}, flags=${it.notification.flags}" }
        assertTrue("No foreground MediaStyle notification for playing Voltik track. Active notifications: $details; notification dump: $notificationDump; media_session dump: $sessionDump", matches())
        assertTrue("Voltik package missing from notification manager dump", notificationDump.contains(context.packageName))
        assertTrue("Track title missing from media session dump", sessionDump.contains("Voltik SAF song"))
    }

    private fun verifyFocusReleasedAfterError(volTik: Player, validUri: Uri) {
        // Confirm the probe actually observed Voltik holding audio focus before
        // provoking an IO error. This prevents a vacuous pass on an empty dump.
        val before = focusStack()
        assertTrue("Voltik should hold audio focus before error; focus stack: $before", before.contains("-- pack: ${context.packageName} "))
        val gotError = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                failure.set("${exception.errorCodeName}: ${exception.cause?.javaClass?.name}: ${exception.cause?.message}")
                gotError.countDown()
            }
        }
        val unavailable = Uri.parse(validUri.toString().replace("fixture.mp3", "missing-fixture.mp3"))
        assertTrue("Fixture URI was not rewritten", unavailable != validUri)
        instrumentation.runOnMainSync {
            volTik.addListener(listener)
            volTik.setMediaItem(MediaItem.fromUri(unavailable))
            volTik.prepare()
            volTik.play()
        }
        assertTrue("Expected an actual playback error for the missing file", gotError.await(15, TimeUnit.SECONDS))
        val until = SystemClock.elapsedRealtime() + 10_000
        var after = focusStack()
        while (SystemClock.elapsedRealtime() < until && after.contains("-- pack: ${context.packageName} ")) {
            SystemClock.sleep(100)
            after = focusStack()
        }
        assertTrue("Voltik retained audio focus after ${failure.get()}; focus stack: $after", !after.contains("-- pack: ${context.packageName} "))

        // A second requester must be able to obtain focus and render a real file.
        // It has a separate AudioFocusRequest, although it shares this test UID.
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AndroidAudioAttributes.Builder().setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        assertEquals("Second requester could not obtain focus", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request))
        val competitorError = AtomicReference<String?>(null)
        lateinit var competitor: ExoPlayer
        instrumentation.runOnMainSync {
            competitor = ExoPlayer.Builder(context).build()
            competitor.addListener(errorListener("second player", competitorError))
            competitor.setMediaItem(MediaItem.fromUri(validUri))
            competitor.prepare()
            competitor.play()
        }
        try { assertAdvances("Second player after Voltik error", { competitor }, competitorError) }
        finally {
            instrumentation.runOnMainSync { competitor.release(); volTik.removeListener(listener) }
            manager.abandonAudioFocusRequest(request)
        }
    }

    private fun focusStack(): String {
        val dump = device.executeShellCommand("dumpsys audio")
        val start = dump.indexOf("Audio Focus stack entries")
        assertTrue("Audio focus stack missing from dumpsys audio", start >= 0)
        return dump.substring(start).substringBefore("\n\n")
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
