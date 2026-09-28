package com.mushrea.code.device

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The file half of the Device Agent (spec section 28): search, open, share, delete, move, copy and
 * rename files inside the user's storage roots. Split out of [DeviceAgentBridge] to keep both files
 * focused (and inside detekt's TooManyFunctions budget).
 *
 * Path safety: every resolved file must live under one of [allowedRoots] (canonicalized); app
 * private and system paths are rejected, and ambiguous name matches ask the agent which one.
 */
class DeviceFileAgent(private val context: Context) {

    suspend fun executeSearchFiles(params: JSONObject): JSONObject.() -> Unit {
        val query = AppResolver.normalize(params.optString("query"))
        val extension = params.optString("extension").removePrefix(".").lowercase()
        val startDir = params.optString("dir").ifBlank { null }
        if (query.isBlank() && extension.isBlank()) throw DeviceAgentError("query or extension is required")
        val rootDir =
            if (startDir != null) {
                allowedRoots().firstOrNull { File(startDir).canonicalFile.path.startsWith(it.path) }
                    ?: throw DeviceAgentError("dir is outside the allowed storage roots")
                File(startDir)
            } else {
                allowedRoots().first()
            }
        val results = withContext(Dispatchers.IO) { searchFiles(rootDir, query, extension) }
        return {
            put(
                "files",
                org.json.JSONArray().apply {
                    results.forEach { f ->
                        put(
                            JSONObject()
                                .put("path", f.path)
                                .put("name", f.name)
                                .put("size", f.size),
                        )
                    }
                },
            )
            put("summary", "${results.size} matching file(s)")
        }
    }

    fun searchFiles(
        root: File,
        query: String,
        extension: String,
    ): List<FoundFile> {
        val found = ArrayList<FoundFile>()
        val queue = ArrayDeque<File>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && found.size < FILE_RESULT_LIMIT && visited < FILE_VISIT_LIMIT) {
            val dir = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                visited++
                if (visited >= FILE_VISIT_LIMIT) break
                if (child.isDirectory) {
                    if (!child.isHidden && !child.name.startsWith(".")) queue.add(child)
                    continue
                }
                val nameMatches = query.isBlank() || AppResolver.normalize(child.name).contains(query)
                val extMatches = extension.isBlank() || child.extension.lowercase() == extension
                if (nameMatches && extMatches) {
                    found += FoundFile(child.path, child.name, child.length())
                    if (found.size >= FILE_RESULT_LIMIT) break
                }
            }
        }
        return found
    }

    suspend fun executeOpenFile(params: JSONObject): JSONObject.() -> Unit {
        val file = resolveTargetFile(params)
        val uri = FileProviderUri.forFile(context, file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, guessMimeType(file))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { throw DeviceAgentError("no app can open ${file.name}: ${it.message}") }
        return { put("summary", "opened ${file.path}") }
    }

    suspend fun executeShareFile(params: JSONObject): JSONObject.() -> Unit {
        val file = resolveTargetFile(params)
        val uri = FileProviderUri.forFile(context, file)
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(guessMimeType(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { throw DeviceAgentError("share failed: ${it.message}") }
        return { put("summary", "shared ${file.name} — complete it in the target app") }
    }

    suspend fun executeDeleteFile(params: JSONObject): JSONObject.() -> Unit {
        val file = resolveTargetFile(params)
        val ok = withContext(Dispatchers.IO) { file.delete() }
        if (!ok || file.exists()) throw DeviceAgentError("could not delete ${file.path}")
        return { put("summary", "deleted ${file.path}") }
    }

    suspend fun executeMoveFile(params: JSONObject): JSONObject.() -> Unit = moveOrCopy(params, move = true)

    suspend fun executeCopyFile(params: JSONObject): JSONObject.() -> Unit = moveOrCopy(params, move = false)

    suspend fun executeRenameFile(params: JSONObject): JSONObject.() -> Unit {
        val file = resolveTargetFile(params)
        val newName = params.optString("new_name").ifBlank { throw DeviceAgentError("new_name is required") }
        if (newName.contains('/') || newName.contains(File.separatorChar)) {
            throw DeviceAgentError("new_name must be a file name, not a path")
        }
        val target = File(file.parentFile, newName)
        val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
        if (!ok || !target.isFile) throw DeviceAgentError("could not rename ${file.name}")
        return { put("summary", "renamed ${file.name} → $newName") }
    }

    /** Resolves `path` — or `name` (searched) — into a real file inside the allowed roots. */
    suspend fun resolveTargetFile(params: JSONObject): File {
        val rawPath = params.optString("path")
        if (rawPath.isNotBlank()) {
            val file = File(rawPath)
            val canonical = withContext(Dispatchers.IO) { runCatching { file.canonicalFile }.getOrDefault(file) }
            if (allowedRoots().none { canonical.path.startsWith(it.path) }) {
                throw DeviceAgentError("path is outside the allowed storage roots")
            }
            if (!canonical.isFile) throw DeviceAgentError("file not found: $rawPath")
            return canonical
        }
        val name = params.optString("name").ifBlank { throw DeviceAgentError("path or name is required") }
        val hits = withContext(Dispatchers.IO) { searchFiles(allowedRoots().first(), AppResolver.normalize(name), "") }
        return when {
            hits.isEmpty() -> throw DeviceAgentError("file not found: $name")
            hits.size == 1 -> File(hits.first().path)
            else ->
                throw DeviceAgentError(
                    "found ${hits.size} similar files — which one? " +
                        hits.take(5).joinToString { it.name },
                )
        }
    }

    fun allowedRoots(): List<File> {
        val primary = Environment.getExternalStorageDirectory() ?: File("/sdcard")
        val roots = linkedSetOf(primary)
        File("/storage").listFiles()?.filter { it.isDirectory }?.forEach { roots.add(it) }
        return roots.map { runCatching { it.canonicalFile }.getOrDefault(it) }.filter { it.isDirectory || it.exists() }
    }

    private suspend fun moveOrCopy(
        params: JSONObject,
        move: Boolean,
    ): JSONObject.() -> Unit {
        val file = resolveTargetFile(params)
        val destinationDir =
            File(params.optString("to").ifBlank { throw DeviceAgentError("to directory is required") })
        val allowed = allowedRoots().firstOrNull { destinationDir.canonicalFile.path.startsWith(it.path) }
            ?: throw DeviceAgentError("to directory is outside the allowed storage roots")
        if (!destinationDir.isDirectory) throw DeviceAgentError("not a directory: $destinationDir")
        val target = File(destinationDir, file.name)
        if (move) {
            val ok = withContext(Dispatchers.IO) {
                if (file.renameTo(target)) {
                    true
                } else {
                    copyFile(file, target) && file.delete()
                }
            }
            if (!ok || !target.isFile || (move && file.exists())) {
                throw DeviceAgentError("could not move ${file.path}")
            }
        } else {
            val ok = withContext(Dispatchers.IO) { copyFile(file, target) }
            if (!ok || !target.isFile) throw DeviceAgentError("could not copy ${file.path}")
        }
        val verb = if (move) "moved" else "copied"
        return { put("summary", "$verb ${file.name} → ${target.path}") }
    }

    private fun copyFile(
        source: File,
        target: File,
    ): Boolean =
        runCatching {
            target.outputStream().use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse { it is IOException }

    private fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    data class FoundFile(val path: String, val name: String, val size: Long)

    class DeviceAgentError(message: String) : RuntimeException(message)

    private companion object {
        const val FILE_RESULT_LIMIT = 20
        const val FILE_VISIT_LIMIT = 5_000
    }
}
