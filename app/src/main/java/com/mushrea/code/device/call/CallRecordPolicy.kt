package com.mushrea.code.device.call

/**
 * Whether near-end call recording may start. The other party's audio is not available to
 * unprivileged apps on stock Android; this policy never pretends otherwise. Recording is an
 * explicit, confirmed tool — the call agent itself still does not record.
 *
 * Call-state codes match [android.telephony.TelephonyManager]: IDLE 0, RINGING 1, OFFHOOK 2.
 */
object CallRecordPolicy {
    const val STATE_IDLE = 0
    const val STATE_RINGING = 1
    const val STATE_OFFHOOK = 2

    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 1_800
    const val DEFAULT_SECONDS = 0

    sealed class Decision {
        data object Allow : Decision()

        data class Refuse(
            val reason: String,
        ) : Decision()
    }

    fun mayStart(
        hasMicrophonePermission: Boolean,
        callState: Int?,
        alreadyRecording: Boolean,
    ): Decision {
        if (alreadyRecording) return Decision.Refuse("a call recording is already in progress — stop it first")
        if (!hasMicrophonePermission) {
            return Decision.Refuse("microphone permission is not granted")
        }
        return when (callState) {
            null ->
                Decision.Refuse(
                    "phone state is unreadable — grant phone permission, and recording only runs during an active call",
                )
            STATE_IDLE -> Decision.Refuse("no call is active — recording starts only while a call is off-hook")
            STATE_RINGING -> Decision.Refuse("the call is still ringing — answer it before recording")
            STATE_OFFHOOK -> Decision.Allow
            else -> Decision.Refuse("unknown call state $callState — recording starts only while a call is off-hook")
        }
    }

    fun clampSeconds(requested: Int): Int =
        when {
            requested <= 0 -> DEFAULT_SECONDS
            else -> requested.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }
}
