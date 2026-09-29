package com.mushrea.code.device.call

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCallBrainTest {
    /** Scripted channel: pops one answer per ask; null = nothing landed (a miss). */
    private class FakeChannel(
        private val answers: ArrayDeque<Result<String?>>,
        val prompts: MutableList<String> = mutableListOf(),
    ) : CallConversationChannel {
        var opened = 0
        var closed = false

        override suspend fun open(): String? = if (closed) null else "session-1".also { opened++ }

        override suspend fun ask(
            sessionId: String,
            prompt: String,
        ): String? {
            prompts.add(prompt)
            val next = answers.removeFirstOrNull() ?: return null
            return next.getOrThrow()
        }
    }

    private fun state(
        purpose: String = "أين أنت الآن؟",
        vararg turns: Pair<ConversationState.Speaker, String>,
    ): ConversationState {
        var s =
            ConversationState(
                callTarget = "أحمد",
                callPurpose = purpose,
                knowledgeToRelay = listOf("سأصل بعد ساعة"),
            )
        turns.forEach { (speaker, text) ->
            s =
                when (speaker) {
                    ConversationState.Speaker.CALLER -> s.recordCaller(text, 0)
                    else -> s.recordAgent(text, 0)
                }
        }
        return s
    }

    @Test
    fun `the reply comes back sanitized to one speakable line`() =
        runBlocking {
            val channel = FakeChannel(ArrayDeque(listOf(Result.success("  \"حسناً، سأبلغ هشام.\"  \nسطر ثانٍ  "))))
            val brain = AgentCallBrain(channel, userName = "هشام")

            val reply = brain.replyTo("لماذا تسأل؟", state(ConversationState.Speaker.CALLER to "لماذا تسأل؟"))

            assertEquals(1, channel.prompts.size)
            // First non-blank line only, quotes stripped.
            assertEquals("حسناً، سأبلغ هشام.", reply)
        }

    @Test
    fun `the prompt carries the task, knowledge, goals and hard rules`() =
        runBlocking {
            val channel = FakeChannel(ArrayDeque(listOf(Result.success("تم"))))
            val brain = AgentCallBrain(channel, userName = "هشام")

            brain.replyTo("أنا في تعز", state(ConversationState.Speaker.CALLER to "أنا في تعز"))
            val prompt = channel.prompts.first()

            assertTrue(prompt.contains("هشام"))
            assertTrue(prompt.contains("أين أنت الآن؟"))
            assertTrue(prompt.contains("سأصل بعد ساعة"))
            assertTrue(prompt.contains("أنا في تعز"))
            assertTrue(prompt.contains("لا تخترع"))
            assertTrue(prompt.contains("رمز تحقق"))
            assertTrue(prompt.contains("سطر واحد قصير"))
        }

    @Test
    fun `two silent rounds disable the brain for the rest of the call`() =
        runBlocking {
            val channel =
                FakeChannel(
                    ArrayDeque(
                        listOf(Result.success(null as String?), Result.success(null as String?), Result.success("رد")),
                    ),
                )
            val brain = AgentCallBrain(channel, userName = "هشام")

            assertNull(brain.replyTo("هلا", state(ConversationState.Speaker.CALLER to "هلا")))
            assertNull(brain.replyTo("هلا", state(ConversationState.Speaker.CALLER to "هلا")))
            // After two misses the brain is off: the third turn returns null without even asking.
            assertNull(brain.replyTo("هلا", state(ConversationState.Speaker.CALLER to "هلا")))
            assertEquals(2, channel.prompts.size)
        }

    @Test
    fun `a throwing channel disables immediately`() =
        runBlocking {
            val channel =
                FakeChannel(ArrayDeque(listOf(Result.failure(IllegalStateException("no model configured")))))
            val brain = AgentCallBrain(channel, userName = "هشام")

            assertNull(brain.replyTo("هلا", state(ConversationState.Speaker.CALLER to "هلا")))
            assertNull(brain.replyTo("هلا مرة أخرى", state(ConversationState.Speaker.CALLER to "هلا")))
            assertEquals(1, channel.prompts.size)
        }

    @Test
    fun `an unreachable channel disables on first use`() =
        runBlocking {
            val channel = FakeChannel(ArrayDeque(emptyList())).apply { closed = true }
            val brain = AgentCallBrain(channel, userName = "هشام")

            assertNull(brain.replyTo("هلا", state(ConversationState.Speaker.CALLER to "هلا")))
            assertEquals(0, channel.prompts.size)
        }
}
