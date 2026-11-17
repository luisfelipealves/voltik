package com.voltik.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

data class PlaybackTrack(val uri: String, val title: String, val artist: String, val album: String)

/** All methods must be called on the main thread. */
class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var closed = false

    fun connect(onConnected: (Player) -> Unit, onError: (Throwable) -> Unit) {
        if (closed) { onError(IllegalStateException("Controlador fechado")); return }
        controller?.let { onConnected(it); return }
        if (future != null) { onError(IllegalStateException("Conexão em andamento")); return }
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener({
            try {
                val connected = pending.get()
                if (!closed) { controller = connected; onConnected(connected) }
            } catch (e: Exception) {
                if (!closed) { future = null; onError(e) }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun playAlbum(tracks: List<PlaybackTrack>, index: Int) {
        require(index in tracks.indices) { "Faixa selecionada inválida" }
        val player = checkNotNull(controller) { "Player ainda não conectado" }
        val items = tracks.map { track ->
            MediaItem.Builder().setUri(Uri.parse(track.uri)).setMediaId(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
                    .setArtist(track.artist).setAlbumTitle(track.album).build()).build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
    }

    fun close() {
        closed = true
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }
}
