package com.mushrea.code.device.bridge

import kotlin.random.Random

/**
 * The payload of an Android wireless-debugging pairing QR.
 *
 * Verified against AOSP (`packages/modules/adb/docs/dev/adb_wifi.md`): the string borrows the Wi-Fi
 * QR format, but the fields mean something else:
 *
 * ```
 * WIFI:T:ADB;S:<service instance name>;P:<pairing password>;;
 * ```
 *
 *  * `T:ADB` marks it as an ADB payload - a phone that scans anything else ignores it, and we must
 *    ignore anything that is not `T:ADB`;
 *  * `S:` is **not an SSID and not an address**: it is a *request* for the mDNS instance name the
 *    other phone must publish for `_adb-tls-pairing._tcp`. That is why the pairing client has to
 *    match on the name it asked for instead of taking whichever pairing service it sees first - the
 *    other phone may well be advertising a second one for its own pairing-code dialog;
 *  * `P:` is the shared secret (the AOSP text calls it a 10-digit number; adb itself accepts the
 *    printable password the generator produced, which is why the random generator below stays inside
 *    an unambiguous alphabet).
 *
 * The device's camera handler starts the pairing server only after it sees this payload, so nothing
 * here is optional and nothing here may be guessed.
 */
data class PeerAdbPairingPayload(
    val serviceName: String,
    val password: String,
) {
    /** The exact text to encode into the QR image. */
    fun encode(): String = "$PREFIX$TYPE_FIELD$SEPARATOR$SERVICE_FIELD$serviceName$SEPARATOR$PASSWORD_FIELD$password$SEPARATOR$SEPARATOR"

    companion object {
        private const val PREFIX = "WIFI:"
        private const val TYPE_FIELD = "T:"
        private const val SERVICE_FIELD = "S:"
        private const val PASSWORD_FIELD = "P:"
        private const val SEPARATOR = ";"
        private const val ADB_TYPE = "ADB"
        private const val MAX_FIELD_LENGTH = 120

        /** The instance-name prefix AOSP's own generator uses for QR sessions. */
        const val DEFAULT_NAME_PREFIX = "studio-"

        /**
         * A fresh pairing session.
         *
         * The service name is what identifies *our* session on the network (and what the other phone
         * will advertise), so it is randomised per attempt: reusing a name makes an old announcement
         * from a previous attempt indistinguishable from this one. The password alphabet skips
         * characters that are easy to confuse when read aloud from a screen; the semicolon and
         * backslash are excluded outright because they are the payload's own separators.
         */
        fun random(
            random: Random = Random.Default,
            namePrefix: String = DEFAULT_NAME_PREFIX,
        ): PeerAdbPairingPayload =
            PeerAdbPairingPayload(
                serviceName = namePrefix + randomToken(random, DEFAULT_TOKEN_LENGTH),
                password = randomToken(random, DEFAULT_TOKEN_LENGTH),
            )

        /**
         * Reads a scanned string.
         *
         * Fails (rather than returning half a payload) for: a plain Wi-Fi QR, a payload whose type is
         * not `ADB`, and a payload missing either field. The caller turns the failure into
         * [PeerAdbErrorCode.QR_INVALID] with the reason attached - "this QR is a hotel Wi-Fi" is a
         * different problem from "this QR is truncated".
         */
        fun parse(text: String): Result<PeerAdbPairingPayload> {
            val fields = splitEscaped(text.trim())
            if (fields.size < 2 || !fields.first().startsWith(PREFIX)) {
                return Result.failure(IllegalArgumentException("not a WIFI: payload"))
            }
            val type = fields.first().removePrefix(PREFIX).let { body -> body.substringAfter(TYPE_FIELD, "") }
            if (!type.equals(ADB_TYPE, ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("payload type is '${type.ifBlank { "?" }}', not ADB"))
            }
            val service = fields.firstOrNull { it.startsWith(SERVICE_FIELD) }?.removePrefix(SERVICE_FIELD).orEmpty()
            val password = fields.firstOrNull { it.startsWith(PASSWORD_FIELD) }?.removePrefix(PASSWORD_FIELD).orEmpty()
            if (service.isBlank() || password.isBlank()) {
                return Result.failure(IllegalArgumentException("payload is missing its service name or password"))
            }
            return Result.success(PeerAdbPairingPayload(serviceName = service, password = password))
        }

        /** Splits on unescaped `;`, keeping the two trailing empties out of the way. */
        private fun splitEscaped(text: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            text.forEach { character ->
                when {
                    escaped -> {
                        current.append(character)
                        escaped = false
                    }
                    character == '\\' -> escaped = true
                    character == ';' -> {
                        fields.add(current.toString())
                        current.setLength(0)
                    }
                    else -> current.append(character)
                }
            }
            if (current.isNotEmpty()) fields.add(current.toString())
            return fields.filter(String::isNotEmpty)
        }

        private fun randomToken(
            random: Random,
            size: Int,
        ): String {
            val tokenSize = size.coerceIn(4, MAX_FIELD_LENGTH)
            return buildString(tokenSize) {
                repeat(tokenSize) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
        }

        private const val DEFAULT_TOKEN_LENGTH = 10
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789"
    }
}
