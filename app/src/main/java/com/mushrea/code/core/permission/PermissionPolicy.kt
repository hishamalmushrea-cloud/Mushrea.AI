package com.mushrea.code.core.permission

/**
 * One subsystem's own rules, plugged into the Permission Center instead of living beside it.
 *
 * The split is deliberate: the center owns the *composition and the safety rules* (emergency stop,
 * Read-Only, fail-closed routing, audit), while a policy owns the knowledge only its subsystem has —
 * the device catalog knows which of its 90 tools are read-only, the runtime knows what a scheduled
 * run is allowed to pre-authorize. A policy never executes anything and never talks to a UI: it
 * answers, and the subsystem that asked acts on the answer.
 */
interface PermissionPolicy {
    /** Stable name for the audit record and for tests (`device.tools`, `runtime.lifecycle`). */
    val id: String

    /** The domains this policy answers for; the center routes a request to exactly one policy. */
    val domains: Set<PermissionDomain>

    /**
     * Evaluates one request.
     *
     * Returning `null` means **"this policy does not cover that operation"** — not "allow". The
     * center turns it into a denial, which is what keeps a newly added tool from running
     * automatically just because nobody wrote a rule for it.
     */
    fun evaluate(request: PermissionRequest): PermissionResult?
}
