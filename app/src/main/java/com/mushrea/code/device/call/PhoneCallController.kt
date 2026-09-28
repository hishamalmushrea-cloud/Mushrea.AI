package com.mushrea.code.device.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.media.AudioManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * The only place the call agent touches the telephony stack — official Android APIs exclusively
 * (spec sections 37/38/45): TelecomManager for dial/end/answer, AudioManager for speaker/mute,
 * PhoneLookup for caller identity. Capabilities the platform gates behind roles (answering,
 * ending a call in progress) are detected, never bypassed: [capabilities] reports what this
 * device+permission set actually allows, and callers degrade honestly instead of pretending.
 */
class PhoneCallController(
    private val context: Context,
) {
    data class Capabilities(
        val canPlaceCall: Boolean,
        val canReadPhoneState: Boolean,
        val canReadContacts: Boolean,
        val canTryAnswering: Boolean,
        val canTryEndingCall: Boolean,
    )

    private val telecom: TelecomManager = context.getSystemService(TelecomManager::class.java)
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    fun capabilities(): Capabilities =
        Capabilities(
            canPlaceCall = granted(Manifest.permission.CALL_PHONE),
            canReadPhoneState = granted(Manifest.permission.READ_PHONE_STATE),
            canReadContacts = granted(Manifest.permission.READ_CONTACTS),
            canTryAnswering =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    (granted(Manifest.permission.ANSWER_PHONE_CALLS) || isDefaultDialer()),
            canTryEndingCall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isDefaultDialer() ||
                granted(Manifest.permission.ANSWER_PHONE_CALLS),
        )

    /** Opens the default-dialer role dialog when the feature needs it (section 38: ask, explain, never force). */
    fun isDefaultDialer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            telecom?.defaultDialerPackage == context.packageName

    fun buildDefaultDialerRequest(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            null
        }

    /**
     * Places the call through TelecomManager.placeCall (no CALL action fallback that could
     * surprise the user with a dialer UI). Returns false — never claims success — when the
     * permission is missing or Telecom rejects the request.
     */
    fun placeCall(number: String): Boolean {
        if (!capabilities().canPlaceCall || number.isBlank()) return false
        return runCatching {
            telecom.placeCall(Uri.parse("tel:" + Uri.encode(number)), null)
            true
        }.getOrDefault(false)
    }

    /**
     * Resolves a contact query ("احمد") to a number via PhoneLookup. Returns (number, label) or
     * null; null with a blank query is "nothing to look up", null with a query is "not found".
     */
    fun resolveContact(query: String): Pair<String, String>? {
        if (!capabilities().canReadContacts || query.isBlank()) return null
        return runCatching {
            val normalized = query.trim()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$normalized%"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0) ?: return@use null
                    val label = cursor.getString(1) ?: normalized
                    number to label
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /** Caller identity for a ringing number (screening, section 15): (number, labelOrNull). */
    fun currentCaller(number: String?): Pair<String, String?> {
        if (number.isNullOrBlank()) return "" to null
        if (!capabilities().canReadContacts) return number to null
        val label =
            runCatching {
                context.contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        return number to label
    }

    /**
     * Answers the ringing call through the official path. On devices where Android grants this
     * only to the default dialer, this returns false and the caller surfaces the role request —
     * the platform rule is respected, not worked around.
     */
    fun tryAcceptRingingCall(): Boolean {
        if (!capabilities().canTryAnswering) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecom.acceptRingingCall()
            } else {
                false
            }
            true
        }.getOrDefault(false)
    }

    /** Ends the active call; role-gated on modern Android, so failure is a normal outcome. */
    fun tryEndCall(): Boolean {
        val ok =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecom.endCall()
                } else {
                    false
                }
            }.getOrDefault(false)
        return ok
    }

    /** Speakerphone on: the practical route for agent turns on real speakers (documented limit). */
    fun setSpeakerOn(on: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            audio.setSpeakerphoneOn(on)
        }
    }

    fun setMicMuted(muted: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            audio.setMicrophoneMute(muted)
        }
    }

    /**
     * One-shot ring-state read: OFFHOOK means a call is active, RINGING means one is arriving.
     * The service subscribes continuously; this is for cold starts and pre-dial checks.
     */
    fun readRadioState(): Int? {
        if (!capabilities().canReadPhoneState) return null
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            manager.callState
        }.getOrNull()
    }

    /**
     * Registers a ring/hold state listener; returns the registration handle or null when the
     * permission is absent (the receiver then simply never fires — no guessing).
     */
    fun listenToRadioState(onChange: (Int) -> Unit): AutoCloseable? {
        if (!capabilities().canReadPhoneState) return null
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val listener =
                object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onChange(state)
                    }
                }
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            AutoCloseable {
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }.getOrNull()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
