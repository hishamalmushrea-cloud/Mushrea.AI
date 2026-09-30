package com.mushrea.code.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mushrea.code.device.termux.TermuxBridge

/**
 * Receives the stdout/stderr/exit-code bundle Termux sends back through the pending intent a
 * `RUN_COMMAND` carried. Termux delivers it in the `"result"` bundle extra; the execution id we
 * put on the intent tells us which waiting command it belongs to.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val executionId = intent.getIntExtra(TermuxBridge.EXTRA_EXECUTION_ID, -1)
        if (executionId < 0) return
        val bundle = intent.getBundleExtra(TermuxBridge.RESULT_BUNDLE) ?: return
        TermuxBridge.deliver(executionId, bundle)
    }
}
