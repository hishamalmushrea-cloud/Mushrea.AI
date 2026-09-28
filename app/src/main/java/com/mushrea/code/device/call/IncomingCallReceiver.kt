package com.mushrea.code.device.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Rings the agent into action for incoming calls (spec sections 13-16): reads the ringing state,
 * resolves the caller against the user's rules, and only ever starts the answering flow for
 * callers the policy explicitly allows. Without READ_PHONE_STATE (or the caller number the
 * platform withholds from non-dialer apps) the rule table can't be matched, so the default
 * protects: nothing is answered.
 */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val store = CallAgentStore(context)
        val controller = PhoneCallController(context)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (number.isNullOrBlank()) return // Platform withheld it; policy cannot be matched safely.
        val (normalized, label) = controller.currentCaller(number)

        val action =
            CallPolicy.resolveIncoming(
                callerLabel = label,
                explicitRules = store.readCallerRules(),
                allowKnownContactsByDefault = store.readAllowKnownContactsByDefault(),
            )
        if (action == CallPolicy.IncomingAction.DO_NOT_ANSWER) return

        val intent2 =
            Intent(context, CallAgentService::class.java)
                .setAction(CallAgentService.ACTION_ANSWER)
        context.startForegroundService(intent2)
    }
}
