package com.mushrea.code.feature.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import androidx.core.content.ContextCompat
import com.mushrea.code.MushreaCodeApplication
import com.mushrea.code.feature.wakeword.VoskModelCatalog
import com.mushrea.code.feature.wakeword.VoskModelLanguage
import com.mushrea.code.feature.wakeword.WakeWordService
import java.util.Locale

class MushreaCodeVoiceInteractionService : VoiceInteractionService() {
    private var ready = false
    private var receiverRegistered = false
    private val showReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == ACTION_SHOW_ASSISTANT) {
                    showAssistant(intent.getStringExtra(EXTRA_REQUEST_ID))
                }
            }
        }

    override fun onReady() {
        super.onReady()
        ready = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                showReceiver,
                IntentFilter(ACTION_SHOW_ASSISTANT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val app = application as? MushreaCodeApplication
        val preferences = app?.preferences?.state?.value
        if (preferences?.wakeWordEnabled == true) {
            val language =
                VoskModelLanguage.fromId(preferences.wakeWordModelLanguage)
                    ?: VoskModelCatalog.defaultLanguageFor(Locale.getDefault())
            val started = WakeWordService.start(this, language)
            if (!started) app.preferences.setWakeWordEnabled(false)
        }
    }

    override fun onShutdown() {
        ready = false
        WakeWordService.stop(this)
        (application as? MushreaCodeApplication)?.preferences?.setWakeWordEnabled(false)
        unregisterShowReceiver()
        super.onShutdown()
    }

    override fun onDestroy() {
        unregisterShowReceiver()
        super.onDestroy()
    }

    private fun unregisterShowReceiver() {
        if (receiverRegistered) {
            unregisterReceiver(showReceiver)
            receiverRegistered = false
        }
    }

    private fun showAssistant(requestId: String?) {
        if (!ready) {
            requestId?.let(WakeWordService::resumeAfterSession)
            return
        }
        requestId?.let(WakeWordService::pauseForSession)
        runCatching {
            showSession(
                Bundle().apply { requestId?.let { putString(EXTRA_REQUEST_ID, it) } },
                VoiceInteractionSession.SHOW_WITH_ASSIST,
            )
        }.onFailure {
            Log.e(TAG, "Unable to show assistant", it)
            requestId?.let(WakeWordService::resumeAfterSession)
        }
    }

    companion object {
        private const val TAG = "OpenCodeVoiceService"
        const val ACTION_SHOW_ASSISTANT = "com.mushrea.code.action.SHOW_ASSISTANT"
        const val EXTRA_REQUEST_ID = "assistant_request_id"

        fun show(
            context: Context,
            requestId: String,
        ) {
            context.sendBroadcast(
                Intent(ACTION_SHOW_ASSISTANT)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_REQUEST_ID, requestId),
            )
        }
    }
}
