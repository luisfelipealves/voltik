package com.voltik.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MusicLibraryCacheTest {
    private fun isolatedContext(): Context {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(original.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    @Test fun roundTripPreservesTrackMetadataAndSeparateSelectedRoots() {
        val context = isolatedContext()
        val cache = MusicLibraryCache(context)
        val root = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val songUri = DocumentsContract.buildDocumentUriUsingTree(root, "primary:Music/Album/track.mp3")
        val song = MusicLibrary.Song(songUri, "Título", "Artista", "Álbum", "Vários artistas", 3, 123456L, "folder", true)
        val roots = setOf(root.toString(), "content://other/tree/secondary")
        assertTrue(cache.save(roots, listOf(song)))
        val restored = MusicLibraryCache(context).load()!!
        assertEquals(roots, restored.roots)
        assertEquals(listOf(song), restored.songs)
        assertEquals(MusicLibraryCache.treeIdentity(root), MusicLibraryCache.treeIdentity(songUri))
        context.filesDir.deleteRecursively()
    }

    @Test fun emptyLibrarySnapshotIsDifferentFromAbsentOrCorruptSnapshot() {
        val context = isolatedContext()
        val cache = MusicLibraryCache(context)
        assertNull(cache.load())
        assertTrue(cache.save(setOf("content://root/tree/root"), emptyList()))
        assertEquals(emptyList<MusicLibrary.Song>(), cache.load()!!.songs)
        File(context.filesDir, "music-library-v1.json").writeText("{broken")
        assertNull(cache.load())
        context.filesDir.deleteRecursively()
    }

    @Test fun reloadGroupsAlbumsAndAppliesCompilationOverrideWithoutRescanning() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(original.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val prefsName = "library-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                original.getSharedPreferences(prefsName, mode)
        }
        val root = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val songUri = DocumentsContract.buildDocumentUriUsingTree(root, "primary:Music/Mix/01.mp3")
        val song = MusicLibrary.Song(songUri, "Title", "One Artist", "Mix", "Various Artists", 1, 1000L, "folder", true)
        context.getSharedPreferences("voltik_library", Context.MODE_PRIVATE).edit()
            .putStringSet("folders", setOf(root.toString())).commit()
        assertTrue(MusicLibraryCache(context).save(setOf(root.toString()), listOf(song)))

        val library = MusicLibrary(context)
        val restored = library.loadCached()!!
        assertTrue(restored.coversAllFolders)
        assertEquals(song, restored.result.songs.single())
        val album = restored.result.albums.single()
        assertTrue(album.isCompilation)
        library.setCompilation(album.id, false)
        assertFalse(library.loadCached()!!.result.albums.single().isCompilation)

        original.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }

    @Test fun removedFoldersAreExcludedAndFolderChangesInvalidateCompleteness() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(original.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val prefsName = "library-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                original.getSharedPreferences(prefsName, mode)
        }
        val a = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val b = Uri.parse("content://com.android.externalstorage.documents/tree/secondary%3AMusic")
        fun song(root: Uri, documentId: String) = MusicLibrary.Song(
            DocumentsContract.buildDocumentUriUsingTree(root, documentId), "Song", "Artist",
            "Album", "Artist", 1, 1000L, documentId, false
        )
        val first = song(a, "primary:Music/a.mp3")
        val second = song(b, "secondary:Music/b.mp3")
        assertTrue(MusicLibraryCache(context).save(setOf(a.toString(), b.toString()), listOf(first, second)))
        context.getSharedPreferences("voltik_library", Context.MODE_PRIVATE).edit()
            .putStringSet("folders", setOf(a.toString())).commit()

        val restored = MusicLibrary(context).loadCached()!!
        assertEquals(listOf(first), restored.result.songs)
        assertFalse(restored.coversAllFolders)
        original.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }
}
