package com.mushrea.code.runtime.local

import com.mushrea.code.core.permission.PermissionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ClaudePermissionBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pollPending surfaces a new permission request once`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"ls"}""",
                permissionLabel = "Bash",
            ),
        )

        val first = bridge.pollPending()
        assertEquals(1, first.size)
        assertEquals(requestId, first.single().requestId)
        assertEquals("Bash", first.single().toolName)
        assertTrue(first.single().permissionLabel.contains("Bash"))

        assertTrue(bridge.pollPending().isEmpty())
    }

    @Test
    fun `pendingRequests recovers a request the event stream already carried`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "AskUserQuestion",
                toolInputJson = """{"questions":[{"question":"Pick?","options":[{"label":"A"}]}]}""",
                permissionLabel = "AskUserQuestion",
            ),
        )

        // The watcher already emitted it; a chat opened later must still find it on disk.
        bridge.pollPending()
        assertEquals(listOf(requestId), bridge.pendingRequests().map { it.requestId })
    }

    @Test
    fun `pendingRequests does not consume the watcher's one-shot emission`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )

        assertEquals(listOf(requestId), bridge.pendingRequests().map { it.requestId })
        assertEquals(listOf(requestId), bridge.pollPending().map { it.requestId })
    }

    @Test
    fun `respond writes a response the hook can read`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()

        assertTrue(bridge.respond(requestId, PermissionResponse.ONCE, remember = false))
        val response = bridge.readResponse(requestId)
        assertNotNull(response)
        assertEquals("allow", response!!.decision)
        assertFalse(response.remember)
    }

    @Test
    fun `reject writes deny`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.REJECT, remember = false, message = "nope"))
        assertEquals("deny", bridge.readResponse(requestId)!!.decision)
        assertEquals("nope", bridge.readResponse(requestId)!!.message)
    }

    @Test
    fun `always allow records a remembered rule`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"git status"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))
        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertFalse(bridge.isAlwaysAllowed("Write", """{"file_path":"a"}"""))
    }

    @Test
    fun `answer question writes answers payload`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "AskUserQuestion",
                toolInputJson = """{"questions":[{"question":"Pick?","options":[{"label":"A"}]}]}""",
                permissionLabel = "AskUserQuestion",
            ),
        )
        bridge.pollPending()
        assertTrue(
            bridge.answerQuestion(
                requestId,
                questionsJson = """[{"question":"Pick?","options":[{"label":"A"}]}]""",
                answers = mapOf("Pick?" to "A"),
            ),
        )
        val response = bridge.readResponse(requestId)!!
        assertEquals("allow", response.decision)
        assertNotNull(response.answersJson)
        assertTrue(response.answersJson!!.contains("Pick?"))
    }

    @Test
    fun `unknown request id returns false`() {
        val bridge = ClaudePermissionBridge(folder.root)
        assertFalse(bridge.respond("missing", PermissionResponse.ONCE, remember = false))
        assertNull(bridge.readResponse("missing"))
    }

    @Test
    fun `hook settings fragment marks mushrea-code permission hook`() {
        val fragment = ClaudePermissionHooks.settingsFragment()
        assertTrue(fragment.contains("PermissionRequest"))
        assertTrue(fragment.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
        assertTrue(fragment.contains("mushrea-code-claude-permission"))
    }

    @Test
    fun `mergeSettings injects hook without dropping existing hooks`() {
        val existing =
            """
            {
              "hooks": {
                "Stop": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "echo hi" }] }]
              }
            }
            """.trimIndent()
        val merged = ClaudePermissionHooks.mergeSettingsJson(existing)
        assertTrue(merged.contains("echo hi"))
        assertTrue(merged.contains("PermissionRequest"))
        assertTrue(merged.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
    }

    @Test
    fun `an always-rule remembers a command prefix, not the whole command`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val request =
            ClaudePermissionBridge.Request(
                requestId = UUID.randomUUID().toString(),
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"git status --short"}""",
                permissionLabel = "Bash",
            )
        bridge.writeGuestRequest(request)
        bridge.pollPending()
        bridge.respond(request.requestId, PermissionResponse.ALWAYS, remember = true)

        // The rule keeps "git status" and covers other forms of it...
        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status --porcelain"}"""))
        // ...but not another command, and not another tool.
        assertFalse(bridge.isAlwaysAllowed("Bash", """{"command":"git commit -m x"}"""))
        assertFalse(bridge.isAlwaysAllowed("Write", """{"command":"git status"}"""))
    }

    @Test
    fun `a fresh bridge reads the rules the previous one wrote`() {
        val first = ClaudePermissionBridge(folder.root)
        val request =
            ClaudePermissionBridge.Request(
                requestId = UUID.randomUUID().toString(),
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Write",
                toolInputJson = """{"file_path":"/tmp/a"}""",
                permissionLabel = "Write",
            )
        first.writeGuestRequest(request)
        first.pollPending()
        first.respond(request.requestId, PermissionResponse.ALWAYS, remember = true)

        // Rules are on disk in the guest bridge, which is what makes them survive an app restart.
        val second = ClaudePermissionBridge(folder.root)
        assertTrue(second.isAlwaysAllowed("Write", """{"file_path":"/tmp/b"}"""))
    }

    @Test
    fun `a question is never answered from an always-rule`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val permission =
            ClaudePermissionBridge.Request(
                requestId = UUID.randomUUID().toString(),
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"ls"}""",
                permissionLabel = "Bash",
            )
        bridge.writeGuestRequest(permission)
        bridge.pollPending()
        bridge.respond(permission.requestId, PermissionResponse.ALWAYS, remember = true)
        assertTrue(bridge.shouldAutoAllow(permission))

        val question =
            permission.copy(
                requestId = UUID.randomUUID().toString(),
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "Bash",
            )
        assertFalse("a rule must never answer a question", bridge.shouldAutoAllow(question))
    }
}
