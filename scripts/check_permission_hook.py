#!/usr/bin/env python3
"""Behavioural check for the Claude Code permission hook (Phase 5).

The hook is the guest side of the permission path: it reads a PermissionRequest JSON on stdin, writes
a request file, waits for the app's answer and prints the decision. Until now it was only tested by
hand, with a stripped PATH and a temporary bridge directory. This script does exactly that, in CI, so
the always-allow path cannot silently break again:

  A. a fresh request is written for the app, and an `allow` answer is passed through as allow;
  B. a `deny` answer passes the user's message through as the deny reason;
  C. an unanswered request is denied after the configured timeout (rather than hanging);
  D. an `always-rules.json` entry with a matching command prefix is allowed **without** writing a
     request (the regression this phase fixed: jq used to error on that comparison and silently fall
     through to asking again);
  E. a non-matching command under the same rule still asks;
  F. a question (AskUserQuestion) is not auto-allowed by a rule;
  G. the same allow/deny behaviour holds without jq, through the sed fallback.

Run: python3 scripts/check_permission_hook.py     (exit 0 = all behaviours hold)
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOOK = ROOT / "app/src/main/assets/scripts/mushrea-code-claude-permission-hook.sh"

# The hook needs these to exist when we strip jq out of PATH for the fallback cases.
BASIC_TOOLS = ["sh", "sleep", "date", "cat", "rm", "mkdir", "printf", "sed", "head", "tail", "cut", "tr", "grep", "uname"]

findings: list[str] = []
checks = 0


def run_hook(
    bridge: Path,
    payload: dict,
    *,
    with_jq: bool = True,
    timeout_sec: int = 5,
    wait_response_sec: float = 6.0,
    responder=None,
) -> tuple[int, str, dict | None]:
    """Runs the hook against a temporary bridge; returns (exit code, stdout, request file seen)."""
    env = dict(os.environ)
    env["MUSHREACODE_CLAUDE_BRIDGE"] = str(bridge)
    env["MUSHREACODE_PERMISSION_TIMEOUT_SEC"] = str(timeout_sec)
    env["MUSHREACODE_QUESTION_TIMEOUT_SEC"] = str(timeout_sec)
    if not with_jq:
        # A PATH with only the basics, so `command -v jq` fails and the sed fallback is used.
        bin_dir = Path(tempfile.mkdtemp(prefix="hook-bin-"))
        for tool in BASIC_TOOLS:
            found = shutil.which(tool)
            if found:
                (bin_dir / tool).symlink_to(found)
        env["PATH"] = str(bin_dir)

    payload_file = Path(tempfile.mkstemp(prefix="hook-in-", suffix=".json")[1])
    payload_file.write_text(json.dumps(payload))
    with payload_file.open("r") as stdin_file:
        proc = subprocess.Popen(
            ["sh", str(HOOK)],
            stdin=stdin_file,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )

    seen: dict | None = None
    if responder is not None:
        deadline = time.time() + wait_response_sec
        while time.time() < deadline:
            files = sorted((bridge / "pending").glob("*.json"))
            if files:
                # The file appears a moment before its content lands, and the hook deletes it as
                # soon as it takes the answer, so read it here and retry until it parses.
                for _ in range(40):
                    try:
                        seen = json.loads(files[0].read_text())
                        break
                    except (json.JSONDecodeError, OSError):
                        time.sleep(0.05)
                responder(files[0], bridge)
                break
            time.sleep(0.05)
    out, err = proc.communicate(timeout=max(10.0, wait_response_sec + timeout_sec + 5))
    payload_file.unlink(missing_ok=True)
    if err.strip() and "jq" not in err:
        findings.append(f"hook wrote to stderr: {err.strip()[:200]}")
    return proc.returncode, out, seen


def answer(decision: str, message: str | None = None):
    def responder(request_file: Path, bridge: Path) -> None:
        response = {"v": 1, "decision": decision, "remember": decision == "allow"}
        if message:
            response["message"] = message
        (bridge / "responses" / request_file.name).write_text(json.dumps(response))

    return responder


def expect(condition: bool, what: str) -> None:
    global checks
    checks += 1
    if not condition:
        findings.append(what)


def decision_of(stdout: str) -> str:
    """The hook answers with jq, which pretty-prints; take the first JSON object in the output."""
    text = stdout.strip()
    start = text.find("{")
    if start >= 0:
        try:
            decoder = json.JSONDecoder()
            obj, _ = decoder.raw_decode(text[start:])
            return obj["hookSpecificOutput"]["permissionDecision"]
        except Exception:
            pass
    return f"<unparsable: {text[:120]}>"


def main() -> int:
    if not HOOK.is_file():
        print(f"hook not found: {HOOK}")
        return 1

    with tempfile.TemporaryDirectory(prefix="hook-test-") as tmp:
        base = Path(tmp)

        # A: allow
        bridge = base / "a"
        code, out, seen = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "ls -la"}},
            responder=answer("allow"),
        )
        expect(seen is not None, "A: the hook did not write a request for the app")
        if seen:
            expect(seen["toolName"] == "Bash", "A: request does not carry the tool name")
            expect(seen["toolInput"]["command"] == "ls -la", "A: request does not carry the tool input")
            expect(seen["claudeSessionId"] == "s1", "A: request does not carry the session id")
        expect(decision_of(out) == "allow", f"A: expected allow, got {decision_of(out)}")
        expect(code == 0, f"A: exit code {code}")

        # B: deny with the user's message
        bridge = base / "b"
        code, out, _ = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "rm -rf /"}},
            responder=answer("deny", "nope, not that"),
        )
        expect(decision_of(out) == "deny", f"B: expected deny, got {decision_of(out)}")
        expect("nope, not that" in out, "B: the user's message was not passed through")

        # C: timeout denies instead of hanging
        bridge = base / "c"
        started = time.time()
        code, out, _ = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "ls"}},
            timeout_sec=1,
        )
        elapsed = time.time() - started
        expect(decision_of(out) == "deny", f"C: expected deny on timeout, got {decision_of(out)}")
        # The hook counts whole seconds (date +%s), so a small timeout can expire within the same
        # second it started; what matters is that it denies and returns instead of hanging.
        expect(elapsed <= 8, f"C: timeout took {elapsed:.1f}s")
        expect(not list((bridge / "pending").glob("*.json")), "C: the hook left its pending file behind")

        # D: an always-rule with a command prefix allows without asking
        bridge = base / "d"
        bridge.mkdir(parents=True)
        (bridge / "always-rules.json").write_text(json.dumps({"rules": [{"toolName": "Bash", "commandPrefix": "git status"}]}))
        code, out, seen = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "git status --short"}},
        )
        expect(decision_of(out) == "allow", f"D: a matching always-rule was not allowed, got {decision_of(out)}")
        expect(seen is None, "D: the hook asked the user even though a rule matched")

        # E: the same rule does not cover another command
        bridge = base / "e"
        bridge.mkdir(parents=True)
        (bridge / "always-rules.json").write_text(json.dumps({"rules": [{"toolName": "Bash", "commandPrefix": "git status"}]}))
        code, out, seen = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "git commit -m x"}},
            responder=answer("deny", "not this one"),
        )
        expect(seen is not None, "E: a non-matching command was auto-allowed")
        expect(decision_of(out) == "deny", f"E: expected deny, got {decision_of(out)}")

        # F: a question is never auto-allowed
        bridge = base / "f"
        bridge.mkdir(parents=True)
        (bridge / "always-rules.json").write_text(json.dumps({"rules": [{"toolName": "AskUserQuestion"}]}))
        code, out, seen = run_hook(
            bridge,
            {
                "tool_name": "AskUserQuestion",
                "session_id": "s1",
                "tool_input": {"questions": [{"question": "Which?", "options": [{"label": "A"}]}]},
            },
            responder=answer("allow"),
        )
        expect(seen is not None, "F: a question was auto-allowed by a rule")
        if seen:
            expect(seen["kind"] == "question", "F: the request is not marked as a question")

        # G: without jq, allow and deny still work through the sed fallback
        bridge = base / "g"
        code, out, seen = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "ls"}},
            with_jq=False,
            responder=answer("allow"),
        )
        expect(seen is not None, "G: the fallback did not write a request")
        expect(decision_of(out) == "allow", f"G: fallback expected allow, got {decision_of(out)}")

        bridge = base / "h"
        code, out, _ = run_hook(
            bridge,
            {"tool_name": "Bash", "session_id": "s1", "tool_input": {"command": "ls"}},
            with_jq=False,
            responder=answer("deny", "no"),
        )
        expect(decision_of(out) == "deny", f"G: fallback expected deny, got {decision_of(out)}")

    print("Permission hook check - Mushrea Code")
    print(f"  hook            : {HOOK.relative_to(ROOT)}")
    print(f"  behaviours run  : {checks}")
    if findings:
        print()
        for f in findings:
            print(f"  - {f}")
        print(f"\n  result: FAIL - {len(findings)} finding(s)")
        return 1
    print("\n  result: OK - allow, deny, timeout, always-rules and the no-jq fallback all behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
