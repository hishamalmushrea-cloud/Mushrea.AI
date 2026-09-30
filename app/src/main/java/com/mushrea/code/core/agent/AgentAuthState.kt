package com.mushrea.code.core.agent

/**
 * One sign-in vocabulary for every agent.
 *
 * Claude Code and Antigravity each grew a near-identical `State` sealed interface (Idle, Starting,
 * AwaitingBrowser, Verifying, SignedIn, Failed) while Codex only exposed a boolean, so every screen
 * asked "is this agent usable?" in its own dialect. This type is the single dialect; the runtime
 * layer translates each agent's own model into it and nothing else has to know which agent it is
 * looking at.
 *
 * `Unknown` is the honest default: an agent whose sign-in has not been read yet is neither signed
 * in nor signed out, and reporting "signed out" would flash a sign-in prompt over a session that is
 * already authenticated.
 */
sealed interface AgentAuthState {
    /** Not read yet (or the agent has no sign-in concept and the caller passed this deliberately). */
    data object Unknown : AgentAuthState

    /** Known to be signed out. */
    data object SignedOut : AgentAuthState

    /** A login flow is being launched. */
    data object Starting : AgentAuthState

    /** Waiting on the user in a browser; [url] is the page to open and [transcript] the CLI output so far. */
    data class AwaitingBrowser(val url: String, val transcript: String = "") : AgentAuthState

    /** The code/redirect came back and is being verified. */
    data object Verifying : AgentAuthState

    /** Signed in; [account] is the account detail the agent reported, when it reports one. */
    data class SignedIn(val account: String? = null) : AgentAuthState

    /** The flow failed; [message] is user-facing and [transcript] is the raw output for the log. */
    data class Failed(val message: String, val transcript: String = "") : AgentAuthState

    /** True only in [SignedIn] - the one state in which a turn may be sent. */
    val signedIn: Boolean
        get() = this is SignedIn

    /** True while the flow owns the UI and no second attempt may be started. */
    val busy: Boolean
        get() = this is Starting || this is AwaitingBrowser || this is Verifying

    /** True when the user has something to do (open a browser, or read a failure). */
    val needsUserAction: Boolean
        get() = this is AwaitingBrowser || this is Failed
}
