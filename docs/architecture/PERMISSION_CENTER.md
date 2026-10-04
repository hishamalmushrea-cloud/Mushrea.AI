# Permission Center (P2)

The Permission Center is the one place in Mushrea Code that answers **"may this sensitive operation
run?"**. It was introduced by phase P2 of the platform program, after the Phase 1 audit showed the
same question being answered in five different places: the device bridge's own Read-Only check, the
firewall's level lookup, per-tool policies (`TermuxCommandPolicy`, `PayloadGuard`, the sensitive-tap
keywords), the user's stored overrides, and the emergency-stop flag.

Executors used to call all of that directly. A caller that reached an executor without going through
the device bridge — or a new subsystem that never had a policy at all — simply ran.

## 1. What the center owns, and what it does not

| Owned by the center | Owned by the subsystem |
| --- | --- |
| Routing a request to the policy that claims its domain | *How* an operation is executed |
| The emergency stop (deny everything but the stop action) | The confirmation UI (the device notification, the chat prompt) |
| Read-Only as a central input (`mutatesState`) | The tool table, per-tool rules, the stored overrides |
| Fail-closed routing (no policy / uncovered operation → DENY) | Availability checks (`DeviceAvailability`) and readiness |
| The decision record and its reason | Verification (`OutcomeVerification`) and the audit trail |
| The optional decision listener (audit linkage) | Timeouts, retries and watchdogs |

The split is deliberate: the center never executes, never shows UI and never talks to a runtime. It
returns a decision, and the caller acts on it with the mechanisms it already had. That is what kept
this phase from being a rewrite of the Device Agent: the bridge's pipeline
(availability → confirmation → execute → verify → audit) is untouched; only the decision moved.

## 2. The model

```
request → routing → policy evaluation → safety rules → decision → (confirmation) → allow/deny
```

`core/permission/PermissionRequest.kt` — the one request shape:

| Field | Consumer |
| --- | --- |
| `domain` | routing; one policy per domain |
| `operation` | catalog action id (`usb_adb_shell`) or dotted name (`runtime.lifecycle.start`) |
| `source` | USER / AGENT / SCHEDULE / SYSTEM — a policy reasons about *who* is asking |
| `target` | what it acts on (path, host, app id, runtime), used by the confirmation prompt |
| `risk` | LOW / MEDIUM / HIGH, taken from the tool catalog for device tools |
| `mutatesState` | the center's Read-Only rule; the catalog's `readOnly` flag is its source |
| `readOnly` | the current Read-Only switch |
| `emergencyStop` | set when the user pressed the emergency stop |
| `safetyOperation` | true only for `stop_agent`, which stays reachable while the stop is set |
| `preAuthorized` | the standing *auto-accept permissions* authorization, judged by a policy |
| `tapLabel` | the visible label of a tapped control, for the sensitive-tap escalation |

`core/permission/PermissionResult.kt` — the one answer: a level (`AUTO`, `CONFIRM`,
`STRONG_CONFIRM`, `DENY`), a human-readable reason, the policy that decided (`decidedBy`), whether a
stored override changed the level (`overridden`), plus `asDecision()` for the device bridge's
existing `PermissionDecision` type.

A result is never a boolean: a refusal has to say which rule refused it, and a confirmation has to
carry how much friction the operation needs.

## 3. Policies

| Policy id | Domains | Source of truth |
| --- | --- | --- |
| `device.tools` | `DEVICE`, `SCREEN`, `FILES`, `NETWORK`, `USB`, `SSH`, `REMOTE` | `ToolPermissionPolicy` + the 90-entry `DeviceToolCatalog` (levels, risk, read-only set, requirements) |
| `runtime` | `AGENT_RUNTIME`, `RUNTIME_LIFECYCLE` | the standing auto-accept authorization; the on-device runtime's install/start/stop/delete |

A policy answers with `null` for an operation it does not cover, and the center turns that into a
denial. `scripts/check_permission_center.py` fails the build if a domain has no policy, if the
bridge rebuilds a policy itself, if a runtime command is sent around the controller, or if an
`if (autoAcceptPermissions)` grows back next to the center.

Adding a domain or an operation is therefore a deliberate act: an unclaimed domain is refused, never
allowed.

## 4. The four levels

| Level | Meaning |
| --- | --- |
| `AUTO` | runs immediately (a read, a low-risk probe, a user's own tap on a button) |
| `CONFIRM` | the user answers a prompt first (the device notification, the chat prompt) |
| `STRONG_CONFIRM` | the same prompt, recorded as the higher-friction level; used by the Device Agent screen's override UI |
| `DENY` | does not run, whoever asks |

`ConfirmationLevel.parseOrNull` also accepts the pre-P2 spelling `STRONG`, because
`DeviceAgentStore` persisted the user's overrides as `"STRONG"`. Without that, a stored override the
user had raised would silently fall back to the tool's catalog level.

`STRONG_CONFIRM` reuses the existing confirmation mechanism: the device bridge asks for a
confirmation the same way for both levels and the level is what gets recorded. No new UI was added
by P2, and none was needed.

## 5. Safety rules, in order

1. **Emergency stop** — while it is set, every request is refused except the safety operation. The
   bridge consumes the stop flag exactly as before (including its TTL), but the *rule* that turns it
   into a refusal now lives here, so no subsystem decides for itself that its own work is too
   important to stop.
2. **Routing** — an unclaimed domain is refused.
3. **Policy evaluation** — an uncovered operation is refused. This closes the fail-open hole the
   Phase 1 audit found in `DeviceActionFirewall.levelFor`, which answered `AUTO` for an id it did
   not know; unknown ids are now denied at the decision layer.
4. **Read-Only** — an allowed state-changing operation becomes a denial. The safety operation is
   exempt on purpose: the emergency stop changes state (it clears the run), and blocking it while
   Read-Only is on would remove the one control that stops a runaway task. The device policy also
   produces its own, more precise refusal for its 90 tools; the center's rule is the safety net for
   every other domain.

## 6. How each subsystem is connected

| Subsystem | Path through the center |
| --- | --- |
| Device Agent (screen, calls, Bluetooth, Termux, payloads, status) | `DeviceAgentBridge.process` → `device.tools` |
| Files | the five file tools in the catalog → `device.tools` |
| Network | `network_*` tools → `device.tools` |
| USB / serial / hub / MTP | `usb_*`, `serial_*`, `hub_*` tools → `device.tools` |
| SSH | `ssh_*` tools → `device.tools` |
| Remote (FTP/SMB) | `remote_*` tools → `device.tools` |
| Scheduler | runtime start before a run → `runtime.lifecycle.start`; the agent's permission prompts → `runtime` (`agent.permission.auto_accept`) |
| Agent runtime prompts (chat, voice) | `runtime` (`agent.permission.auto_accept`) |
| Runtime lifecycle | `LocalRuntimeServiceController` → `runtime.lifecycle.*` |

"Connected" means the operation's decision is taken by the center; execution, verification and audit
stay where they were (`Permission ≠ Execution ≠ Verification`).

## 7. Audit

The device bridge records the center's decision in its activity log as before, and `DeviceAuditLog`
format 4 is unchanged, because the decision already carries the level and the reason the log wrote.
The center also accepts an `onDecision` listener; it runs after the answer is fixed and inside
`runCatching`, so a broken audit store can neither change a decision nor block one. No listener is
attached in the app today: the device path keeps writing its own audit record, and the runtime and
agent-prompt domains are *not* audited (they were not before P2 either) — the hook is where a shared
audit attaches once the owner defines its schema, which this phase deliberately did not do.

## 8. Verified by

* `app/src/test/java/com/mushrea/code/core/permission/PermissionCenterTest.kt` — routing, the four
  levels, fail-closed coverage, emergency stop, Read-Only (including the safety-operation exemption),
  the audit hook, the vocabulary bridge, and the cross-subsystem claim (all 90 catalog tools plus
  every domain answered by one center).
* `app/src/test/java/com/mushrea/code/device/permission/DeviceToolPolicyTest.kt` — the catalog's
  levels, risk influence, domain routing, overrides, Read-Only, the sensitive-tap escalation.
* `app/src/test/java/com/mushrea/code/runtime/permission/RuntimePermissionPolicyTest.kt` — the
  standing authorization and the runtime lifecycle per source.
* `scripts/check_permission_center.py` — the wiring rules from §3, run in both CI workflows.
* `ToolPermissionPolicyTest`, `DeviceActionFirewallTest` and `DeviceAuditLogTest` — the pre-P2
  behaviour of the device layer, unchanged and still green.
