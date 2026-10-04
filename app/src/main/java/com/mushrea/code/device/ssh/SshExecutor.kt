package com.mushrea.code.device.ssh

import android.content.Context
import android.os.Environment
import com.mushrea.code.device.usb.AdbException
import net.schmizz.sshj.sftp.FileMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The agent surface for the servers feature: run commands, list directories over SFTP and move
 * files either way. Credentials arrive in the command params (they live in the conversation and
 * are never stored); the first connection to a server pins its host-key fingerprint.
 */
class SshExecutor(
    private val context: Context,
) {
    private val agent by lazy { SshAgent(context) }

    private fun credentials(params: JSONObject): SshCredentials = SshRequest.credentials(params)

    suspend fun executeExec(params: JSONObject): JSONObject.() -> Unit {
        val credentials = credentials(params)
        val command = params.optString("command").trim().ifBlank { throw AdbException("command is required") }
        val timeoutSeconds = params.optInt("timeout_seconds", 60).coerceIn(5, 600)
        val (exit, stdout, stderr) = agent.exec(credentials, command, timeoutSeconds * 1000L)
        return {
            put("exit_status", exit)
            put("stdout", stdout.takeLast(6000))
            if (stderr.isNotBlank()) put("stderr", stderr.takeLast(3000))
            put(
                "summary",
                "exit $exit on ${credentials.host} — first line: " +
                    (stdout.lineSequence().firstOrNull()?.take(120).orEmpty().ifEmpty { "(no output)" }),
            )
        }
    }

    suspend fun executeList(params: JSONObject): JSONObject.() -> Unit {
        val credentials = credentials(params)
        val path = params.optString("path").ifBlank { "." }
        val entries: List<JSONObject> =
            agent.withSession(credentials, LIST_TIMEOUT_MILLIS) { client ->
                client.newSFTPClient().use { sftp ->
                    sftp.ls(path).take(500).map { resource ->
                        JSONObject()
                            .put("name", resource.getName())
                            .put("directory", resource.attributes.mode.type == FileMode.Type.DIRECTORY)
                            .put("size", resource.attributes.size)
                    }
                }
            }
        val array = JSONArray().apply { entries.forEach { put(it) } }
        return {
            put("path", path)
            put("entries", array)
            put("summary", "${entries.size} item(s) in $path on ${credentials.host}")
        }
    }

    suspend fun executeDownload(params: JSONObject): JSONObject.() -> Unit {
        val credentials = credentials(params)
        val remotePath = params.optString("remote_path").ifBlank { throw AdbException("remote_path is required") }
        val name = SshRequest.remoteFileName(remotePath)
        val destination = File(downloadRoot(), "${timestampPrefix()}-$name")
        val (bytes, remoteBytes) =
            agent.withSession(credentials, TRANSFER_TIMEOUT_MILLIS) { client ->
                destination.parentFile?.mkdirs()
                val remoteSize =
                    client.newSFTPClient().use { sftp ->
                        sftp.get(remotePath, destination.absolutePath)
                        sftp.stat(remotePath).size
                    }
                destination.length() to remoteSize
            }
        val verified = remoteBytes >= 0 && bytes == remoteBytes
        if (!verified) {
            throw AdbException("downloaded $remotePath but it is $bytes of $remoteBytes bytes")
        }
        return {
            put("local_path", destination.absolutePath)
            put("bytes", bytes)
            put("summary", "downloaded $remotePath (${bytes / 1024} KiB) into ${destination.parent}")
            put("verified", true)
            put("verification", "the local copy has the remote file's own $remoteBytes bytes")
        }
    }

    suspend fun executeUpload(params: JSONObject): JSONObject.() -> Unit {
        val credentials = credentials(params)
        val localPath = params.optString("local_path").ifBlank { throw AdbException("local_path is required") }
        val file = File(localPath)
        if (!file.isFile) throw AdbException("no local file at $localPath")
        val remoteDir = params.optString("remote_dir").ifBlank { "." }
        val remotePath = SshRequest.remoteUploadPath(remoteDir, file.name)
        val localBytes = file.length()
        val remoteBytes =
            agent.withSession(credentials, TRANSFER_TIMEOUT_MILLIS) { client ->
                client.newSFTPClient().use { sftp ->
                    sftp.put(file.absolutePath, remotePath)
                    sftp.stat(remotePath).size
                }
            }
        if (remoteBytes != localBytes) {
            throw AdbException("uploaded ${file.name} but the server has $remoteBytes of $localBytes bytes")
        }
        return {
            put("remote_path", remotePath)
            put("bytes", localBytes)
            put("summary", "uploaded ${file.name} (${localBytes / 1024} KiB) to $remotePath on ${credentials.host}")
            put("verified", true)
            put("verification", "the server reports the same $localBytes bytes that were sent")
        }
    }

    private fun downloadRoot(): File {
        val root = File(Environment.getExternalStorageDirectory(), "Download/Mushrea-ssh")
        if (!root.isDirectory && !root.mkdirs()) {
            throw AdbException("cannot create ${root.absolutePath} — grant Mushrea Code all-files access")
        }
        return root
    }

    private fun timestampPrefix(): String = System.currentTimeMillis().toString()

    private companion object {
        const val LIST_TIMEOUT_MILLIS = 30_000L
        const val TRANSFER_TIMEOUT_MILLIS = 300_000L
    }
}
