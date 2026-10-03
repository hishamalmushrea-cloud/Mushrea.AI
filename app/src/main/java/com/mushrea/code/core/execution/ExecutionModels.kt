package com.mushrea.code.core.execution

import com.mushrea.code.core.permission.PermissionRisk
import com.mushrea.code.core.permission.PermissionSource
import java.util.UUID

/**
 * The provider-neutral execution vocabulary (Peer ADB work, phase 1).
 *
 * The platform's device tools answer "what can the agent ask for?" with a fixed table. That table is
 * the right shape for the tools the product ships, but it is the wrong shape for *driving another
 * phone over ADB*: the useful set there is "whatever the device and its shell can do", which cannot
 * be enumerated in advance. This package is the second half of the answer - a request/result pair
 * generic enough that one provider can run a shell line, a program, a script or a file transfer, and
 * a planner can decide which provider fits without the caller knowing anything about ADB.
 *
 * Nothing here executes anything: `core` holds the vocabulary only. Implementations live in the
 * layers that may touch a device (`device/bridge/`).
 *
 * The four questions a request answers, kept apart on purpose:
 *  * [ExecutionOperation] - *what kind* of work (run a shell line, run a program, move a file…);
 *  * [ExecutionTarget] - *where* it happens, named explicitly (there is no implicit device);
 *  * [ExecutionInvocation] - *what* runs, as data (never a string the caller interpolated);
 *  * [ExecutionEffect] - *what it costs*, declared by the caller so policy can weigh it.
 */

/** What kind of work a provider is being asked to do - the primitive, never the command. */
enum class ExecutionOperation {
    /** Read facts about the environment. Must not change the target. */
    PROBE,

    /** Run one shell line through the target's shell. */
    SHELL,

    /** Run one program with an argument vector, without shell interpolation. */
    EXEC,

    /** Copy bytes from this device to the target. */
    PUSH,

    /** Copy bytes from the target to this device. */
    PULL,

    /** Install a package (APK) on the target. */
    INSTALL,

    /** Run a script through an interpreter the target actually has. */
    SCRIPT,
}

/** Which channel a request travels over. A provider serves exactly one. */
enum class ExecutionTransport {
    /** Another Android phone reached through wireless debugging (TLS ADB). */
    PEER_ADB,

    /** Another Android phone reached through a USB cable. */
    USB_ADB,

    /** The Linux runtime running inside this app. */
    LOCAL_RUNTIME,

    /** A remote shell the user has credentials for. */
    SSH,
}

/**
 * Where a request goes.
 *
 * [id] is the stable identity the user and the audit see (an ADB serial for a phone, a profile id for
 * a runtime). There is deliberately no default target: every request names one, because "the first
 * attached device" is how a script installs an APK on the wrong phone.
 */
data class ExecutionTarget(
    val id: String,
    val transport: ExecutionTransport,
    val label: String = id,
)

/** A file that travels with the request (a local path for push, a remote path for pull). */
data class ExecutionFile(
    val localPath: String,
    val remotePath: String,
)

/**
 * The work itself, as data.
 *
 * [command] is one program path or one shell line; [arguments] is an argv that a provider must pass
 * without re-parsing. A caller that needs shell operators says so by using [ExecutionOperation.SHELL]
 * and putting the line in [command] - the provider is then free to refuse anything it cannot quote.
 */
data class ExecutionInvocation(
    val command: String,
    val arguments: List<String> = emptyList(),
    val interpreter: String? = null,
    val files: List<ExecutionFile> = emptyList(),
    val stdin: String? = null,
)

/**
 * What the caller expects the operation to cost.
 *
 * This is the caller's *claim*, and policy is allowed to distrust it: the peer provider runs its own
 * classifier over the command and can only raise the friction this declares, never lower it. Keeping
 * the claim in the request (instead of letting each provider guess) is what lets one policy answer
 * for providers that do not exist yet.
 */
data class ExecutionEffect(
    val mutatesTarget: Boolean,
    val destructive: Boolean = false,
    val risk: PermissionRisk = PermissionRisk.LOW,
    val needsUserInteraction: Boolean = false,
)

/** How the request is governed: who asked, why, how long it may take, whether it must be proven. */
data class ExecutionPolicy(
    val requestedBy: PermissionSource = PermissionSource.AGENT,
    val reason: String = "",
    val timeoutMillis: Long = 30_000,
    val verify: Boolean = false,
    /**
     * True when the caller already obtained the user's approval for *this* operation.
     *
     * It is an input to policy, never a bypass: a policy may accept it (and not ask the same question
     * twice), and a strong confirmation stays strong whatever it says. The device channel sets it
     * when the user has just answered the tool's own confirmation prompt.
     */
    val preAuthorized: Boolean = false,
)

/** One request to one target through whatever provider fits. */
data class ExecutionRequest(
    val operation: ExecutionOperation,
    val target: ExecutionTarget,
    val invocation: ExecutionInvocation,
    val effect: ExecutionEffect,
    val policy: ExecutionPolicy = ExecutionPolicy(),
    val correlationId: String = UUID.randomUUID().toString(),
    val providerId: String? = null,
)
