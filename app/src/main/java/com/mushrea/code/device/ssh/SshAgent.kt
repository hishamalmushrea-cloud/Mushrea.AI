package com.mushrea.code.device.ssh

import android.content.Context
import com.mushrea.code.device.usb.AdbException
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.json.JSONObject

/** Where to connect and how to prove who we are. Credentials live in the conversation only. */
data class SshCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKeyPath: String?,
)

/**
 * SSH/SFTP half of the servers feature on top of sshj: host keys are pinned trust-on-first-use
 * into app-private storage (a later fingerprint change fails loudly instead of silently
 * trusting a different server), passwords and key files are used per call and never persisted.
 */
class SshAgent(private val context: Context) {
    /** The key the server presented on the last successful verification — used to pin TOFU. */
    @Volatile
    private var verifiedKey: PublicKey? = null

    suspend fun <T> withSession(
        credentials: SshCredentials,
        timeoutMillis: Long,
        block: suspend (SSHClient) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            verifiedKey = null
            val client = SSHClient()
            client.addHostKeyVerifier { _, _, key ->
                verifiedKey = key
                val pinned = knownHosts().optString(keyOf(credentials))
                pinned.isEmpty() || pinned == fingerprint(key)
            }
            client.use {
                val failure =
                    runCatching { client.connect(credentials.host, credentials.port) }.exceptionOrNull()
                if (failure != null) {
                    throw AdbException("SSH connect to ${credentials.host}:${credentials.port} failed: ${failure.message}")
                }
                verifiedKey?.let { rememberFingerprint(credentials, it) }
                if (credentials.privateKeyPath != null) {
                    runCatching { client.loadKeys(credentials.privateKeyPath) }
                        .onSuccess { client.addIdentityKey(it) }
                        .onFailure { throw AdbException("cannot read the private key: ${it.message}") }
                }
                if (credentials.password != null) {
                    client.addPasswordIdentity(credentials.password)
                }
                val authFailure =
                    runCatching { client.auth(credentials.username) }.exceptionOrNull()
                if (authFailure != null) {
                    throw AdbException("SSH authentication as ${credentials.username} failed: ${authFailure.message}")
                }
                try {
                    withTimeout(timeoutMillis) { block(client) }
                } catch (timeout: TimeoutCancellationException) {
                    throw AdbException("SSH operation timed out after ${timeoutMillis / 1000}s")
                }
            }
        }

    /** Runs one command; returns (exit status, stdout, stderr) with streams read fully. */
    suspend fun exec(
        credentials: SshCredentials,
        command: String,
        timeoutMillis: Long,
    ): Triple<Int, String, String> =
        withSession(credentials, timeoutMillis) { client ->
            client.startSession().use { session: Session ->
                val cmd = session.exec(command)
                cmd.join()
                val stdout = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
                val exit = cmd.exitStatus ?: -1
                Triple(exit, stdout, stderr)
            }
        }

    private fun rememberFingerprint(
        credentials: SshCredentials,
        key: PublicKey,
    ) {
        val hosts = knownHosts()
        val keyName = keyOf(credentials)
        if (hosts.optString(keyName) == fingerprint(key)) return
        hosts.put(keyName, fingerprint(key))
        runCatching {
            hostsFile().parentFile?.mkdirs()
            hostsFile().writeText(hosts.toString(2))
        }
    }

    private fun keyOf(credentials: SshCredentials): String = credentials.host + ":" + credentials.port

    private fun fingerprint(key: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }

    private fun knownHosts(): JSONObject = runCatching { JSONObject(hostsFile().readText()) }.getOrDefault(JSONObject())

    private fun hostsFile(): File = File(context.filesDir, "ssh/known_hosts.json")
}
