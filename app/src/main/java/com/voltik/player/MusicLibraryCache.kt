package com.voltik.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small, versioned snapshot of discovered metadata; never stores album artwork
 * or the music itself. A replacement is atomic, so a crash cannot destroy the
 * last completed scan. Call from Dispatchers.IO.
 */
internal class MusicLibraryCache(context: Context) {
    data class Snapshot(val roots: Set<String>, val songs: List<MusicLibrary.Song>)

    private val file = AtomicFile(File(context.applicationContext.filesDir, "music-library-v1.json"))

    @Synchronized fun load(): Snapshot? {
      return try {
        if (!file.baseFile.exists()) return null
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (json.getInt("version") != 1) return null
        val rootsJson = json.getJSONArray("roots")
        val roots = (0 until rootsJson.length()).map { rootsJson.getString(it) }.toSet()
        val array = json.getJSONArray("songs")
        val songs = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            MusicLibrary.Song(
                uri = Uri.parse(item.getString("uri")),
                title = item.getString("title"),
                artist = item.getString("artist"),
                album = item.getString("album"),
                albumArtist = item.getString("albumArtist"),
                track = item.getInt("track"),
                durationMs = item.getLong("durationMs"),
                folderId = item.getString("folderId"),
                taggedCompilation = item.getBoolean("taggedCompilation"),
            )
        }
        Snapshot(roots, songs)
      } catch (_: Exception) { null }
    }

    @Synchronized fun save(roots: Set<String>, songs: List<MusicLibrary.Song>): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            val rootArray = JSONArray()
            roots.sorted().forEach { rootArray.put(it) }
            val songArray = JSONArray()
            songs.forEach { song ->
                songArray.put(JSONObject()
                    .put("uri", song.uri.toString())
                    .put("title", song.title)
                    .put("artist", song.artist)
                    .put("album", song.album)
                    .put("albumArtist", song.albumArtist)
                    .put("track", song.track)
                    .put("durationMs", song.durationMs)
                    .put("folderId", song.folderId)
                    .put("taggedCompilation", song.taggedCompilation))
            }
            val json = JSONObject().put("version", 1).put("roots", rootArray).put("songs", songArray)
            val output = file.startWrite()
            stream = output
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            stream = null
            true
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            false
        }
    }

    companion object {
        /** URI tree identity permits showing still-selected roots after a folder change. */
        fun treeIdentity(uri: Uri): String? = try {
            uri.authority.orEmpty() + ":" + DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) { null }
    }
}
