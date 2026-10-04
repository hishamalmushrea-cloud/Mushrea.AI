package com.mushrea.code.core.permission

/**
 * The user's answer to a permission request, in the one vocabulary every agent accepts.
 *
 * It was declared next to the OpenCode backend, but it is not OpenCode's: Claude Code, Codex and
 * Antigravity each translate it to their own protocol words (`allow`/`deny`,
 * `acceptForSession`/`decline`), and the notification actions build it. It therefore belongs to the
 * shared permission vocabulary, which is what lets `core` build a prompt without importing a
 * runtime.
 */
enum class PermissionResponse(val apiValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}
