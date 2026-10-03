package com.mushrea.code.core.peer

/**
 * How much this app trusts a device it has paired with.
 *
 * Pairing is a key exchange, not a relationship: `adb pair` proves the phone accepted *a* key, and the
 * platform still has to decide what it is willing to do with the channel afterwards. Keeping trust as
 * its own fact is what lets a flow say "this is a device we have used for weeks" differently from
 * "this is a phone somebody just asked us to set up" without inventing a second device record.
 *
 *  * [UNKNOWN] - nothing paired, or nothing observed since (a fresh install keeps the registry entry
 *    but loses the evidence).
 *  * [TOFU] - trust on first use: *we* performed the pairing in this app, so the key on the phone is
 *    the key we presented. Good enough to reconnect without asking again.
 *  * [USER_APPROVED] - the user confirmed the device explicitly (a device they named, or one they
 *    approved after the identity was shown).
 *  * [REVOKED] - the user or the platform withdrew it; the device is kept in the registry so its
 *    identity is not forgotten, but nothing runs on it until it is paired again.
 */
enum class PeerTrust {
    UNKNOWN,
    TOFU,
    USER_APPROVED,
    REVOKED,
    ;

    /** True when a channel to this device may be opened without asking the user again. */
    val mayReconnect: Boolean get() = this == TOFU || this == USER_APPROVED

    /** True when the user still has to answer something before the device is usable. */
    val needsUser: Boolean get() = this == UNKNOWN || this == REVOKED

    val label: String
        get() =
            when (this) {
                UNKNOWN -> "not trusted yet"
                TOFU -> "trusted on first use"
                USER_APPROVED -> "approved by the user"
                REVOKED -> "revoked"
            }
}
