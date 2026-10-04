package com.mushrea.code.core.peer

import java.security.MessageDigest

/**
 * A stable identity for a device, derived from what the device itself said about itself.
 *
 * The problem this solves: Android's wireless debugging gives a device a new port every time the
 * feature is switched on, and a phone can move between networks - so anything that treats "host:port"
 * as identity forgets the phone. The serial is stable but is not always available at the moment we
 * first hear about a device (an mDNS announcement carries an instance name, not a serial), and it is
 * not secret either - it should not be spread around as a key.
 *
 * So the identity is a digest over the fields that describe *this device*: the serial when it is known
 * (the strongest field), plus the hardware and build facts the device reported. Two consequences are
 * deliberate:
 *
 *  * **it survives** a new port, a new address, a Wi-Fi change and a reconnect;
 *  * **it changes** when the serial belongs to a different phone, which is what makes adopting a route
 *    on the strength of an identity safe instead of a guess.
 *
 * The digest is not an authentication mechanism and is not treated as one: pairing and `adb` are what
 * authenticate. It is a name that does not lie, which is all "which device is this?" needs.
 */
object PeerIdentityDigest {
    /** A digest over [serial] alone, for callers that have nothing else yet. */
    fun of(serial: String): String = digest(listOf(serial))

    /** A digest over every identity field the device reported. */
    fun of(
        serial: String,
        model: String = "",
        manufacturer: String = "",
        androidVersion: String = "",
        sdk: Int = 0,
        abi: String = "",
    ): String = digest(listOf(serial, model, manufacturer, androidVersion, sdk.toString(), abi))

    /** A digest over the serial plus the identity a connected device reported about itself. */
    fun of(
        serial: String,
        identity: PeerIdentity,
    ): String =
        of(
            serial = serial,
            model = identity.model,
            manufacturer = identity.manufacturer,
            androidVersion = identity.androidVersion,
            sdk = identity.sdk,
            abi = identity.abi,
        )

    private fun digest(parts: List<String>): String {
        val material = parts.joinToString("\u001f") { it.trim().lowercase() }.trim('\u001f')
        if (material.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
