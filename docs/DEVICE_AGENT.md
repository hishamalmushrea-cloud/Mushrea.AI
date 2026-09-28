# Device Agent — Mushrea Code as a General-Purpose Android AI Agent

This document describes the Device Agent added on top of Mushrea Code's existing coding-agent
capabilities, per the product spec ("Mushrea Code — Android General-Purpose AI Agent"). The coding
agent (OpenCode / Claude Code / Antigravity / Codex, terminal, git, GitHub, sessions, scheduling,
voice, wake word) is untouched — the Device Agent is an extension, not a replacement.

## Architecture

```text
Mushrea Code
│
├── Agent Core (existing: OpenCode / Claude Code / Antigravity / Codex runtimes)
│     └── the LLM decides goals and steps; device tools are just tools to it
│
├── Coding Tools (existing: code, git, terminal, build, test — unchanged)
│
└── Device Agent  (new: com.mushrea.code.device)
      ├── MushreaCodeAccessibilityService  — screen reading + actions (official Accessibility API)
      ├── DeviceAgentBridge                — command channel → firewall → execute → verify → log
      ├── DeviceActionFirewall             — per-action confirmation policy, sensitive-tap escalation
      ├── AppResolver                      — app-name → package resolution (Arabic-aware)
      ├── ScreenSnapshot / Formatter       — accessibility tree → compact numbered element list
      ├── DeviceAgentStore                 — workspace marker, firewall overrides, stop flag, activity log
      ├── DeviceAgentActivity              — status, firewall toggles, STOP, activity log
      └── mushreacode-device-mcp.py        — MCP server exposing device_* tools to every agent CLI
```

## How a device task flows

```text
User (voice/text, anywhere on the phone)
  → agent CLI (in the on-device runtime) decides to use a device_* tool
    → mushreacode-device-mcp.py writes .mushrea-code/device-command.json in the active workspace
      → MushreaCodeAccessibilityService's bridge picks it up (polls every 0.5 s)
        → DeviceActionFirewall classifies: AUTO runs; CONFIRM shows a notification (Allow/Deny)
          → engine executes via Accessibility/Intents/files
            → verification (e.g. re-check the foreground package after open_app)
              → .mushrea-code/device-result.json written back
                → agent reads the verified result and continues the loop
```

## Tools exposed (device MCP)

`device_open_app`, `device_current_app`, `device_read_screen`, `device_find_element`, `device_tap`,
`device_long_press`, `device_swipe`, `device_scroll`, `device_type_text`, `device_clear_text`,
`device_press` (back/home/recents), `device_open_url`, `device_list_apps`, `device_search_files`,
`device_open_file`, `device_share_file`, `device_delete_file`, `device_move_file`,
`device_copy_file`, `device_rename_file`, `device_stop` (emergency), `device_status`.

Precedence inside every interaction: Android API → Accessibility semantics (find the element by
text/description) → element action → coordinate gesture fallback.

## Permission Firewall

| Action | Default |
|---|---|
| open app / read screen / search files / open file / scroll / type / taps | Automatic |
| taps on sensitive controls (pay / delete / send / checkout / ادفع / احذف / إرسال …) | Confirmation |
| share / delete / move / copy / rename file | Confirmation |
| every level | User-configurable on the Device Agent screen (AUTO → CONFIRM → STRONG → default) |

Confirmations appear as a high-priority notification with **Allow / Deny**; unanswered requests time
out (120 s) and the action is *not* performed. File paths are constrained to user storage roots
(`/sdcard`, `/storage/*`); app-private and system paths are rejected.

## Emergency stop

- STOP button on the Device Agent screen (and `StopAgentReceiver` broadcast `com.mushrea.code.STOP_AGENT`)
- `device_stop` tool the agent itself can call (the voice "توقف/stop" path goes through the agent)
- The flag is consumed between steps: no new device action starts after a stop request, and pending
  confirmations are treated as Deny.

## Setup (user)

1. Update/install the app.
2. Open **Mushrea Device Agent** (launcher entry) → *Open Accessibility settings* → enable
   **Mushrea Code**. This is the official Android accessibility grant; nothing works without it.
3. Optionally grant **All files access** for the file agent to search everywhere.
4. Start a chat with any agent and ask for device tasks ("افتح YouTube", "ابحث عن ...", ...).
   The runtime rebuild (any next runtime install/update) registers the `mushrea-code-device` MCP
   server automatically for OpenCode, Claude Code and Antigravity.

## Status (honest accounting per spec section 55)

### Implemented (code complete, compiles against the same patterns as the rest of the app)

- Device Agent core: command codec, firewall (with sensitive-tap escalation), app resolver
  (Arabic normalization: alef/ya/ta-marbuta folding, Arabic-Indic digits), screen snapshot
  formatter + element matcher, file-backed store, activity log, emergency stop
- Accessibility engine: tree snapshot, semantic element re-location, click (with clickable-ancestor
  walk), global back/home/recents, gesture tap/long-press/swipe, text set/clear via ACTION_SET_TEXT
- Bridge: polling loop in the accessibility service, workspace tracking, per-command verification
  (foreground check after open_app, existence checks after file ops), confirmation notifications,
  stop handling, structured activity log
- File agent: search (bounded walk), open/share via FileProvider + system intents,
  delete/move/copy/rename with root-safety checks
- Device MCP server registered for all three agent CLIs, following the browser-MCP file-channel
  pattern exactly
- Unit tests: firewall policy, app resolution, screen formatting/matching, command codec
- UI: Device Agent screen (accessibility status, firewall customization, STOP, activity log) —
  temporarily a separate launcher entry; folding it into Settings is future work

### Tested

- Pure-logic unit tests (`app/src/test/.../device/`): firewall, resolver, snapshot matcher, codec
- Python MCP script: AST/syntax check (it mirrors the proven browser-MCP stdio loop)

### Not tested here (needs Manual QA on a real device — see checklist)

No Android SDK/device exists in this development environment, so the following spec checklist is
**pending on-device QA**: open app + verification, back/home/recents, read screen, find element,
tap/long-press/swipe/scroll, type/clear, file search/open/share, explain-screen (agent-driven),
multi-step task, recovery, stop agent, voice/wake-word routing, background execution from the
assistant, and regression of the coding agent itself.

### Known limitations / Android restrictions

- `read_screen` sees only what Accessibility exposes; apps that block accessibility (banking
  screens, FLAG_SECURE content) return sparse trees. The agent is told to say so honestly.
- Foreground-app detection requires the accessibility service to have received a window event;
  before the first event, `get_current_app` reports the root window's package instead.
- Coordinate gestures need the `canPerformGestures` capability (declared); on some devices
  accessibility gestures are rate-limited.
- App listing relies on the `<queries>` launcher-intent declaration (Android 11+ visibility);
  apps without a launcher entry are not resolvable — by design.
- File tools follow symlinks only within allowed roots and never touch app-private or system paths.
- The confirmation UI is notification-based; if notifications are blocked, the firewall degrades to
  "deny on timeout" (safe default), reported in the activity log.

## Next phases (spec 52)

- Phase 5+: richer Context Engine (current file/page/video memory, confidence-driven questions)
- Explain/summarize screen as first-class app-level flows (currently agent-driven via device_read_screen)
- Contextual search + in-app navigation recipes
- Folding the Device Agent screen into Settings, plus chat-side status card
- Scheduled device automation (linking Schedule MCP to device tools with confirmation policy)
