package com.mushrea.code.feature.chat

import android.content.Context
import com.mushrea.code.R
import com.mushrea.code.data.connection.SecureSettingsRepository
import com.mushrea.code.feature.assistant.TTSManager
import com.mushrea.code.feature.assistant.TTSState
import com.mushrea.code.feature.assistant.TtsConfiguration
import com.mushrea.code.feature.assistant.TtsSettings
import com.mushrea.code.feature.assistant.ttsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reads chat messages aloud through whichever TTS provider the voice settings configure - the
 * same engine the voice session uses, so its provider, rate, and pitch sliders all apply here.
 *
 * The engine is built lazily on the first read and rebuilt only when the stored voice settings
 * change, so a settings tweak applies on the next read without restarting the app.
 */
class ChatSpeechController(
    context: Context,
    private val settings: SecureSettingsRepository,
    private val scope: CoroutineScope,
    private val onSpeakingChanged: (String?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var manager: TTSManager? = null
    private var configuredFor: TtsSettings? = null
    private var speakJob: Job? = null

    @Volatile
    private var currentMessageId: String? = null

    /** The message currently being read, or null while silent. */
    val speakingMessageId: String?
        get() = currentMessageId

    /** Reads [rawText] for [messageId], or stops it when that message is already being read. */
    fun toggle(
        messageId: String,
        rawText: String,
    ) {
        if (currentMessageId == messageId) {
            stop()
        } else {
            speak(messageId, rawText)
        }
    }

    fun speak(
        messageId: String,
        rawText: String,
    ) {
        stop()
        val spoken = textForSpeech(rawText, appContext.getString(R.string.speech_code_snippet))
        if (spoken.isBlank()) return
        val engine = engine() ?: return
        currentMessageId = messageId
        onSpeakingChanged(messageId)
        speakJob =
            scope.launch {
                engine.speakWithProgress(spoken).collect { state ->
                    when (state) {
                        is TTSState.Error -> {
                            onError(state.message)
                            finish()
                        }
                        TTSState.Done -> finish()
                        else -> Unit
                    }
                }
            }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        manager?.stop()
        if (currentMessageId != null) {
            currentMessageId = null
            onSpeakingChanged(null)
        }
    }

    fun shutdown() {
        stop()
        manager?.shutdown()
        manager = null
        configuredFor = null
    }

    private fun finish() {
        speakJob?.cancel()
        speakJob = null
        if (currentMessageId != null) {
            currentMessageId = null
            onSpeakingChanged(null)
        }
    }

    private fun engine(): TTSManager? {
        val snapshot = settings.ttsSettings()
        var active = manager
        if (active == null || configuredFor != snapshot) {
            active?.shutdown()
            active = TTSManager(appContext, TtsConfiguration.from(snapshot))
            manager = active
            configuredFor = snapshot
        }
        return active
    }
}
