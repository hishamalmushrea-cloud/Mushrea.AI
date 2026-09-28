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
        var machine = CallStateMachine.State.CONNECTED
        val startedAt = System.currentTimeMillis()
        var conversation =
            ConversationState(
                callTarget = callerLabel ?: task.contactQuery,
                callPurpose = task.goals.firstOrNull()?.question ?: "أخذ رسالة",
                goals = task.goals,
                knowledgeToRelay = task.knowledgeToRelay,
            )
        var escalated = false
        var takenMessage: String? = null

        fun transition(event: CallStateMachine.Event) {
            CallStateMachine.reduce(machine, event)?.let {
                machine = it
                onStateChange(it, conversation)
            }
        }

        suspend fun say(text: String) {
            when (CallPolicy.classifyAgentUtterance(text)) {
                CallPolicy.UtteranceClass.REFUSED, CallPolicy.UtteranceClass.REQUIRES_USER_CONFIRMATION -> {
                    escalated = true
                }
                CallPolicy.UtteranceClass.ALLOWED -> Unit
            }
            transition(CallStateMachine.Event.START_SPEAKING)
            conversation = conversation.recordAgent(text, System.currentTimeMillis() - startedAt)
            speaker.speak(text)
            transition(CallStateMachine.Event.START_LISTENING)
        }

        /** One listening window; null = silence. Sets STOPPED when the caller says stop. */
        suspend fun listenOnce(): String? {
            transition(CallStateMachine.Event.START_LISTENING)
            val utterance = listener.awaitUtterance(UTTERANCE_TIMEOUT_MILLIS) ?: return null
            if (StopPhrases.isStopCommand(utterance)) {
                transition(CallStateMachine.Event.STOP)
                conversation = conversation.recordSystem("STOP", System.currentTimeMillis() - startedAt)
                return null
            }
            conversation = conversation.recordCaller(utterance, System.currentTimeMillis() - startedAt)
            return utterance
        }

        fun escalateLine(): String = "هذا لا أملك التعامل معه، سيناقشك $userName بنفسه."

        // Opening: the identity disclosure is mandatory (sections 4/31).
        say(CallPolicy.introLine(userName, conversation.callPurpose, identityTemplate))
        task.knowledgeToRelay.forEach { item ->
            say(item)
            conversation = conversation.markKnowledgeRelaid(item)
        }

        if (isMessageMode) {
            say("كيف يمكنني مساعدتك؟")
            var attempts = 0
            while (takenMessage.isNullOrBlank() && attempts < SILENCE_RETRIES && machine != CallStateMachine.State.STOPPED) {
                val heard = listenOnce()
                when {
                    machine == CallStateMachine.State.STOPPED -> Unit
                    heard != null && CallPolicy.isSensitiveCallerRequest(heard) -> {
                        say(escalateLine())
                        conversation.addFact("طلب حساس من المتصل: ${heard.take(60)}")
                    }
                    heard != null -> {
                        takenMessage = heard
                        conversation.addFact(heard)
                    }
                    else -> {
                        attempts++
                        say(if (attempts < SILENCE_RETRIES) "هل ما زلت معي؟" else "لم أتمكن من سماعك، سيتم إبلاغ $userName باتصالك.")
                    }
                }
            }
            say("سأخبر $userName أنك اتصلت." + (takenMessage?.let { " رسالتك: $it." } ?: ""))
            return Outcome(conversation, CallStateMachine.State.COMPLETED, escalated, takenMessage)
        }

        // Goal loop: ask every unanswered question; verify answers actually arrived (section 24).
        while (!CallStateMachine.isTerminal(machine)) {
            val goal = conversation.nextUnansweredGoal ?: run {
                say("شكرا لك، سأبلغ $userName بكل ما ذكرته.")
                transition(CallStateMachine.Event.FINISH_NORMALLY)
                null
            } ?: break
            say(goal.question)
            var attempts = 0
            while (conversation.nextUnansweredGoal === goal && attempts < SILENCE_RETRIES && !CallStateMachine.isTerminal(machine)) {
                val heard = listenOnce()
                when {
                    machine == CallStateMachine.State.STOPPED -> Unit
                    heard == null -> {
                        attempts++
                        say(if (attempts < SILENCE_RETRIES) "هل ما زلت معي؟" else "يبدو أن الاتصال ضعيف، سأختم المكالمة الآن.")
                        if (attempts >= SILENCE_RETRIES) transition(CallStateMachine.Event.REMOTE_HUNG_UP)
                    }
                    CallPolicy.isSensitiveCallerRequest(heard) -> {
                        escalated = true
                        say(escalateLine())
                        conversation = conversation.addFact("طلب حساس من المتصل: ${heard.take(60)}")
                        conversation = conversation.answerCurrentGoal(heard.take(120))
                    }
                    isCallerQuestion(heard) -> {
                        conversation = conversation.setPendingCallerQuestion(heard)
                        say("لا أملك هذه المعلومة الآن، سأسأل $userName وأعود إليك بالجواب.")
                        conversation = conversation.answerCurrentGoal(heard.take(120))
                    }
                    else -> {
                        brain.replyTo(heard, conversation)
                            ?.takeIf { CallPolicy.classifyAgentUtterance(it) == CallPolicy.UtteranceClass.ALLOWED }
                            ?.let { say(it) }
                        conversation = conversation.answerCurrentGoal(heard)
                        conversation = conversation.addFact(heard)
                    }
                }
            }
        }

        val finalState = if (machine == CallStateMachine.State.STOPPED) CallStateMachine.State.STOPPED else machine
        return Outcome(conversation, finalState, escalated, takenMessage)
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
