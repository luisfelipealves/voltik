package com.voltik.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.projectm.core.ProjectMCore
import nl.neerdael.projectm.core.ProjectMJNI
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Individual .milk presets. Originals are read only; exact bytes are copied to private storage. */
class MilkdropManager(context: Context) {
    data class Preset(val id: String, val name: String)
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "milkdrop-presets")
    private val prefs = app.getSharedPreferences("milkdrop_presets", Context.MODE_PRIVATE)
    private val maxBytes = 8 * 1024 * 1024L

    fun available(): List<Preset> = root.listFiles().orEmpty().mapNotNull { dir ->
        if (!dir.isDirectory || !validId(dir.name) || !File(dir, "0/0.milk").isFile || !File(dir, "presets.idx").isFile) null
        else Preset(dir.name, File(dir, "name.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.take(255) ?: "Preset importado")
    }.sortedBy { it.name.lowercase() }

    fun selectedId(): String? = prefs.getString("selected", null)?.takeIf(::validId)

    /** Stages and indexes exactly one file; does not alter user files. */
    suspend fun import(uri: Uri): Preset = withContext(Dispatchers.IO) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Não foi possível criar o armazenamento de presets.")
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        val subdir = File(dir, "0")
        if (!subdir.mkdirs()) throw IOException("Não foi possível preparar o preset.")
        var complete = false
        try {
            val name = displayName(uri)
            var copied = 0L
            val target = File(subdir, "0.milk")
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
            input.use { source -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(16384)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    copied += n
                    if (copied > maxBytes) throw IOException("O preset ultrapassa 8 MB.")
                    output.write(buffer, 0, n)
                }
            } }
            if (copied == 0L) throw IOException("O preset está vazio.")
            // Index uses generated paths; control characters cannot inject index entries.
            File(dir, "presets.idx").writeText("0/0.milk\t$name\n", Charsets.UTF_8)
            File(dir, "name.txt").writeText(name, Charsets.UTF_8)
            complete = true
            Preset(id, name)
        } finally { if (!complete) dir.deleteRecursively() }
    }

    /** Prepare/commit indexes metadata on the engine worker; parsing occurs later on the GL thread. */
    suspend fun activate(id: String): Boolean = withContext(Dispatchers.IO) { activationMutex.withLock {
        require(validId(id)) { "Preset inválido." }
        val dir = File(root, id)
        if (!File(dir, "0/0.milk").isFile || !File(dir, "presets.idx").isFile) return@withContext false
        ensureEngine(app)
        ProjectMJNI.setAutoChange(false)
        val token = ProjectMJNI.prepareCustomPresetPack(dir.absolutePath)
        if (token <= 0) return@withContext false
        var committed = false
        try {
            repeat(300) {
                coroutineContext.ensureActive()
                when (ProjectMJNI.getCustomPresetPackStatus(token)) {
                    1 -> {
                        if (!ProjectMJNI.commitCustomPresetPack(token)) return@withContext false
                        committed = true
                        return@withContext prefs.edit().putString("selected", id).commit()
                    }
                    -1 -> return@withContext false
                }
                delay(100)
            }
            false
        } finally { if (!committed) ProjectMJNI.discardCustomPresetPack(token) }
    } }

    private fun displayName(uri: Uri): String {
        val name = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
        val cleaned = name.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace('/', '_').replace('\\', '_').trim()
        if (cleaned.isBlank()) return "Preset importado.milk"
        if (!cleaned.endsWith(".milk", ignoreCase = true)) throw IOException("Selecione um arquivo .milk.")
        // Upstream validates the index display name too. Keep the suffix even when truncated.
        return cleaned.dropLast(5).take(155).ifBlank { "Preset importado" } + ".milk"
    }

    private fun validId(id: String): Boolean = try { UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }

    companion object {
        @Volatile private var initialized = false
        private val activationMutex = Mutex()
        @Synchronized fun ensureEngine(context: Context) {
            if (!initialized) {
                ProjectMCore.init(context.applicationContext)
                initialized = true
            }
        }
        fun nativeSupported(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }
    }
}
