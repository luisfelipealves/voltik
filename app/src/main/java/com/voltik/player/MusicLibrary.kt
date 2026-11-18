package com.voltik.player

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.Locale

/** Local-only library. Folder access is granted by ACTION_OPEN_DOCUMENT_TREE.
 * Call [scan] on a background thread. This class never modifies music files. */
class MusicLibrary(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("voltik_library", Context.MODE_PRIVATE)

    data class Song(
        val uri: Uri,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val track: Int,
        val durationMs: Long,
        val folderId: String,
        val taggedCompilation: Boolean,
    )

    data class Album(
        val id: String,
        val title: String,
        val artist: String,
        val songs: List<Song>,
        val isCompilation: Boolean,
    )

    data class ScanResult(
        val songs: List<Song>,
        val albums: List<Album>,
        val inaccessibleFolders: List<Uri>,
    )

    fun folders(): List<Uri> = prefs.getStringSet("folders", emptySet()).orEmpty()
        .sorted().map(Uri::parse)

    /** Returns false if Android did not allow persistent read access. */
    fun addFolder(uri: Uri, flags: Int): Boolean {
        val grantFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (grantFlags == 0) return false
        return try {
            resolver.takePersistableUriPermission(uri, grantFlags)
            prefs.edit().putStringSet("folders", folders().map { it.toString() }.toSet() + uri.toString()).commit()
        } catch (_: SecurityException) {
            false
        }
    }

    fun removeFolder(uri: Uri) {
        prefs.edit().putStringSet("folders", folders().map { it.toString() }.toSet() - uri.toString()).apply()
        try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
    }

    /** Read the first embedded image in an album; run on Dispatchers.IO.
     * Callers should cache decoded, downsampled thumbnails by album ID.
     * A missing/broken image on one track must not hide images on later tracks. */
    fun readEmbeddedArtwork(uris: List<Uri>): ByteArray? {
        return firstValidArtwork(uris, 8 * 1024 * 1024) { uri ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                val data = retriever.embeddedPicture
                if (data == null || data.isEmpty() || data.size > 8 * 1024 * 1024) null
                else {
                    // Validate headers without allocating the decoded bitmap, so
                    // a corrupt first cover does not hide a valid later cover.
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    if (options.outWidth > 0 && options.outHeight > 0) data else null
                }
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) { }
            }
        }
    }

    /** null discards the user override and returns to metadata-based detection. */
    fun setCompilation(albumId: String, value: Boolean?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("compilation:$albumId")
        else editor.putBoolean("compilation:$albumId", value)
        editor.apply()
    }

    fun scan(): ScanResult {
        val found = mutableListOf<Song>()
        val inaccessible = mutableListOf<Uri>()
        val visitedDocs = mutableSetOf<String>()
        for (tree in folders()) {
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                // Commit an entire root only after the walk succeeds. Otherwise a
                // failed subfolder can leave partial results and poison deduplication
                // of another selected root that overlaps this one.
                val rootSongs = mutableListOf<Song>()
                val rootVisited = visitedDocs.toMutableSet()
                readFolder(tree, rootId, rootSongs, rootVisited)
                found.addAll(rootSongs)
                visitedDocs.addAll(rootVisited)
            } catch (_: Exception) {
                inaccessible.add(tree)
            }
        }
        // Album artist distinguishes same-titled albums sharing one folder. A
        // missing album artist joins the sole explicit album artist in that folder;
        // ambiguous metadata stays in its own album rather than being guessed.
        val albums = found.groupBy { song ->
            song.folderId + "\u0000" + song.album.trim().lowercase(Locale.ROOT)
        }.flatMap { (folderAndTitle, sameTitle) ->
            val explicitArtists = sameTitle.map { it.albumArtist.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }.distinct()
            sameTitle.groupBy { song ->
                val albumArtist = song.albumArtist.trim().lowercase(Locale.ROOT)
                when {
                    albumArtist.isNotEmpty() -> albumArtist
                    explicitArtists.size == 1 -> explicitArtists.first()
                    else -> ""
                }
            }.map { (albumArtist, tracks) -> stableId(folderAndTitle + "\u0000" + albumArtist) to tracks }
        }.map { (id, tracks) ->
            val ordered = tracks.sortedWith(compareBy<Song>({ if (it.track > 0) it.track else Int.MAX_VALUE }, { it.title.lowercase(Locale.ROOT) }))
            val albumArtist = ordered.map { it.albumArtist.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val automatic = ordered.any { it.taggedCompilation } || isVariousArtists(albumArtist)
            val compilation = if (prefs.contains("compilation:$id")) prefs.getBoolean("compilation:$id", automatic) else automatic
            val artists = ordered.map { it.artist }.filter { it != UNKNOWN_ARTIST }.distinct()
            val artist = if (compilation) "Vários artistas" else albumArtist.ifBlank {
                if (artists.size == 1) artists.first() else UNKNOWN_ARTIST
            }
            Album(id, ordered.first().album, artist, ordered, compilation)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return ScanResult(found, albums, inaccessible)
    }

    private fun readFolder(tree: Uri, root: String, result: MutableList<Song>, visited: MutableSet<String>) {
        val pending = ArrayDeque<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, projection, null, null, null) ?: throw IllegalStateException("Folder unavailable")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (it.moveToNext()) {
                    val id = it.getString(idIndex) ?: continue
                    val name = it.getString(nameIndex).orEmpty()
                    val mime = it.getString(mimeIndex).orEmpty()
                    val key = tree.authority.orEmpty() + ":" + id
                    if (!visited.add(key)) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { pending.add(id); continue }
                    if (!name.endsWith(".mp3", ignoreCase = true) && mime != "audio/mpeg" && mime != "audio/mp3") continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val folderKey = tree.authority.orEmpty() + ":" + folder
                    readSong(uri, name, folderKey)?.let(result::add)
                }
            }
        }
    }

    private fun readSong(uri: Uri, filename: String, folderId: String): Song? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            fun meta(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
            val track = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0
            val title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { filename.substringBeforeLast('.', filename) }
            val compilation = meta(MediaMetadataRetriever.METADATA_KEY_COMPILATION).lowercase(Locale.ROOT) in setOf("1", "true", "yes", "sim")
            return Song(uri, title, meta(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { UNKNOWN_ARTIST },
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { UNKNOWN_ALBUM },
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), track,
                meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L, folderId, compilation)
        } catch (e: SecurityException) {
            // Do not silently report an empty library if the SAF grant is gone.
            throw e
        } catch (_: Exception) {
            // Tag extraction can fail for a file the player can nevertheless
            // read. Keep the track so a metadata problem does not hide music.
            val title = filename.substringBeforeLast('.', filename).ifBlank { "Faixa sem título" }
            return Song(uri, title, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", 0, 0L, folderId, false)
        } finally { try { retriever.release() } catch (_: Exception) {} }
    }

    private fun isVariousArtists(text: String): Boolean = text.trim().lowercase(Locale.ROOT) in setOf("various artists", "various", "vários artistas", "varios artistas", "va", "v.a.")
    private fun stableId(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val UNKNOWN_ARTIST = "Artista desconhecido"
        const val UNKNOWN_ALBUM = "Álbum desconhecido"
    }
}
