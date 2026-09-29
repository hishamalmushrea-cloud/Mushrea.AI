package com.mushrea.code.device.call

import com.mushrea.code.core.api.PromptRequest
import com.mushrea.code.runtime.OpenCodeBackend
import kotlinx.coroutines.delay

/**
 * The real [CallConversationChannel]: a dedicated session on the currently selected runtime
 * (OpenCode/Claude/Antigravity/Codex target), driven through the same backend the chat UI uses.
 * Provider and model are left to the runtime's defaults; a reply that never lands inside the
 * window reads as a miss — the brain decides what to do with those.
 */
class OpenCodeCallChannel(
    private val backend: OpenCodeBackend,
) : CallConversationChannel {

    override suspend fun open(): String? =
        runCatching { backend.createSession(title = SESSION_TITLE).id }.getOrNull()

    override suspend fun ask(
        sessionId: String,
        prompt: String,
    ): String? {
        val before =
            runCatching { backend.listMessages(sessionId).size }.getOrDefault(0)
        backend.sendMessage(sessionId, PromptRequest(text = prompt))
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
            val messages = runCatching { backend.listMessages(sessionId) }.getOrNull() ?: continue
            val reply =
                messages
                    .drop(before)
                    .lastOrNull { it.info.role != "user" && it.info.error == null }
                    ?.text
                    ?.trim()
            if (!reply.isNullOrBlank()) return reply
        }
        return null
    }

    private companion object {
        const val SESSION_TITLE = "mushrea-call-agent"
        const val REPLY_TIMEOUT_MILLIS = 9_000L
        const val POLL_MILLIS = 600L
    }
}
