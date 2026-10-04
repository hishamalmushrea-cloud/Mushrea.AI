package com.mushrea.code.feature.browser

import com.mushrea.code.core.security.OpenCodeUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val GUEST_BROWSER_FALLBACK_URL = "http://127.0.0.1"

/**
 * The in-app browser is for pages the guest runtime serves on loopback/LAN, not a general
 * web browser. Public hosts, file URLs and javascript: must not load.
 */
internal fun isAllowedGuestBrowserUrl(raw: String): Boolean {
    val parsed = raw.trim().toHttpUrlOrNull() ?: return false
    if (parsed.scheme != "http" && parsed.scheme != "https") return false
    return OpenCodeUrl.isTrustedCleartextHost(parsed.host)
}

internal fun normalizeGuestBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return GUEST_BROWSER_FALLBACK_URL
    val candidate =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    return candidate.takeIf(::isAllowedGuestBrowserUrl) ?: GUEST_BROWSER_FALLBACK_URL
}
