package com.mushrea.code.device.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mushrea.code.R
import com.mushrea.code.device.DeviceAgentStore
import com.mushrea.code.device.DeviceConfirmReceiver
import kotlinx.coroutines.delay

/**
 * Asks the user to allow one peer operation, using the confirmation mechanism the Device Agent
 * already ships: the same store record, the same Allow/Deny receiver, the same notification channel
 * and the same strings.
 *
 * That is deliberate - a peer operation must not grow a second approval UI. The only thing this class
 * owns is the *wording*, because the decision now concerns another phone, and the poll loop that
 * turns the receiver's answer into a boolean.
 */
class PeerConfirmationPrompt(
    private val context: Context,
    private val store: DeviceAgentStore = DeviceAgentStore(context),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
) {
    /**
     * True when the user allowed it.
     *
     * A timeout, a denial, the emergency stop and "notifications are off" all answer false: an
     * unanswered confirmation is never an approval.
     */
    suspend fun confirm(
        action: String,
        detail: String,
    ): Boolean {
        val requestId = store.requestConfirmation(action, detail)
        val posted = post(requestId, action, detail)
        if (!posted) {
            store.answerConfirmation(requestId, "deny")
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (store.stopRequested()) {
                cancel(requestId)
                return false
            }
            when (store.consumeConfirmationDecision(requestId)) {
                "allow" -> {
                    cancel(requestId)
                    return true
                }
                "deny" -> {
                    cancel(requestId)
                    return false
                }
            }
            delay(pollIntervalMillis)
        }
        cancel(requestId)
        return false
    }

    private fun post(
        requestId: String,
        action: String,
        detail: String,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.device_agent_channel_confirmations),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val body = buildString {
            append(action)
            if (detail.isNotBlank()) append(": ").append(detail)
        }
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.device_agent_confirm_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.device_agent_confirm_allow), intent(requestId, "allow"))
                .addAction(0, context.getString(R.string.device_agent_confirm_deny), intent(requestId, "deny"))
                .build()
        return runCatching { manager.notify(requestId.hashCode(), notification) }.isSuccess
    }

    private fun intent(
        requestId: String,
        decision: String,
    ) = android.app.PendingIntent.getBroadcast(
        context,
        (requestId + decision).hashCode(),
        Intent(context, DeviceConfirmReceiver::class.java)
            .putExtra(DeviceConfirmReceiver.EXTRA_REQUEST_ID, requestId)
            .putExtra(DeviceConfirmReceiver.EXTRA_DECISION, decision),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(requestId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(requestId.hashCode()) }
    }

    private companion object {
        /** The Device Agent's own channel: same id, so the user sees one "Device confirmations". */
        const val CHANNEL = "device_agent_confirmations"
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
