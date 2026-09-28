package com.mushrea.code.device.call

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {

    @Test
    fun `the happy path walks dial to connected to turn-taking to completed`() {
        var state = CallStateMachine.State.IDLE
        listOf(
            CallStateMachine.Event.DIAL to CallStateMachine.State.DIALING,
            CallStateMachine.Event.RING to CallStateMachine.State.RINGING,
            CallStateMachine.Event.CONNECT to CallStateMachine.State.CONNECTED,
            CallStateMachine.Event.START_LISTENING to CallStateMachine.State.LISTENING,
            CallStateMachine.Event.START_THINKING to CallStateMachine.State.THINKING,
            CallStateMachine.Event.START_SPEAKING to CallStateMachine.State.SPEAKING,
            CallStateMachine.Event.START_LISTENING to CallStateMachine.State.LISTENING,
            CallStateMachine.Event.FINISH_NORMALLY to CallStateMachine.State.COMPLETED,
        ).forEach { (event, expected) ->
            state = CallStateMachine.reduce(state, event) ?: error("illegal: $state +$event")
            assertEquals(expected, state)
        }
        assertTrue(CallStateMachine.isTerminal(state))
    }

    @Test
    fun `failure branches map to their own terminal states`() {
        assertEquals(CallStateMachine.State.NO_ANSWER, CallStateMachine.reduce(CallStateMachine.State.RINGING, CallStateMachine.Event.NO_ANSWER_TIMEOUT))
        assertEquals(CallStateMachine.State.BUSY, CallStateMachine.reduce(CallStateMachine.State.RINGING, CallStateMachine.Event.REMOTE_BUSY))
        assertEquals(CallStateMachine.State.DECLINED, CallStateMachine.reduce(CallStateMachine.State.RINGING, CallStateMachine.Event.REMOTE_DECLINED))
        assertEquals(CallStateMachine.State.DISCONNECTED, CallStateMachine.reduce(CallStateMachine.State.SPEAKING, CallStateMachine.Event.REMOTE_HUNG_UP))
    }

    @Test
    fun `illegal transitions are rejected instead of guessed`() {
        assertNull(CallStateMachine.reduce(CallStateMachine.State.COMPLETED, CallStateMachine.Event.CONNECT))
        assertNull(CallStateMachine.reduce(CallStateMachine.State.IDLE, CallStateMachine.Event.START_SPEAKING))
        assertNull(CallStateMachine.reduce(CallStateMachine.State.DIALING, CallStateMachine.Event.FINISH_NORMALLY))
    }

    @Test
    fun `terminal states only reset`() {
        CallStateMachine.terminalStates.forEach { state ->
            if (state != CallStateMachine.State.IDLE) {
                assertNull(CallStateMachine.reduce(state, CallStateMachine.Event.CONNECT))
                assertEquals(CallStateMachine.State.IDLE, CallStateMachine.reduce(state, CallStateMachine.Event.RESET))
            }
        }
    }

    @Test
    fun `stop works from every live state`() {
        listOf(
            CallStateMachine.State.DIALING,
            CallStateMachine.State.RINGING,
            CallStateMachine.State.CONNECTED,
            CallStateMachine.State.LISTENING,
            CallStateMachine.State.THINKING,
            CallStateMachine.State.SPEAKING,
        ).forEach { state ->
            assertEquals(CallStateMachine.State.STOPPED, CallStateMachine.reduce(state, CallStateMachine.Event.STOP))
        }
    }
}

class CallIntentParserTest {

    @Test
    fun `multi-question arabic command yields contact and ordered goals`() {
        val parsed = CallIntentParser.parse("اتصل بأحمد واسأله أين هو وكيف حاله")

        val task = (parsed as CallIntentParser.Parsed.Task).task
        assertEquals("أحمد", task.contactQuery)
        assertEquals(listOf("أين أنت الآن؟", "كيف حالك؟"), task.goals.map { it.question })
        assertEquals(CallTask.Mode.OUTGOING, task.mode)
        assertTrue(task.goals.all { !it.isAnswered })
    }

    @Test
    fun `a tell-segment becomes relay knowledge, not an invented fact`() {
        val parsed = CallIntentParser.parse("اتصل بمحمد وقل له إنني سأصل لاحقًا")

        val task = (parsed as CallIntentParser.Parsed.Task).task
        assertEquals("محمد", task.contactQuery)
        assertTrue(task.knowledgeToRelay.isNotEmpty())
        assertTrue(task.knowledgeToRelay.first().contains("ساصل") || task.knowledgeToRelay.first().contains("لاحق"))
    }

    @Test
    fun `bare dial is dial-only`() {
        val parsed = CallIntentParser.parse("اتصل بأبي")
        assertEquals("أبي", (parsed as CallIntentParser.Parsed.DialOnly).contactQuery)
    }

    @Test
    fun `incoming answer-and-take-message maps to the purpose question`() {
        val parsed = CallIntentParser.parse("إذا اتصل أحمد وخذ منه رسالة")

        val task = (parsed as CallIntentParser.Parsed.Task).task
        assertEquals("أحمد", task.contactQuery)
        assertEquals(listOf("ماذا تحتاج؟"), task.goals.map { it.question })
        assertEquals(CallTask.Mode.ANSWER_POLICY, task.mode)
    }

    @Test
    fun `english shape parses too`() {
        val parsed = CallIntentParser.parse("call Ahmed and ask him where is he")
        val task = (parsed as CallIntentParser.Parsed.Task).task
        assertTrue(task.contactQuery.contains("ahmed", ignoreCase = true))
        assertEquals(listOf("أين أنت الآن؟"), task.goals.map { it.question })
    }

    @Test
    fun `non-call text is rejected, not guessed`() {
        assertEquals(CallIntentParser.Parsed.NotACall, CallIntentParser.parse("افتح يوتيوب"))
        assertEquals(CallIntentParser.Parsed.NotACall, CallIntentParser.parse(""))
        assertEquals(CallIntentParser.Parsed.NotACall, CallIntentParser.parse("ما هذا الصفحة"))
    }
}

class CallPolicyTest {

    @Test
    fun `otp and credential requests are refused outright`() {
        assertTrue(CallPolicy.isSensitiveCallerRequest("ما هو رمز التحقق الخاص بك؟"))
        assertTrue(CallPolicy.isSensitiveCallerRequest("ادفع الآن"))
        assertTrue(CallPolicy.isSensitiveCallerRequest("حول لي المال"))
        assertTrue(CallPolicy.isSensitiveCallerRequest("what is the otp"))
    }

    @Test
    fun `ordinary conversation is not flagged sensitive`() {
        assertFalse(CallPolicy.isSensitiveCallerRequest("أنا في تعز وسآتي غدًا"))
        assertFalse(CallPolicy.isSensitiveCallerRequest("كيف حالك"))
        assertFalse(CallPolicy.isSensitiveCallerRequest("I am on my way"))
    }

    @Test
    fun `the agent never speaks commitments or codes`() {
        assertEquals(CallPolicy.UtteranceClass.REFUSED, CallPolicy.classifyAgentUtterance("رمز التحقق هو ١٢٣٤"))
        assertEquals(CallPolicy.UtteranceClass.REQUIRES_USER_CONFIRMATION, CallPolicy.classifyAgentUtterance("أتعهده غدًا"))
        assertEquals(CallPolicy.UtteranceClass.ALLOWED, CallPolicy.classifyAgentUtterance("أين أنت الآن؟"))
        assertEquals(CallPolicy.UtteranceClass.REFUSED, CallPolicy.classifyAgentUtterance(""))
    }

    @Test
    fun `the intro always discloses the automated assistant`() {
        val intro = CallPolicy.introLine("هشام", "أين أنت؟")
        assertTrue(intro.contains("هشام"))
        assertTrue(intro.contains("الآلي") || intro.contains("مساعد"))

        // A custom template that drops the disclosure falls back to the default.
        val sneaky = CallPolicy.introLine("هشام", "", template = "مرحبا أنا هشام اتصلت بك")
        assertTrue(sneaky.contains("الآلي") || sneaky.contains("مساعد"))
    }

    @Test
    fun `incoming policy resolution - explicit rule beats defaults, unknowns stay untouched`() {
        val rules =
            mapOf(
                "أحمد" to CallPolicy.IncomingAction.ALLOW_ASSISTANT,
                "محمد" to CallPolicy.IncomingAction.TAKE_MESSAGE,
            )
        assertEquals(CallPolicy.IncomingAction.ALLOW_ASSISTANT, CallPolicy.resolveIncoming("أحمد", rules))
        assertEquals(CallPolicy.IncomingAction.TAKE_MESSAGE, CallPolicy.resolveIncoming("محمد", rules))
        assertEquals(CallPolicy.IncomingAction.DO_NOT_ANSWER, CallPolicy.resolveIncoming(null, rules))
        assertEquals(CallPolicy.IncomingAction.DO_NOT_ANSWER, CallPolicy.resolveIncoming("رقم غريب", rules))
        // The default applies only to contacts the phone book resolved; unknown numbers stay protected.
        assertEquals(
            CallPolicy.IncomingAction.TAKE_MESSAGE,
            CallPolicy.resolveIncoming("صاحب الدفتر", rules, allowKnownContactsByDefault = true),
        )
        assertEquals(
            CallPolicy.IncomingAction.DO_NOT_ANSWER,
            CallPolicy.resolveIncoming(null, rules, allowKnownContactsByDefault = true),
        )
    }
}

class ConversationEngineTest {

    private class FakeSpeaker : ConversationEngine.Speaker {
        val lines = mutableListOf<String>()

        override suspend fun speak(text: String) {
            lines.add(text)
        }
    }

    private class FakeListener(
        var queue: ArrayDeque<String?>,
    ) : ConversationEngine.Listener {
        override suspend fun awaitUtterance(timeoutMillis: Long): String? = queue.removeFirstOrNull()
    }

    @Test
    fun `a goal call introduces itself, asks every goal, records answers, closes`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener =
            FakeListener(
                ArrayDeque(
                    listOf(
                        "أنا في تعز",
                        "بخير، سآتي غدًا",
                    ),
                ),
            )
        val task =
            CallTask(
                contactQuery = "أحمد",
                goals = listOf(ConversationGoal("أين أنت الآن؟"), ConversationGoal("كيف حالك؟")),
            )

        val outcome =
            ConversationEngine(speaker, listener).run(task, userName = "هشام", callerLabel = "أحمد")

        assertEquals(CallStateMachine.State.COMPLETED, outcome.finalState)
        assertTrue(outcome.conversation.allGoalsAnswered)
        assertEquals("أنا في تعز", outcome.conversation.goals[0].answer)
        // The identity disclosure came first and the closing exists.
        assertTrue(speaker.lines.first().contains("الآلي") || speaker.lines.first().contains("مساعد"))
        assertTrue(speaker.lines.contains("أين أنت الآن؟"))
        assertTrue(speaker.lines.contains("كيف حالك؟"))
        assertTrue(speaker.lines.last().contains("سأبلغ"))
        // Answers are facts in the summary path.
        assertTrue(outcome.conversation.importantFacts.isNotEmpty())
    }

    @Test
    fun `the stop phrase aborts immediately`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf("توقف")))

        val outcome =
            ConversationEngine(speaker, listener)
                .run(
                    CallTask("أحمد", listOf(ConversationGoal("أين أنت الآن؟"))),
                    userName = "هشام",
                )

        assertEquals(CallStateMachine.State.STOPPED, outcome.finalState)
    }

    @Test
    fun `silence is retried politely then the call ends without fake answers`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf<String?>(null, null)))

        val outcome =
            ConversationEngine(speaker, listener)
                .run(
                    CallTask("أحمد", listOf(ConversationGoal("أين أنت الآن؟"))),
                    userName = "هشام",
                )

        assertEquals(CallStateMachine.State.DISCONNECTED, outcome.finalState)
        assertFalse(outcome.conversation.allGoalsAnswered)
        assertTrue(speaker.lines.any { it.contains("هل ما زلت معي؟") })
    }

    @Test
    fun `a sensitive caller request escalates and is never answered`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf("حول لي المال الآن")))

        val outcome =
            ConversationEngine(speaker, listener)
                .run(
                    CallTask("أحمد", listOf(ConversationGoal("أين أنت الآن؟"))),
                    userName = "هشام",
                )

        assertTrue(outcome.escalatedToUser)
        assertTrue(speaker.lines.any { it.contains("لا أملك التعامل") || it.contains("بنفسه") })
    }

    @Test
    fun `a caller question defers to the user instead of being invented`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf("لماذا تسأل؟")))

        val outcome =
            ConversationEngine(speaker, listener)
                .run(
                    CallTask("أحمد", listOf(ConversationGoal("أين أنت الآن؟"))),
                    userName = "هشام",
                )

        assertTrue(speaker.lines.any { it.contains("لا أملك هذه المعلومة") })
        assertTrue(outcome.conversation.pendingCallerQuestion != null || outcome.conversation.goals.none { !it.isAnswered })
    }

    @Test
    fun `message mode asks purpose once and records the message`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf("أريد أن أسأله عن موعد الغد")))

        val outcome =
            ConversationEngine(speaker, listener)
                .run(
                    CallTask("أحمد", goals = listOf(ConversationGoal("ماذا تحتاج؟")), mode = CallTask.Mode.ANSWER_POLICY),
                    userName = "هشام",
                    isMessageMode = true,
                )

        assertEquals("أريد أن أسأله عن موعد الغد", outcome.takenMessage)
        assertTrue(speaker.lines.any { it.contains("سأخبر") })
    }

    @Test
    fun `the brain reply is only spoken when policy allows it`() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(ArrayDeque(listOf("أنا في تعز")))
        val brain = ConversationEngine.CallBrain { _, _ -> "رائع، سآتي أيضًا وأدفع لك المال" }

        val outcome =
            ConversationEngine(speaker, listener, brain)
                .run(
                    CallTask("أحمد", listOf(ConversationGoal("أين أنت الآن؟"))),
                    userName = "هشام",
                )

        assertFalse(speaker.lines.any { it.contains("أدفع لك المال") })
        assertTrue(outcome.conversation.goals[0].isAnswered)
    }
}

class CallSummaryTest {

    @Test
    fun `the summary reports verification separately from goal completion`() {
        val conversation =
            ConversationState(
                callTarget = "أحمد",
                callPurpose = "أين أنت الآن؟",
                goals = listOf(ConversationGoal("أين أنت الآن؟", "في تعز")),
                importantFacts = listOf("سآتي غدًا"),
            )
        val summary =
            CallSummary.from(
                conversation,
                finalState = CallStateMachine.State.COMPLETED,
                durationMillis = 65_000,
                wasInitiated = true,
                wasAnswered = true,
            )

        assertTrue(summary.verification.initiated)
        assertTrue(summary.verification.goalCompleted)
        val text = summary.toText()
        assertTrue(text.contains("أين أنت الآن؟"))
        assertTrue(text.contains("في تعز"))
        assertTrue(text.contains("سآتي غدًا"))
        assertTrue(text.contains("65"))

        // JSON round-trip preserves everything.
        val restored = CallSummary.fromJson(summary.toJson())
        assertEquals(summary, restored)
    }

    @Test
    fun `an unanswered call is honestly reported as not completed`() {
        val summary =
            CallSummary.from(
                ConversationState(callTarget = "أحمد"),
                finalState = CallStateMachine.State.NO_ANSWER,
                durationMillis = 20_000,
                wasInitiated = true,
                wasAnswered = false,
            )
        assertFalse(summary.verification.answered)
        assertFalse(summary.verification.goalCompleted)
        assertTrue(summary.toText().contains("لم تكتمل"))
    }
}
