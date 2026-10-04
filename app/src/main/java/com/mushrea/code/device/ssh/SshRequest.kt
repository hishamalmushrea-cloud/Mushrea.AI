package com.mushrea.code.device.ssh

import com.mushrea.code.device.usb.AdbException
import org.json.JSONObject

/**
 * Parses the SSH tool parameters the agent sends. Credentials stay in the conversation: this
 * object never writes them, it only refuses a call that is missing a host, a user, or a proof.
 */
object SshRequest {
    fun credentials(params: JSONObject): SshCredentials {
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

    fun remoteFileName(remotePath: String): String =
        remotePath.trimEnd('/').substringAfterLast('/').ifBlank { "download" }

    fun remoteUploadPath(
        remoteDir: String,
        fileName: String,
    ): String = remoteDir.trimEnd('/').ifBlank { "." } + "/" + fileName
}
