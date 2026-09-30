package com.mushrea.code.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives Allow / Deny from the Device Agent confirmation notification and records the decision
 * for the pending request; the bridge picks it up from [DeviceAgentStore] within its poll interval.
 */
class DeviceConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        if (decision !in setOf("allow", "deny")) return
        DeviceAgentStore(context).answerConfirmation(requestId, decision)
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val ACTION_CONFIRM = "com.mushrea.code.DEVICE_CONFIRM"
    }
}

/**
 * Emergency stop (prompt section 36): sets the stop flag the bridge consumes between steps, so no
 * new device action starts after the user asked to stop. Wired to the STOP button and any in-app
 * stop control.
 */
class StopAgentReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        DeviceAgentStore(context).requestStop()
    }

    companion object {
        const val ACTION_STOP_AGENT = "com.mushrea.code.STOP_AGENT"
    }
}
