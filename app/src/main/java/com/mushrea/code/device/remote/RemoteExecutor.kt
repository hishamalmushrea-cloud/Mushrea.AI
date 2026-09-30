package com.mushrea.code.device.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Environment
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.mushrea.code.core.util.safeMessage
import com.mushrea.code.device.usb.AdbException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Collections
import java.util.concurrent.TimeUnit

/** One service that advertised itself on the local network (mDNS). */
data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
    val type: String,
)

/** One listed entry, normalized across protocols. */
data class RemoteEntry(
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val modified: String?,
)

/**
 * The remote-device surface: browse what advertises itself on the LAN (mDNS), then list and
 * pull files from machines the user has credentials for, over SMB, FTP, WebDAV, or SCP.
 *
 * Credentials travel per call exactly like the ssh tools - nothing is persisted. SCP pins host
 * keys TOFU-style on first connect, the same stance the ssh tools take.
 */
class RemoteExecutor(
    private val context: Context,
) {
    private val nsdManager get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    // ---- net_browse ---------------------------------------------------------

    suspend fun executeNetBrowse(params: JSONObject): JSONObject.() -> Unit {
        val seconds = params.optInt("seconds", 6).coerceIn(2, 15)
        val found = Collections.synchronizedList(ArrayList<DiscoveredService>())
        withTimeoutOrNull(seconds * 1000L) {
            coroutineScope {
                SERVICE_TYPES.forEach { type ->
                    launch {
                        browse(type).collect { service ->
                            if (found.none { it.host == service.host && it.port == service.port && it.type == service.type }) {
                                found.add(service)
                            }
                        }
                    }
                }
            }
        }
        val array =
            JSONArray(
                found.map { service ->
                    JSONObject()
                        .put("name", service.name)
                        .put("host", service.host)
                        .put("port", service.port)
                        .put("type", service.type)
                },
            )
        return {
            put("services", array)
            put(
                "summary",
                if (found.isEmpty()) {
                    "no services advertised themselves in $seconds s — machines often stay silent; remote_list works with a known address anyway"
                } else {
                    "${found.size} service(s) found: " + found.joinToString(", ") { "${it.type}://${it.host}:${it.port}" } +
                        " — ask for a listing with remote_list"
                },
            )
        }
    }

    private fun browse(serviceType: String): Flow<DiscoveredService> =
        callbackFlow {
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        close()
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        // Stopping a finished browse is not an error anyone can act on.
                    }

                    override fun onDiscoveryStarted(serviceType: String) {
                        // The browse window collects whatever arrives.
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        // The window closed normally or timed out.
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        // A machine that goes quiet mid-browse stays listed for this window.
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        nsdManager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    info: NsdServiceInfo,
                                    errorCode: Int,
                                ) = Unit

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host?.hostAddress
                                    if (host != null) {
                                        trySend(DiscoveredService(info.serviceName.orEmpty(), host, info.port, serviceType))
                                    }
                                }
                            },
                        )
                    }
                }
            runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { close() }
            awaitClose { runCatching { nsdManager.stopServiceDiscovery(listener) } }
        }

    // ---- remote_list --------------------------------------------------------

    suspend fun executeRemoteList(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val protocol = params.optString("protocol").lowercase()
            val host = params.optString("host").ifBlank { throw AdbException("host is required") }
            val path = params.optString("path").ifBlank { "/" }
            val user = params.optString("user").ifBlank { "anonymous" }
            val password = params.optString("password")
            when (protocol) {
                "smb" -> Unit
                "ftp" -> Unit
                "webdav" -> Unit
                else ->
                    throw AdbException(
                        "protocol must be smb, ftp or webdav (scp cannot list directories; use ssh_exec or remote_download)",
                    )
            }
            val entries =
                when (protocol) {
                    "smb" -> smbList(host, path, user, password)
                    "ftp" -> ftpList(host, params.optInt("port", 21), path, user, password)
                    else -> webDavList(host, params.optInt("port", 80), path, user, password)
                }
            val entriesJson =
                JSONArray(
                    entries.take(500).map { entry ->
                        JSONObject()
                            .put("name", entry.name)
                            .put("is_folder", entry.isFolder)
                            .put("bytes", entry.sizeBytes)
                            .put("modified", entry.modified ?: JSONObject.NULL)
                    },
                )
            return@withContext {
                put("entries", entriesJson)
                put(
                    "summary",
                    entries.size.toString() + " item(s) — entries carry name, size and date, so \"newest video\" is a sort away; remote_download copies one into Download/Mushrea-remote",
                )
            }
        }

    // ---- remote_download ----------------------------------------------------

    suspend fun executeRemoteDownload(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val protocol = params.optString("protocol").lowercase()
            val host = params.optString("host").ifBlank { throw AdbException("host is required") }
            val path = params.optString("path").ifBlank { throw AdbException("path is required (the file to copy)") }
            val name = params.optString("name").ifBlank { WebDavListing.displayName(path) }
            if (!RemotePaths.isFileNameSafe(name)) throw AdbException("the derived file name is not safe: $name")
            val user = params.optString("user").ifBlank { "anonymous" }
            val password = params.optString("password")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mushrea-remote")
            if (!dir.exists()) dir.mkdirs()
            val destination = File(dir, name)
            when (protocol) {
                "smb" -> smbDownload(host, path, user, password, destination)
                "ftp" -> ftpDownload(host, params.optInt("port", 21), path, user, password, destination)
                "webdav" -> webDavDownload(host, params.optInt("port", 80), path, user, password, destination)
                "scp" -> scpDownload(host, params.optInt("port", 22), path, user, password, destination)
                else -> throw AdbException("protocol must be smb, ftp, webdav or scp")
            }
            {
                put("path", destination.absolutePath)
                put("bytes", destination.length())
                put("summary", "copied $name from $protocol://$host into " + destination.absolutePath)
            }
        }

    // ---- SMB ----------------------------------------------------------------

    private fun smbList(
        host: String,
        path: String,
        user: String,
        password: String,
    ): List<RemoteEntry> {
        val split = RemotePaths.splitSmb(host, path)
        SMBClient().use { client ->
            val connection = client.connect(split.host) ?: throw AdbException("cannot reach the SMB host ${split.host}")
            val session =
                try {
                    connection.authenticate(AuthenticationContext(user, password.toCharArray(), ""))
                } catch (t: Throwable) {
                    throw AdbException("SMB login refused: " + t.safeMessage("authentication failed"))
                }
            val share =
                try {
                    session.connectShare(split.share) as? DiskShare
                } catch (t: Throwable) {
                    throw AdbException("cannot open the share '${split.share}': " + t.safeMessage("share not available"))
                } ?: throw AdbException("'${split.share}' is not a file share")
            share.use {
                val listing: List<FileIdBothDirectoryInformation> =
                    runCatching { it.list(split.pathInsideShare, "*") }.getOrElse { t ->
                        throw AdbException("SMB listing failed: " + t.safeMessage("access denied"))
                    }
                return listing
                    .filter { info -> info.fileName != "." && info.fileName != ".." }
                    .map { info ->
                        RemoteEntry(
                            name = info.fileName,
                            isFolder = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                            sizeBytes = info.endOfFile,
                            modified = null,
                        )
                    }
            }
        }
    }

    private fun smbDownload(
        host: String,
        path: String,
        user: String,
        password: String,
        destination: File,
    ) {
        val split = RemotePaths.splitSmb(host, path)
        SMBClient().use { client ->
            val connection = client.connect(split.host) ?: throw AdbException("cannot reach the SMB host ${split.host}")
            val session = connection.authenticate(AuthenticationContext(user, password.toCharArray(), ""))
            val share = session.connectShare(split.share) as? DiskShare ?: throw AdbException("'${split.share}' is not a file share")
            share.use {
                val file =
                    it.openFile(
                        split.pathInsideShare,
                        setOf(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                file.use { handle -> handle.inputStream.use { input -> copyAll(input, destination) } }
            }
        }
    }

    // ---- FTP ----------------------------------------------------------------

    private fun ftpClient(
        host: String,
        port: Int,
        user: String,
        password: String,
    ): FTPClient =
        FTPClient().apply {
            connectTimeout = 15_000
            connect(host, port)
            enterLocalPassiveMode()
            if (!login(user, password)) {
                val reply = replyString
                disconnect()
                throw AdbException("FTP login refused: " + (reply ?: "unknown reply"))
            }
            setFileType(FTP.BINARY_FILE_TYPE)
        }

    private fun ftpList(
        host: String,
        port: Int,
        path: String,
        user: String,
        password: String,
    ): List<RemoteEntry> {
        val client = ftpClient(host, port, user, password)
        try {
            val files = client.listFiles(path.ifBlank { "/" })
            return files.map { file ->
                RemoteEntry(
                    name = file.name,
                    isFolder = file.isDirectory,
                    sizeBytes = file.size,
                    modified =
                        file.timestamp?.let { stamp ->
                            String.format(
                                "%04d-%02d-%02d %02d:%02d",
                                stamp.get(java.util.Calendar.YEAR),
                                stamp.get(java.util.Calendar.MONTH) + 1,
                                stamp.get(java.util.Calendar.DAY_OF_MONTH),
                                stamp.get(java.util.Calendar.HOUR_OF_DAY),
                                stamp.get(java.util.Calendar.MINUTE),
                            )
                        },
                )
            }
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    private fun ftpDownload(
        host: String,
        port: Int,
        path: String,
        user: String,
        password: String,
        destination: File,
    ) {
        val client = ftpClient(host, port, user, password)
        try {
            destination.outputStream().use { output ->
                if (!client.retrieveFile(path, output)) {
                    throw AdbException("FTP transfer failed: " + (client.replyString ?: "unknown reply"))
                }
            }
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    // ---- WebDAV -------------------------------------------------------------

    private fun webDavList(
        host: String,
        port: Int,
        path: String,
        user: String,
        password: String,
    ): List<RemoteEntry> {
        val normalized = if (path.startsWith("/")) path else "/" + path
        val request =
            okhttp3.Request
                .Builder()
                .url("http://$host:$port$normalized")
                .header("Authorization", Credentials.basic(user, password))
                .header("Depth", "1")
                .method("PROPFIND", okhttp3.internal.EMPTY_REQUEST)
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AdbException("WebDAV PROPFIND failed: HTTP ${response.code}")
            val body = response.body ?: throw AdbException("the WebDAV server answered with an empty body")
            val self = normalized.trimEnd('/')
            return WebDavListing
                .parse(body.charStream())
                .filter { entry -> entry.href.trimEnd('/') != self }
                .map { entry -> RemoteEntry(WebDavListing.displayName(entry.href), entry.isFolder, entry.sizeBytes, entry.lastModified) }
        }
    }

    private fun webDavDownload(
        host: String,
        port: Int,
        path: String,
        user: String,
        password: String,
        destination: File,
    ) {
        val normalized = if (path.startsWith("/")) path else "/" + path
        val request =
            okhttp3.Request
                .Builder()
                .url("http://$host:$port$normalized")
                .header("Authorization", Credentials.basic(user, password))
                .get()
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AdbException("WebDAV GET failed: HTTP ${response.code}")
            val body = response.body ?: throw AdbException("the WebDAV server answered with an empty body")
            body.byteStream().use { input -> copyAll(input, destination) }
        }
    }

    // ---- SCP (sshj with TOFU host pinning, like the ssh tools) --------------

    private fun scpDownload(
        host: String,
        port: Int,
        path: String,
        user: String,
        password: String,
        destination: File,
    ) {
        if (user.isBlank() || user == "anonymous") throw AdbException("scp needs a real user name")
        val client = SSHClient()
        client.addHostKeyVerifier(ScpTofuVerifier(context))
        try {
            client.connect(host, port)
            try {
                client.authPassword(user, password)
            } catch (t: Throwable) {
                throw AdbException("scp login refused: " + t.safeMessage("authentication failed"))
            }
            client.newSCPFileTransfer().download(path, destination.absolutePath)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun copyAll(
        input: InputStream,
        destination: File,
    ) {
        destination.outputStream().use { output -> input.copyTo(output) }
    }

    private companion object {
        val SERVICE_TYPES = listOf("_ssh._tcp.", "_smb._tcp.", "_ftp._tcp.", "_http._tcp.", "_webdav._tcp.", "_nfs._tcp.", "_ipp._tcp.")
    }
}

/** TOFU host pinning for SCP, mirroring the ssh tools' behaviour with its own pin file. */
class ScpTofuVerifier(
    private val context: Context,
) : HostKeyVerifier {
    private val pinFile = File(context.filesDir, "remote/scp_known_hosts.json")

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.encoded)
                .joinToString("") { "%02x".format(it) }
        val pins = readPins()
        val existing = pins.opt(hostname)
        if (existing is String) {
            return existing == fingerprint
        }
        pins.put(hostname, fingerprint)
        pinFile.parentFile?.mkdirs()
        pinFile.writeText(pins.toString())
        return true
    }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = emptyList()

    private fun readPins(): JSONObject =
        runCatching { JSONObject(pinFile.takeIf { it.isFile }?.readText() ?: "{}") }.getOrDefault(JSONObject())
}
