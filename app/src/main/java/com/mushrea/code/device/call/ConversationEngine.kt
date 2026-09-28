package com.mushrea.code.device.call

import com.mushrea.code.device.AppResolver
import com.mushrea.code.device.StopPhrases

/**
 * The turn loop of the conversation agent (spec sections 4-11, 20-21): introduce itself as an
 * automated assistant, relay the user's provided knowledge, work through the ordered goals, and
 * finish politely — never inventing facts, escalating sensitive requests, staying silence-honest
 * after two unheard turns, and aborting the instant a stop phrase is heard.
 *
 * The engine is pure: speech in/out are ports, so unit tests drive it with fakes and the service
 * wires them to the recognizer and TTS. [CallBrain] is the hook where the LLM agent core can
 * propose a reply; the engine only uses a model reply when the policy classifies it ALLOWED, and
 * otherwise follows the task literally — a deterministic agent with no imagination by design.
 */
class ConversationEngine(
    private val speaker: Speaker,
    private val listener: Listener,
    private val brain: CallBrain = CallBrain { _, _ -> null },
) {
    interface Speaker {
        /** Speaks a line; returns when the audio is done. */
        suspend fun speak(text: String)
    }

    interface Listener {
        /**
         * Waits for one caller utterance; null means "heard nothing usable in the window"
         * (silence, noise, unclear speech — section 22: silence is never taken as an answer).
         */
        suspend fun awaitUtterance(timeoutMillis: Long): String?
    }

    fun interface CallBrain {
        /** A model-proposed reply, or null when no model is available this turn. */
        suspend fun replyTo(
            utterance: String,
            state: ConversationState,
        ): String?
    }

    data class Outcome(
        val conversation: ConversationState,
        val finalState: CallStateMachine.State,
        val escalatedToUser: Boolean,
        val takenMessage: String?,
    )

    suspend fun run(
        task: CallTask,
        userName: String,
        identityTemplate: String? = null,
        callerLabel: String? = null,
        isMessageMode: Boolean = false,
        onStateChange: (CallStateMachine.State, ConversationState) -> Unit = { _, _ -> },
    ): Outcome {
        val session = Session(task, userName, callerLabel)
        session.onStateChange = onStateChange
        val (machine, escalated, takenMessage) = run(session, identityTemplate, isMessageMode)
        return Outcome(session.conversation, machine, escalated, takenMessage)
    }

    private suspend fun run(
        session: Session,
        identityTemplate: String?,
        isMessageMode: Boolean,
    ): Triple<CallStateMachine.State, Boolean, String?> {
        runIntro(session, identityTemplate)
        return if (isMessageMode) runMessageMode(session) else runGoalLoop(session)
    }

    // -- Session state shared by the phases ----------------------------------

    private class Session(
        val task: CallTask,
        val userName: String,
        val callerLabel: String?,
    ) {
        var onStateChange: ((CallStateMachine.State, ConversationState) -> Unit)? = null

        var machine = CallStateMachine.State.CONNECTED
        var conversation =
            ConversationState(
                callTarget = callerLabel ?: task.contactQuery,
                callPurpose = task.goals.firstOrNull()?.question ?: "أخذ رسالة",
                goals = task.goals,
                knowledgeToRelay = task.knowledgeToRelay,
            )
        var escalated = false
        var takenMessage: String? = null
        val startedAt = System.currentTimeMillis()

        fun transition(event: CallStateMachine.Event) {
            CallStateMachine.reduce(machine, event)?.let {
                machine = it
                onStateChange?.invoke(it, conversation)
            }
        }

        fun escalateLine(): String = "هذا لا أملك التعامل معه، سيناقشك $userName بنفسه."
    }

    private suspend fun say(
        session: Session,
        text: String,
    ) {
        when (CallPolicy.classifyAgentUtterance(text)) {
            CallPolicy.UtteranceClass.REFUSED, CallPolicy.UtteranceClass.REQUIRES_USER_CONFIRMATION -> session.escalated = true
            CallPolicy.UtteranceClass.ALLOWED -> Unit
        }
        session.transition(CallStateMachine.Event.START_SPEAKING)
        session.conversation = session.conversation.recordAgent(text, System.currentTimeMillis() - session.startedAt)
        speaker.speak(text)
        session.transition(CallStateMachine.Event.START_LISTENING)
    }

    /** One listening window; null = silence. Sets STOPPED when the caller says stop. */
    private suspend fun listenOnce(session: Session): String? {
        session.transition(CallStateMachine.Event.START_LISTENING)
        val utterance = listener.awaitUtterance(UTTERANCE_TIMEOUT_MILLIS) ?: return null
        if (StopPhrases.isStopCommand(utterance)) {
            session.transition(CallStateMachine.Event.STOP)
            session.conversation = session.conversation.recordSystem("STOP", System.currentTimeMillis() - session.startedAt)
            return null
        }
        session.conversation = session.conversation.recordCaller(utterance, System.currentTimeMillis() - session.startedAt)
        return utterance
    }

    /** Disclosure first (sections 4/31), then every relay item the user provided. */
    private suspend fun runIntro(
        session: Session,
        identityTemplate: String?,
    ) {
        say(session, CallPolicy.introLine(session.userName, session.conversation.callPurpose, identityTemplate))
        session.task.knowledgeToRelay.forEach { item ->
            say(session, item)
            session.conversation = session.conversation.markKnowledgeRelaid(item)
        }
    }

    /** Incoming-call mode: ask the purpose once, record the message, close politely (16). */
    private suspend fun runMessageMode(session: Session): Triple<CallStateMachine.State, Boolean, String?> {
        say(session, "كيف يمكنني مساعدتك؟")
        var attempts = 0
        while (session.takenMessage.isNullOrBlank() && attempts < SILENCE_RETRIES && session.machine != CallStateMachine.State.STOPPED) {
            val heard = listenOnce(session)
            when {
                session.machine == CallStateMachine.State.STOPPED -> Unit
                heard != null && CallPolicy.isSensitiveCallerRequest(heard) -> {
                    say(session, session.escalateLine())
                    session.conversation = session.conversation.addFact("طلب حساس من المتصل: ${heard.take(60)}")
                }
                heard != null -> {
                    session.takenMessage = heard
                    session.conversation = session.conversation.addFact(heard)
                }
                else -> {
                    attempts++
                    say(session, if (attempts < SILENCE_RETRIES) "هل ما زلت معي؟" else "لم أتمكن من سماعك، سيتم إبلاغ ${session.userName} باتصالك.")
                }
            }
        }
        say(session, "سأخبر ${session.userName} أنك اتصلت." + (session.takenMessage?.let { " رسالتك: $it." } ?: ""))
        val finalState =
            if (session.machine == CallStateMachine.State.STOPPED) CallStateMachine.State.STOPPED else CallStateMachine.State.COMPLETED
        return Triple(finalState, session.escalated, session.takenMessage)
    }

    /** Goal loop: ask, listen, verify; end when goals complete, the caller hangs up, or stop. */
    private suspend fun runGoalLoop(session: Session): Triple<CallStateMachine.State, Boolean, String?> {
        while (!CallStateMachine.isTerminal(session.machine)) {
            val goal = session.conversation.nextUnansweredGoal ?: run {
                say(session, "شكرا لك، سأبلغ ${session.userName} بكل ما ذكرته.")
                session.transition(CallStateMachine.Event.FINISH_NORMALLY)
                null
            } ?: break
            say(session, goal.question)
            askSingleGoal(session, goal)
        }
        val finalState =
            if (session.machine == CallStateMachine.State.STOPPED) CallStateMachine.State.STOPPED else session.machine
        return Triple(finalState, session.escalated, session.takenMessage)
    }

    /** One goal's listen/answer cycle with the polite silence retries (section 21). */
    private suspend fun askSingleGoal(
        session: Session,
        goal: ConversationGoal,
    ) {
        var attempts = 0
        while (session.conversation.nextUnansweredGoal === goal &&
            attempts < SILENCE_RETRIES &&
            !CallStateMachine.isTerminal(session.machine)
        ) {
            val heard = listenOnce(session)
            when {
                session.machine == CallStateMachine.State.STOPPED -> Unit
                heard == null -> {
                    attempts++
                    say(session, if (attempts < SILENCE_RETRIES) "هل ما زلت معي؟" else "يبدو أن الاتصال ضعيف، سأختم المكالمة الآن.")
                    if (attempts >= SILENCE_RETRIES) session.transition(CallStateMachine.Event.REMOTE_HUNG_UP)
                }
                CallPolicy.isSensitiveCallerRequest(heard) -> {
                    session.escalated = true
                    say(session, session.escalateLine())
                    session.conversation = session.conversation.addFact("طلب حساس من المتصل: ${heard.take(60)}")
                    session.conversation = session.conversation.answerCurrentGoal(heard.take(120))
                }
                isCallerQuestion(heard) -> {
                    session.conversation = session.conversation.setPendingCallerQuestion(heard)
                    say(session, "لا أملك هذه المعلومة الآن، سأسأل ${session.userName} وأعود إليك بالجواب.")
                    session.conversation = session.conversation.answerCurrentGoal(heard.take(120))
                }
                else -> {
                    brain.replyTo(heard, session.conversation)
                        ?.takeIf { CallPolicy.classifyAgentUtterance(it) == CallPolicy.UtteranceClass.ALLOWED }
                        ?.let { say(session, it) }
                    session.conversation = session.conversation.answerCurrentGoal(heard)
                    session.conversation = session.conversation.addFact(heard)
                }
            }
        }
    }

    /** A caller question defers to the user — the agent never answers what it does not know (11). */
    private fun isCallerQuestion(utterance: String): Boolean {
        val normalized = AppResolver.normalize(utterance)
        return utterance.contains("؟") || utterance.contains("?") ||
            listOf("لماذا", "كيف يمكن", "متى س", "هل يمكن", "هل س", "why", "how can", "when will you", "can you")
                .any(normalized::contains)
    }

    private companion object {
        const val UTTERANCE_TIMEOUT_MILLIS = 7000L
        const val SILENCE_RETRIES = 2
    }
}
