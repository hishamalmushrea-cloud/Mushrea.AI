package com.mushrea.code.core.security

import java.util.Base64
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Certificate pin handling for a remote OpenCode endpoint.
 *
 * `ConnectionProfile.pinSha256` stores the pin and `OpenCodeApiClient.defaultHttpClient` feeds it to
 * OkHttp's `CertificatePinner`. OkHttp expects the **base64** SHA-256 of the certificate's Subject
 * Public Key Info — the value `openssl … | openssl enc -base64` prints, and the one OkHttp itself
 * echoes in a pinning failure — not a hex digest. Every value a user pastes is normalised to that
 * canonical form here, before it can reach the network layer.
 */
object ConnectionPin {
    private const val PREFIX = "sha256/"
    private const val DIGEST_BYTES = 32

    /**
     * Marker in OkHttp's pin failure message. A hostname verification failure raises the same
     * [SSLPeerUnverifiedException] type, so the message is the only thing that separates a pin
     * mismatch from an ordinary TLS trust problem.
     */
    private const val MISMATCH_MARKER = "Certificate pinning failure"

    /**
     * Returns the canonical base64 pin for [raw], or `null` when [raw] is blank or is not a SHA-256
     * digest. A leading `sha256/`, missing base64 padding and surrounding whitespace are accepted so
     * that a value copied from `openssl` or from OkHttp's own error output both work.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val body =
            if (trimmed.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) {
                trimmed.substring(PREFIX.length)
            } else {
                trimmed
            }
        val compact = body.filterNot { it.isWhitespace() }
        val decoded = runCatching { Base64.getDecoder().decode(compact) }.getOrNull() ?: return null
        if (decoded.size != DIGEST_BYTES) return null
        return Base64.getEncoder().encodeToString(decoded)
    }

    /**
     * Whether [error] is OkHttp refusing the connection because the server certificate does not
     * match the configured pin. Causes are walked because a caller may have wrapped the failure.
     * A certificate that simply fails trust validation is **not** reported as a mismatch: the two
     * have different fixes and must not be conflated.
     */
    fun isMismatch(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val peerUnverified = current as? SSLPeerUnverifiedException
            if (peerUnverified?.message?.contains(MISMATCH_MARKER) == true) return true
            current = current.cause
        }
        return false
    }
}
