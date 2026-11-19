package com.voltik.player

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.voltik.player.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var library: MusicLibrary
    private lateinit var playback: PlaybackController
    private lateinit var milkdrop: MilkdropCoordinator
    private var mediaPlayer: Player? = null
    private var albums by mutableStateOf<List<UiAlbum>>(emptyList())
    private var folders by mutableStateOf<List<UiFolder>>(emptyList())
    private var playerState by mutableStateOf(UiPlayer())
    private var actuallyPlaying by mutableStateOf(false)
    private var keepAwake by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var playbackMessage by mutableStateOf<String?>(null)
    private var scanJob: Job? = null
    private var tickJob: Job? = null
    private var periodicScanJob: Job? = null
    private val scanMutex = Mutex()
    private var activityActive = false
    // Picker results are delivered before onResume. The result already initiates
    // a scan, or sets an actionable error; do not immediately overwrite either.
    private var pickerResultHandled = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickerResultHandled = true
        if (uri != null) {
            if (library.addFolder(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) refresh()
            else message = "Não foi possível manter o acesso à pasta. Tente selecioná-la novamente."
        }
    }
    private val presetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) milkdrop.importPreset(uri)
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) playbackMessage = null
        }
        override fun onPlayerError(error: PlaybackException) {
            val causeName = errorCauseName(error)
            val code = error.errorCodeName
            // Do not log exception messages, track titles or SAF URIs.
            Log.e("VoltikPlayback", "Playback error: code=$code, cause=${causeName ?: "none"}")
            playbackMessage = playbackErrorMessage(code, causeName)
            updatePlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = MusicLibrary(applicationContext)
        playback = PlaybackController(applicationContext)
        milkdrop = MilkdropCoordinator(applicationContext, scope)
        milkdrop.restore()
        keepAwake = getSharedPreferences("voltik_settings", MODE_PRIVATE).getBoolean("keep_awake", false)
        applyKeepAwake()
        playback.connect(onConnected = { player ->
            mediaPlayer = player
            player.addListener(listener)
            updatePlayer()
            startTicker()
        }, onError = {
            playbackMessage = "Não foi possível iniciar o player. Feche e abra o app novamente."
        })

        setContent {
            val visual = milkdrop.state
            val visualId = visual.activePresetId
            val projectMContent: (@Composable () -> Unit)? = if (visualId == null) null else {
                {
                    val token = remember { UUID.randomUUID().toString() }
                    DisposableEffect(token) {
                        milkdrop.panelAttached(token)
                        onDispose { milkdrop.panelDisposed(token) }
                    }
                    Box(Modifier.fillMaxSize()) {
                        ProjectMPanel(
                            lifecycle = lifecycle,
                            isPlaying = actuallyPlaying,
                            pcmSnapshot = { PlaybackPcmCapture.tap.snapshot(1536) },
                            enablePcmCapture = { enabled -> PlaybackPcmCapture.tap.setEnabled(enabled) },
                            onError = { detail -> scope.launch { milkdrop.panelError(visualId, token, detail) } },
                            onGlReady = { scope.launch { milkdrop.glReady(token) } },
                            expectedPreset = milkdrop.expectedPreset,
                            activationBaseline = milkdrop.activationBaseline,
                            onPresetRendered = { id -> scope.launch { milkdrop.presetRendered(id, token) } }
                        )
                        if (visual.busy) Box(Modifier.fillMaxSize().background(Color(0xFF101115)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
            VoltikApp(albums, folders, playerState, keepAwake, scanning,
                listOfNotNull(playbackMessage, message).takeIf { it.isNotEmpty() }?.joinToString("\n"),
                UiActions(
                    addFolder = {
                        try { folderPicker.launch(null) }
                        catch (_: ActivityNotFoundException) {
                            message = "O seletor de pastas do Android não está disponível neste aparelho."
                        }
                    },
                    removeFolder = { uri -> library.removeFolder(Uri.parse(uri)); refresh() },
                    refresh = { refresh() },
                    playSong = { album, index -> startAlbum(album, index) },
                    setCompilation = { id, value -> library.setCompilation(id, value); refresh() },
                    togglePlay = {
                        mediaPlayer?.let { player ->
                            if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
                                playbackMessage = null
                                player.prepare()
                                player.play()
                            } else if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                            else {
                                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                player.play()
                            }
                        }
                        updatePlayer()
                    },
                    previous = { mediaPlayer?.seekToPreviousMediaItem() },
                    next = { mediaPlayer?.seekToNextMediaItem() },
                    seekTo = { position -> mediaPlayer?.seekTo(position.coerceAtLeast(0)) },
                    toggleShuffle = { mediaPlayer?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                    cycleRepeat = { mediaPlayer?.let { it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    } } },
                    setKeepAwake = { enabled ->
                        keepAwake = enabled
                        getSharedPreferences("voltik_settings", MODE_PRIVATE).edit().putBoolean("keep_awake", enabled).apply()
                        applyKeepAwake()
                    },
                    loadArtwork = { uris ->
                        withContext(Dispatchers.IO) { library.readEmbeddedArtwork(uris.map(Uri::parse)) }
                    }
                ),
                visualState = visual,
                visualActions = VisualUiActions(
                    importPreset = { try { presetPicker.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { milkdrop.importUnavailable() } },
                    chooseVoltik = milkdrop::chooseVoltik,
                    choosePreset = milkdrop::choosePreset
                ),
                projectMContent = projectMContent
            )
        }
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        startTicker()
        if (::library.isInitialized) {
            if (pickerResultHandled) pickerResultHandled = false else refresh()
            periodicScanJob?.cancel()
            periodicScanJob = scope.launch {
                while (isActive) {
                    delay(300_000)
                    if (!scanning && folders.isNotEmpty()) refresh()
                }
            }
        }
    }

    override fun onPause() {
        activityActive = false
        tickJob?.cancel()
        tickJob = null
        periodicScanJob?.cancel()
        periodicScanJob = null
        super.onPause()
    }

    private fun refresh() {
        scanJob?.cancel()
        folders = library.folders().map { UiFolder(it.toString(), folderLabel(it)) }
        if (folders.isEmpty()) {
            albums = emptyList()
            scanning = false
            message = null
            return
        }
        scanning = true
        message = null
        scanJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { scanMutex.withLock { library.scan() } }
                albums = result.albums.map { album ->
                    UiAlbum(album.id, album.title, album.artist, album.songs.map { song ->
                        UiSong(song.uri.toString(), song.title, song.artist, song.durationMs)
                    }, album.isCompilation)
                }
                message = if (result.inaccessibleFolders.isNotEmpty())
                    "Não foi possível acessar ${result.inaccessibleFolders.size} pasta(s). Remova e adicione novamente nas configurações."
                else if (result.songs.isEmpty()) "Nenhum arquivo MP3 encontrado nas pastas selecionadas." else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Não foi possível atualizar a biblioteca. Confira suas pastas."
            } finally {
                if (isActive) scanning = false
            }
        }
    }

    private fun startAlbum(album: UiAlbum, index: Int) {
        if (mediaPlayer == null) { playbackMessage = "O player ainda está iniciando. Tente novamente."; return }
        try {
            playbackMessage = null
            playback.playAlbum(album.songs.map { PlaybackTrack(it.uri, it.title, it.artist, album.title) }, index)
            updatePlayer()
        } catch (e: Exception) {
            val causeName = e.javaClass.simpleName
            Log.e("VoltikPlayback", "Playback setup error: cause=$causeName")
            playbackMessage = playbackErrorMessage("ERRO_AO_INICIAR", causeName)
        }
    }

    private fun errorCauseName(error: Throwable): String? {
        val causes = generateSequence(error.cause) { it.cause }.take(12).toList()
        return (causes.firstOrNull { it is SecurityException } ?: causes.lastOrNull())?.javaClass?.simpleName
    }

    private fun updatePlayer() {
        val p = mediaPlayer ?: return
        actuallyPlaying = p.isPlaying
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        playerState = UiPlayer(
            title = p.mediaMetadata.title?.toString().orEmpty(),
            artist = p.mediaMetadata.artist?.toString().orEmpty(),
            durationMs = duration, positionMs = p.currentPosition.coerceAtLeast(0),
            playing = p.playerError == null && p.playWhenReady &&
                p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            audioLevel = if (p.isPlaying) PlaybackAudioLevel.value else 0f
        )
    }

    private fun startTicker() {
        if (!activityActive || mediaPlayer == null || tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                updatePlayer()
                delay(200)
            }
        }
    }

    private fun applyKeepAwake() {
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun folderLabel(uri: Uri): String = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast(':').ifBlank { "Pasta de músicas" }

    override fun onDestroy() {
        scanJob?.cancel()
        tickJob?.cancel()
        mediaPlayer?.removeListener(listener)
        playback.close()
        scope.cancel()
        super.onDestroy()
    }
}
