package com.mushrea.code.feature.workspace

import com.mushrea.code.core.connection.ConnectionProfile
import com.mushrea.code.core.security.ConnectionPin
import com.mushrea.code.core.security.OpenCodeUrl
import java.util.UUID

data class ConnectionFormState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
    val allowInsecureLan: Boolean = false,
    val pinSha256: String = "",
    val isTesting: Boolean = false,
    val testMessage: String? = null,
    val testSucceeded: Boolean = false,
) {
    private val parsedUrl
        get() = OpenCodeUrl.normalize(baseUrl).getOrNull()

    val normalizedUrl: String?
        get() = parsedUrl?.toString()

    /** The pin in the form OkHttp needs (base64 SHA-256), or `null` when the field is empty. */
    val normalizedPin: String?
        get() = ConnectionPin.normalize(pinSha256)

    /** A pin was typed but it is not a base64 SHA-256 digest, so the connection cannot be saved. */
    val pinInvalid: Boolean
        get() = pinSha256.isNotBlank() && normalizedPin == null

    /**
     * A pin is only enforced on https: on the plaintext LAN endpoint `OpenCodeApiClient` has no
     * certificate to pin. Surfaced so the field is never left looking active when it is not.
     */
    val pinIgnored: Boolean
        get() = normalizedPin != null && parsedUrl?.scheme == "http"

    /**
     * [OpenCodeUrl.normalize] only ever returns an `http` URL for loopback, RFC1918, link-local,
     * Tailscale CGNAT and `.local` hosts, and rejects everything else that is not https. A valid
     * endpoint is therefore already a safe one, and no separate cleartext opt-in is required.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && parsedUrl != null && !pinInvalid

    fun toProfile(): ConnectionProfile {
        require(!pinInvalid) { "Certificate pin is not a base64 SHA-256 digest" }
        val url = requireNotNull(parsedUrl) { "Endpoint is not a valid OpenCode URL" }
        return ConnectionProfile(
            id = id,
            name = name.trim(),
            baseUrl = url.toString(),
            username = username.trim().ifBlank { "opencode" },
            password = password.takeIf { it.isNotBlank() },
            // `opencode serve` on a PC is plain HTTP on the LAN. normalize() has already limited
            // cleartext to private address space, so record the allowance here instead of asking
            // the user to tick a box before the connection can be saved at all.
            allowInsecureLan = allowInsecureLan || url.scheme == "http",
            pinSha256 = normalizedPin,
        )
    }

    companion object {
        fun from(profile: ConnectionProfile): ConnectionFormState =
            ConnectionFormState(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                username = profile.username,
                password = profile.password.orEmpty(),
                allowInsecureLan = profile.allowInsecureLan,
                // Carried through instead of dropped: an edit that silently deleted the pin would
                // leave the user believing the connection was still pinned.
                pinSha256 = profile.pinSha256.orEmpty(),
                testSucceeded = true,
            )
    }
}
