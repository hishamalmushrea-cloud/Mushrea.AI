package com.mushrea.code.device.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mushrea.code.R
import com.mushrea.code.feature.assistant.SpeechRecognizerManager
import com.mushrea.code.feature.assistant.SpeechResult
import com.mushrea.code.feature.assistant.TTSManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Foreground host of the conversation agent: dials or answers through [PhoneCallController],
 * drives [ConversationEngine] with the project's existing recognizer and TTS (no parallel STT/TTS
 * stack), publishes live state to [CallAgentStore] for the MCP surface, and exposes TAKE OVER /
 * END / STOP as notification actions (spec sections 18/19). The call itself stays a normal
 * system call — no audio is recorded, ever (section 27).
 */
class CallAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: PhoneCallController
    private lateinit var store: CallAgentStore
    private lateinit var tts: TTSManager
    private lateinit var recognizer: SpeechRecognizerManager
    private var conversationJob: Job? = null
    private var callStartedMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = PhoneCallController(this)
        store = CallAgentStore(this)
        tts = TTSManager(this)
        recognizer = SpeechRecognizerManager(this)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_RUN_TASK -> startInForeground(getString(R.string.call_agent_ongoing_title))
            ACTION_ANSWER -> startInForeground(getString(R.string.call_agent_incoming_mode))
            ACTION_STOP_AGENT, ACTION_TAKE_OVER, ACTION_END_CALL -> startInForeground(getString(R.string.call_agent_ongoing_title))
            else -> Unit
        }
        when (intent?.action) {
            ACTION_ANSWER -> {
                // Incoming flow: the receiver passes the caller's number when the platform
                // disclosed it; without an explicit task the agent takes a message (section 16).
                val number = intent.getStringExtra(EXTRA_NUMBER)
                val label = number?.takeIf { it.isNotBlank() }?.let { controller.currentCaller(it).second }
                val task =
                    intent.getStringExtra(EXTRA_TASK)
                        ?.let { raw -> runCatching { decodeTask(raw) }.getOrNull() }
                        ?: CallTask(
                            contactQuery = number.orEmpty(),
                            goals = listOf(ConversationGoal("ماذا تحتاج؟")),
                            mode = CallTask.Mode.ANSWER_POLICY,
                        )
                conversationJob?.cancel()
                conversationJob =
                    scope.launch {
                        runCall(task, incomingCallerLabel = label)
                    }
                return START_NOT_STICKY
            }
            ACTION_TAKE_OVER -> {
                conversationJob?.cancel()
                updateNotification(getString(R.string.call_agent_taken_over))
                return START_NOT_STICKY
            }
            ACTION_END_CALL -> {
                conversationJob?.cancel()
                controller.tryEndCall()
                finish(CallStateMachine.State.STOPPED)
                return START_NOT_STICKY
            }
            ACTION_STOP_AGENT -> {
                conversationJob?.cancel()
                tts.stop()
                finish(CallStateMachine.State.STOPPED)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }

        val task = intent?.getStringExtra(EXTRA_TASK)?.let { raw -> runCatching { decodeTask(raw) }.getOrNull() }
        conversationJob?.cancel()
        conversationJob =
            scope.launch {
                runCall(task)
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        tts.shutdown()
        super.onDestroy()
    }

    private suspend fun runCall(
        task: CallTask?,
        incomingCallerLabel: String? = null,
    ) {
        if (task == null) {
            finish(CallStateMachine.State.FAILED)
            return
        }
        store.writeLiveState(CallStateMachine.State.DIALING, null)
        callStartedMillis = System.currentTimeMillis()

        // Outgoing: resolve → dial → wait for the radio to say the call is actually up.
        var callerLabel: String? = incomingCallerLabel
        if (task.mode == CallTask.Mode.OUTGOING) {
            val contact = controller.resolveContact(task.contactQuery)
            val number = contact?.first
            callerLabel = contact?.second
            if (number == null || !controller.placeCall(number)) {
                store.writeLiveState(CallStateMachine.State.FAILED, null)
                store.appendCallSummary(
                    CallSummary(
                        verification = CallSummary.Verification(false, false, false),
                        purpose = task.goals.firstOrNull()?.question.orEmpty(),
                        answers = emptyList(),
                        callerFacts = emptyList(),
                        callerMessage = null,
                        durationMillis = 0,
                        state = CallStateMachine.State.FAILED,
                    ),
                )
                finish(CallStateMachine.State.FAILED)
                return
            }
            store.writeLiveState(CallStateMachine.State.RINGING, null)
            if (controller.readRadioState() == null) {
                // Without READ_PHONE_STATE the connection cannot be verified, so the agent
                // refuses to talk into an unverified call rather than pretend (section 45).
                store.writeLiveState(CallStateMachine.State.FAILED, null)
                store.appendCallSummary(
                    CallSummary(
                        verification = CallSummary.Verification(true, false, false),
                        purpose = task.goals.firstOrNull()?.question.orEmpty(),
                        answers = emptyList(),
                        callerFacts = listOf("READ_PHONE_STATE permission missing — call state unverifiable"),
                        callerMessage = null,
                        durationMillis = System.currentTimeMillis() - callStartedMillis,
                        state = CallStateMachine.State.FAILED,
                    ),
                )
                finish(CallStateMachine.State.FAILED)
                return
            }
            val connected = waitForConnect(CONNECT_WAIT_MILLIS)
            if (!connected) {
                store.writeLiveState(CallStateMachine.State.NO_ANSWER, null)
                finish(CallStateMachine.State.NO_ANSWER)
                return
            }
        } else {
            if (!controller.tryAcceptRingingCall()) {
                // The official path is role-gated; say so instead of pretending (sections 37/38).
                updateNotification(getString(R.string.call_agent_needs_dialer_role))
                store.writeLiveState(CallStateMachine.State.FAILED, null)
                finish(CallStateMachine.State.FAILED)
                return
            }
            store.writeLiveState(CallStateMachine.State.CONNECTED, null)
        }
        controller.setSpeakerOn(true)

        val userName = store.readUserDisplayName().ifBlank { getString(R.string.call_agent_default_user_name) }
        val engine =
            ConversationEngine(
                speaker = ttsSpeaker(),
                listener = recognizerListener(),
                brain = liveBrain(userName),
            )
        val outcome =
            engine.run(
                task = task,
                userName = userName,
                identityTemplate = store.readIdentityTemplate().ifBlank { null },
                callerLabel = callerLabel,
                isMessageMode = messageModeFor(task),
                onStateChange = { state, conversation -> store.writeLiveState(state, conversation) },
            )

        val summary =
            CallSummary.from(
                outcome.conversation,
                finalState = outcome.finalState,
                durationMillis = System.currentTimeMillis() - callStartedMillis,
                wasInitiated = true,
                wasAnswered =
                    outcome.finalState != CallStateMachine.State.NO_ANSWER &&
                        outcome.finalState != CallStateMachine.State.FAILED,
            )
        store.appendCallSummary(summary)
        outcome.takenMessage?.let { message ->
            store.recordTakenMessage(outcome.conversation.callTarget, message)
        }
        store.writeLiveState(outcome.finalState, outcome.conversation)
        if (outcome.finalState != CallStateMachine.State.STOPPED) controller.tryEndCall()
        finish(outcome.finalState)
    }

    private suspend fun waitForConnect(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (controller.readRadioState() != CALL_STATE_OFFHOOK) {
                if (controller.readRadioState() == CALL_STATE_IDLE) return@withTimeoutOrNull false
                kotlinx.coroutines.delay(POLL_MILLIS)
            }
            true
        } ?: false

    /** One recognition window reusing the project's recognizer (section 5: reuse, no parallel stack). */
    private suspend fun listenOnce(timeoutMillis: Long): String? =
        withTimeoutOrNull(timeoutMillis) {
            recognizer
                .startListening(recognitionLanguage())
                .firstOrNull { it is SpeechResult.Result || it is SpeechResult.Error }
                ?.let { result ->
                    when (result) {
                        is SpeechResult.Result -> result.text.takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
        }

    /**
     * The live brain, only when the user allows cloud processing (spec section 27 — it routes
     * caller words to the configured AI provider) and a runtime is actually selected. Any failure
     * here just means the deterministic goal loop runs alone.
     */
    private fun liveBrain(userName: String): ConversationEngine.CallBrain =
        runCatching {
            if (!store.readPrivacy().optBoolean("cloud_processing")) {
                return@runCatching ConversationEngine.CallBrain { _, _ -> null }
            }
            val app = application as com.mushrea.code.MushreaCodeApplication
            val target = app.runtimeRegistry.selected.value ?: return@runCatching ConversationEngine.CallBrain { _, _ -> null }
            AgentCallBrain(OpenCodeCallChannel(target), userName)
        }.getOrDefault(ConversationEngine.CallBrain { _, _ -> null })

    /** TTS through the project's manager; Android engine by default (cloud never required). */
    private fun ttsSpeaker(): ConversationEngine.Speaker =
        object : ConversationEngine.Speaker {
            override suspend fun speak(text: String) {
                tts.speak(text)
            }
        }

    private fun recognizerListener(): ConversationEngine.Listener =
        object : ConversationEngine.Listener {
            override suspend fun awaitUtterance(timeoutMillis: Long): String? = listenOnce(timeoutMillis)
        }

    /** Message-taking shapes: no goals, or the answer-policy purpose question alone. */
    private fun messageModeFor(task: CallTask): Boolean =
        task.isMessageTakingOnly ||
            (
                task.mode == CallTask.Mode.ANSWER_POLICY &&
                    task.goals.size == 1 &&
                    task.goals[0].question == "ماذا تحتاج؟"
            )

    private fun recognitionLanguage(): String =
        when (resources.configuration.locales[0].language) {
            "ar", "en", "fr", "es", "ja", "pt", "ru", "zh" -> resources.configuration.locales[0].toLanguageTag()
            else -> "ar-SA"
        }

    private fun finish(finalState: CallStateMachine.State) {
        store.writeLiveState(finalState, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, getString(R.string.call_agent_channel_name), NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun baseNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.call_agent_ongoing_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, CallAgentActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(0, getString(R.string.call_agent_action_take_over), serviceIntent(ACTION_TAKE_OVER))
            .addAction(0, getString(R.string.call_agent_action_end_call), serviceIntent(ACTION_END_CALL))
            .addAction(0, getString(R.string.call_agent_action_stop_agent), serviceIntent(ACTION_STOP_AGENT))
            .build()

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, CallAgentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun startInForeground(text: String) {
        val notification = baseNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, baseNotification(text))
    }

    private fun decodeTask(raw: String): CallTask {
        val json = JSONObject(raw)
        val goals =
            json.optJSONArray("goals")?.let { array ->
                (0 until array.length()).map { ConversationGoal(array.getJSONObject(it).getString("question")) }
            }.orEmpty()
        return CallTask(
            contactQuery = json.getString("contact"),
            goals = goals,
            knowledgeToRelay = json.optJSONArray("knowledge")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            mode = if (json.optString("mode") == "ANSWER_POLICY") CallTask.Mode.ANSWER_POLICY else CallTask.Mode.OUTGOING,
        )
    }

    companion object {
        private const val CHANNEL_ID = "call_agent"
        private const val NOTIFICATION_ID = 4711
        private const val CONNECT_WAIT_MILLIS = 35_000L
        private const val POLL_MILLIS = 500L
        private const val CALL_STATE_OFFHOOK = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
        private const val CALL_STATE_IDLE = android.telephony.TelephonyManager.CALL_STATE_IDLE

        const val ACTION_RUN_TASK = "com.mushrea.code.call.RUN_TASK"
        const val ACTION_ANSWER = "com.mushrea.code.call.ANSWER"
        const val ACTION_TAKE_OVER = "com.mushrea.code.call.TAKE_OVER"
        const val ACTION_END_CALL = "com.mushrea.code.call.END_CALL"
        const val ACTION_STOP_AGENT = "com.mushrea.code.call.STOP_AGENT"
        const val EXTRA_TASK = "task"
        const val EXTRA_NUMBER = "caller_number"

        /** Shared entry for the bridge/MCP path: launches the service with the parsed task. */
        fun start(
            context: Context,
            task: CallTask,
        ) {
            val json =
                JSONObject()
                    .put("contact", task.contactQuery)
                    .put("mode", task.mode.name)
                    .put("goals", org.json.JSONArray().apply { task.goals.forEach { put(JSONObject().put("question", it.question)) } })
                    .put("knowledge", org.json.JSONArray(task.knowledgeToRelay))
            val intent =
                Intent(context, CallAgentService::class.java)
                    .setAction(ACTION_RUN_TASK)
                    .putExtra(EXTRA_TASK, json.toString())
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
