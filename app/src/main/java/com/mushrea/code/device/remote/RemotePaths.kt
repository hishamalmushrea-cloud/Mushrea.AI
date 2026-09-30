package com.mushrea.code.device.remote

/**
 * Splits a remote path into the pieces each protocol needs, purely.
 *
 * SMB wants a share name plus the path inside that share; the other protocols take host,
 * port, and path directly. This is where "host/share/dir/file" becomes those parts so the
 * executor stays thin and the splitting is unit-testable.
 */
object RemotePaths {
    data class SmbSplit(
        val host: String,
        val share: String,
        val pathInsideShare: String,
    )

    /**
     * Splits "host/share", "host/share/dir", or a bare "host" (share defaults to the Windows
     * default share "IPC$" is useless for files, so an empty share throws at the caller side
     * as a missing-argument error instead).
     */
    fun splitSmb(
        host: String,
        remotePath: String,
    ): SmbSplit {
        val cleaned = remotePath.trimStart('/', '\\')
        val share = cleaned.substringBefore('/').substringBefore('\\')
        if (share.isBlank()) throw IllegalArgumentException("the SMB path needs a share name, like host/share/dir")
        val rest = cleaned.removePrefix(share).trimStart('/', '\\')
        return SmbSplit(host = host, share = share, pathInsideShare = rest)
    }

    /** True when [name] looks like a directory listing entry rather than a file to fetch. */
    fun isFileNameSafe(name: String): Boolean = name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != ".."
}
