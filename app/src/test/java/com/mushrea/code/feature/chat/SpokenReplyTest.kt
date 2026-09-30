package com.mushrea.code.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenReplyTest {
    @Test
    fun `picks the last assistant message after the last user prompt`() {
        val reply1 = ChatMessage(id = "r1", isUser = false, parts = listOf(ChatPart.Text("t1", "الرد الأول")))
        val prompt = ChatMessage(id = "u1", isUser = true)
        val reply2 = ChatMessage(id = "r2", isUser = false, parts = listOf(ChatPart.Text("t2", "الرد الثاني")))
        assertEquals("r2", latestUnspokenReply(listOf(reply1, prompt, reply2), emptySet())?.id)
    }

    @Test
    fun `skips replies that were already announced`() {
        val prompt = ChatMessage(id = "u1", isUser = true)
        val reply = ChatMessage(id = "r1", isUser = false, parts = listOf(ChatPart.Text("t1", "نص")))
        assertNull(latestUnspokenReply(listOf(prompt, reply), setOf("r1")))
    }

    @Test
    fun `a turn that produced no readable text reads nothing`() {
        val prompt = ChatMessage(id = "u1", isUser = true)
        val blank = ChatMessage(id = "r1", isUser = false, parts = listOf(ChatPart.Text("t1", "   ")))
        assertNull(latestUnspokenReply(listOf(prompt, blank), emptySet()))
    }

    @Test
    fun `an unreadable newest turn does not resurrect an older reply`() {
        val older = ChatMessage(id = "r1", isUser = false, parts = listOf(ChatPart.Text("t1", "قديم")))
        val prompt = ChatMessage(id = "u1", isUser = true)
        val blank = ChatMessage(id = "r2", isUser = false, parts = listOf(ChatPart.Text("t2", "")))
        assertNull(latestUnspokenReply(listOf(older, prompt, blank), emptySet()))
    }

    @Test
    fun `a chat with no user prompt yet reads its assistant message`() {
        val greeting = ChatMessage(id = "r0", isUser = false, parts = listOf(ChatPart.Text("t0", "مرحبا")))
        assertEquals("r0", latestUnspokenReply(listOf(greeting), emptySet())?.id)
    }
}
