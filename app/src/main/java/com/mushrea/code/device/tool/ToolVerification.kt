package com.mushrea.code.device.tool

import org.json.JSONObject

/**
 * What the app could prove about an execution, as opposed to what the executor reported.
 *
 * The bridge has always promised "execute → verify" (spec section 25) while only three actions
 * checked anything; everything else reported success because the call returned without throwing.
 * The rule from Phase 2 on: **every** result carries a verification, and an action nobody can check
 * says so instead of implying a confirmation that never happened.
 *
 * Two levels, kept apart on purpose:
 *  * [verified] `true` — an independent check ran *after* the action (the file is gone from disk, the
 *    destination has the source's byte count, the package is installed, the foreground app changed);
 *  * [verified] `false` — the executor reported success and nothing verified the effect, with the
 *    reason in [detail].
 */
data class OutcomeVerification(
    val verified: Boolean,
    val detail: String,
) {
    companion object {
        const val KEY_VERIFIED = "verified"
        const val KEY_DETAIL = "verification"

        /** An independent check ran and passed. */
        fun passed(detail: String): OutcomeVerification = OutcomeVerification(verified = true, detail = detail)

        /** Nothing checked the effect; [detail] says why (never silence). */
        fun unverified(detail: String): OutcomeVerification = OutcomeVerification(verified = false, detail = detail)

        /** The action itself failed, so there was no effect to verify. */
        fun failed(): OutcomeVerification = unverified("the action failed, so there was nothing to verify")

        /**
         * Reads what an executor recorded in its payload, or null when it recorded nothing.
         *
         * `verified` is accepted in the legacy mixed form one action used (`true`, or a string that
         * explains why it could not be confirmed), so an executor can be converted one at a time
         * without the bridge misreading the old shape as success.
         */
        fun of(payload: JSONObject): OutcomeVerification? {
            if (payload.has(KEY_DETAIL)) {
                return OutcomeVerification(payload.optBoolean(KEY_VERIFIED, false), payload.optString(KEY_DETAIL))
            }
            if (!payload.has(KEY_VERIFIED)) return null
            val raw = payload.opt(KEY_VERIFIED)
            return when (raw) {
                is Boolean -> if (raw) passed("confirmed by the executor") else unverified("the executor could not confirm the effect")
                is String -> if (raw.equals("true", ignoreCase = true)) passed("confirmed by the executor") else unverified(raw)
                else -> null
            }
        }

        /** Writes [verification] into a result payload, replacing whatever the executor left there. */
        fun apply(
            payload: JSONObject,
            verification: OutcomeVerification,
        ) {
            payload.put(KEY_VERIFIED, verification.verified)
            payload.put(KEY_DETAIL, verification.detail)
        }
    }
}
