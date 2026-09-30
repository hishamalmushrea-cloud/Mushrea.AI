package com.mushrea.code.device

/**
 * The single place that names Xiaomi's official bootloader-unlock path — and the honest sentences
 * around it.
 *
 * The owner's requirement was explicit: a locked device must be refused *and* pointed at the
 * official route, with the Mi Unlock link. That sentence existed in the diagnostics output as
 * prose; this object makes the link a first-class value so the UI can open it, the diagnostics
 * payload can carry it, and a JVM unit test can pin both facts down (see `XiaomiUnlockTest`).
 *
 * What this deliberately does **not** do: it does not sign anything, does not fetch a token, and
 * does not shorten the 72 h/168 h account waiting period. Xiaomi's bootloader verifies a server
 * signature, so the official flow is the only flow — anything else is either a bypass claim that
 * cannot work or a tool that bricks the phone.
 */
object XiaomiUnlock {
    /** The official apply/download page; the requirements and waiting period published there are the real ones. */
    const val OFFICIAL_URL = "https://en.miui.com/unlock/"

    /**
     * Why a locked device cannot be flashed, for the diagnostics page and for `safety_preflight`.
     * [product] is the codename `fastboot getvar product` reported, when the caller knows it.
     */
    fun lockedNotice(product: String?): String {
        val device = product?.trim()?.takeIf { it.isNotBlank() }
        val prefix = if (device == null) "the bootloader is LOCKED" else "the bootloader of \"$device\" is LOCKED"
        return "$prefix: flashing is refused by design. The only path is Xiaomi's official unlock " +
            "(Mi Unlock, bound to your Mi account, with its own waiting period): $OFFICIAL_URL"
    }

    /**
     * The counterpart reminder: the wait is real and nothing here pretends otherwise. Used by
     * `safety_preflight` so a green checklist is never read as "ready to unlock right now".
     */
    fun waitingPeriodReminder(): String =
        "Mi unlock waiting period: if Mi Unlock reports 72h/168h, that time must actually elapse — " +
            "nothing in this app can shorten it, and no tool should pretend otherwise ($OFFICIAL_URL)"
}
