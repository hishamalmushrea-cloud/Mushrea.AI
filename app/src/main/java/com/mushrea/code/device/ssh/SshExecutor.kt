package com.mushrea.code.device.ssh

import android.content.Context
import android.os.Environment
import com.mushrea.code.device.usb.AdbException
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

    private fun credentials(params: JSONObject): SshCredentials {
        val host = params.optString("host").ifBlank { throw AdbException("host is required") }
        val username = params.optString("username").ifBlank { throw AdbException("username is required") }
        val port = params.optInt("port", 22).coerceIn(1, 65535)
        val password = params.optString("password").ifBlank { null }
        val privateKeyPath = params.optString("private_key").ifBlank { null }
        if (password == null && privateKeyPath == null) {
            throw AdbException("give me either a password or a private_key path")
        }
        return SshCredentials(host, port, username, password, privateKeyPath)
    }

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
                            .put("directory", isDirectory(resource.attributes.mode.permissions))
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
        val name = remotePath.trimEnd('/').substringAfterLast('/').ifBlank { "download" }
        val destination = File(downloadRoot(), "${timestampPrefix()}-$name")
        val bytes =
            agent.withSession(credentials, TRANSFER_TIMEOUT_MILLIS) { client ->
                destination.parentFile?.mkdirs()
                client.newSFTPClient().use { sftp -> sftp.get(remotePath, destination.absolutePath) }
                destination.length()
            }
        return {
            put("local_path", destination.absolutePath)
            put("bytes", bytes)
            put("summary", "downloaded $remotePath (${bytes / 1024} KiB) into ${destination.parent}")
        }
    }

    suspend fun executeUpload(params: JSONObject): JSONObject.() -> Unit {
        val credentials = credentials(params)
        val localPath = params.optString("local_path").ifBlank { throw AdbException("local_path is required") }
        val file = File(localPath)
        if (!file.isFile) throw AdbException("no local file at $localPath")
        val remoteDir = params.optString("remote_dir").ifBlank { "." }
        val remotePath = remoteDir.trimEnd('/') + "/" + file.name
        agent.withSession(credentials, TRANSFER_TIMEOUT_MILLIS) { client ->
            client.newSFTPClient().use { sftp -> sftp.put(file.absolutePath, remotePath) }
        }
        return {
            put("remote_path", remotePath)
            put("bytes", file.length())
            put("summary", "uploaded ${file.name} (${file.length() / 1024} KiB) to $remotePath on ${credentials.host}")
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

    /** sshj 0.38 dropped the bean type helpers; the POSIX mode bits are the stable truth. */
    private fun isDirectory(mode: Int): Boolean = (mode and 0xF000) == 0x4000

    private companion object {
        const val LIST_TIMEOUT_MILLIS = 30_000L
        const val TRANSFER_TIMEOUT_MILLIS = 300_000L
    }
}
