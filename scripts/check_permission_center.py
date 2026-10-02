#!/usr/bin/env python3
"""Guard the unified Permission Center (P2).

P2 replaced the per-subsystem permission logic with one center in `core/permission`. Kotlin tests
prove the *decisions*; this script proves the *wiring*, because the failure mode that matters here is
not a wrong answer but a second path: a new executor called straight from an activity, a policy
rebuilt inside the bridge, or an `if (autoAcceptPermissions)` growing back next to the center. None
of those would break a unit test.

Rules:
  A. the center exists and exposes the one entry point (`PermissionCenter.decide`);
  B. the composition root builds exactly one center, from the device and runtime policies;
  C. the device bridge reaches the device policy *through* the center and never rebuilds it;
  D. the on-device runtime is driven only through its controller, and the controller asks the center;
  E. no subsystem answers the agent's permission prompt on its own any more: the standing
     auto-accept setting may only be passed to the center as `preAuthorized`;
  F. the device executors stay reachable only through the bridge (with the deliberate exceptions
     listed below);
  G. every `PermissionDomain` is claimed by at least one policy, so a new domain cannot be routed to
     nothing (which the center would refuse - safe, but it would make the domain dead).

Usage:
    python3 scripts/check_permission_center.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
CENTER_DIR = MAIN / "com/mushrea/code/core/permission"
BRIDGE = MAIN / "com/mushrea/code/device/DeviceAgentBridge.kt"
RUNTIME_SERVICE = MAIN / "com/mushrea/code/runtime/local/LocalRuntimeService.kt"
APPLICATION = MAIN / "com/mushrea/code/MushreaCodeApplication.kt"

CENTER_FILES = {
    "PermissionCenter.kt": "class PermissionCenter(",
    "PermissionPolicy.kt": "interface PermissionPolicy",
    "PermissionRequest.kt": "data class PermissionRequest(",
    "PermissionResult.kt": "data class PermissionResult(",
    "PermissionRisk.kt": "enum class PermissionRisk",
    "ConfirmationLevel.kt": "enum class ConfirmationLevel",
}

# Executors owned by the device bridge. Any other file that reaches one in *code* (comments are
# stripped first, so a doc reference like "(`SshExecutor.credentials`)" does not count) is a bypass.
EXECUTORS = [
    "UsbSerialExecutor",
    "HubExecutor",
    "RemoteExecutor",
    "NetworkExecutor",
    "BluetoothExecutor",
    "SshExecutor",
    "PayloadExecutor",
    "TermuxExecutor",
    "CallAgentExecutor",
    "UsbExecutor",
]

# Deliberate, documented exceptions: the Device Agent screen's own read-only USB diagnostics button,
# which asks the executor for a report without running any command through the command channel.
EXECUTOR_EXCEPTIONS = {
    "UsbExecutor": {"com/mushrea/code/device/DeviceAgentActivity.kt"},
}

# The three call sites that used to answer the agent's prompt with their own auto-accept check.
AUTO_ACCEPT_CALLERS = [
    "com/mushrea/code/feature/chat/ChatViewModel.kt",
    "com/mushrea/code/feature/assistant/MushreaCodeVoiceSession.kt",
    "com/mushrea/code/feature/schedule/ScheduleExecutionService.kt",
]

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")

findings: list[str] = []
checks = 0


def expect(condition: bool, what: str) -> None:
    global checks
    checks += 1
    if not condition:
        findings.append(what)


def code_only(path: Path) -> str:
    """The file's text without comments, so documentation cannot trip a code rule."""
    text = path.read_text(encoding="utf-8")
    text = BLOCK_COMMENT.sub("", text)
    return LINE_COMMENT.sub("", text)


def production_sources() -> list[Path]:
    return sorted(p for p in MAIN.rglob("*.kt"))


def main() -> int:
    # A. the center itself
    for name, needle in CENTER_FILES.items():
        path = CENTER_DIR / name
        expect(path.is_file(), f"A: {name} is missing from core/permission")
        if path.is_file():
            expect(needle in code_only(path), f"A: {name} does not declare `{needle}`")
    center = CENTER_DIR / "PermissionCenter.kt"
    if center.is_file():
        text = code_only(center)
        expect("fun decide(" in text, "A: the center has no decide() entry point")
        expect("PermissionDomain" in text, "A: the center does not route by domain")

    # B. one center, built from the policies
    if APPLICATION.is_file():
        text = code_only(APPLICATION)
        expect(
            text.count("PermissionCenter(") == 1,
            "B: MushreaCodeApplication must build exactly one PermissionCenter",
        )
        expect("DeviceToolPolicy(" in text, "B: the device policy is not wired into the center")
        expect("RuntimePermissionPolicy()" in text, "B: the runtime policy is not wired into the center")

    # C. the bridge goes through the center
    if BRIDGE.is_file():
        text = code_only(BRIDGE)
        expect("permissionCenter.decide(" in text, "C: DeviceAgentBridge does not ask the center")
        expect("ToolPermissionPolicy(" not in text, "C: DeviceAgentBridge rebuilds the device policy itself")
        expect("DeviceActionFirewall.levelFor(" not in text, "C: the bridge classifies with the firewall directly, not through the center")

    # D. the runtime is driven through its controller, and the controller asks the center
    if RUNTIME_SERVICE.is_file():
        text = code_only(RUNTIME_SERVICE)
        expect("permissionCenter.decide(" in text, "D: LocalRuntimeServiceController does not ask the center")
        for method in ("start", "stop", "delete", "reinstall"):
            expect(
                re.search(rf"fun {method}\(.*?sendIfPermitted\(|fun {method}\(", text, re.S) is not None,
                f"D: {method}() disappeared from the controller",
            )
    for path in production_sources():
        if path == RUNTIME_SERVICE:
            continue
        expect(
            "LocalRuntimeService.send(" not in code_only(path),
            f"D: {path.relative_to(MAIN)} sends a runtime command around the controller",
        )

    # E. nobody answers the agent's prompt on their own any more
    auto_accept_if = re.compile(r"if\s*\(\s*(?:[A-Za-z_][\w]*\.)*autoAccept(?:Permissions)?\b")
    for path in production_sources():
        text = code_only(path)
        match = auto_accept_if.search(text)
        expect(match is None, f"E: {path.relative_to(MAIN)} decides auto-accept itself: `{match.group(0) if match else ''}`")
    for name in AUTO_ACCEPT_CALLERS:
        path = MAIN / name
        expect(path.is_file(), f"E: {name} disappeared; update this rule if that is deliberate")
        if path.is_file():
            expect(
                "preAuthorized" in code_only(path),
                f"E: {name} no longer passes the standing authorization to the center",
            )

    # F. the executors stay behind the bridge
    mentions: dict[str, set[str]] = {name: set() for name in EXECUTORS}
    for path in production_sources():
        text = code_only(path)
        for name in EXECUTORS:
            # The class's own file is where it is defined, not a second caller.
            if path.name == f"{name}.kt":
                continue
            if re.search(rf"\b{name}\b", text):
                mentions[name].add(str(path.relative_to(MAIN)))
    bridge_rel = str(BRIDGE.relative_to(MAIN))
    for name, files in mentions.items():
        allowed = {bridge_rel} | EXECUTOR_EXCEPTIONS.get(name, set())
        unexpected = sorted(files - allowed)
        expect(
            not unexpected,
            f"F: {name} is reached outside the bridge by {', '.join(unexpected) or '(nowhere)'}",
        )
        if name != "UsbExecutor":
            expect(bool(files), f"F: nothing constructs {name} - is it dead?")

    # G. every domain is claimed by a policy
    request = CENTER_DIR / "PermissionRequest.kt"
    if request.is_file():
        text = code_only(request)
        enum_block = text.split("enum class PermissionDomain", 1)[-1]
        enum_block = enum_block.split("}", 1)[0]
        domains = re.findall(r"^\s*([A-Z][A-Z_]+),", enum_block, re.M)
        policy_text = ""
        for path in MAIN.rglob("*Policy.kt"):
            body = code_only(path)
            if ": PermissionPolicy" in body:
                policy_text += body
        for domain in domains:
            expect(
                f"PermissionDomain.{domain}" in policy_text,
                f"G: no policy claims PermissionDomain.{domain}",
            )
        expect(len(domains) >= 8, f"G: only {len(domains)} domains found; the P2 surface is 8+")

    print("Permission Center check - Mushrea Code")
    print(f"  center files    : {', '.join(sorted(CENTER_FILES))}")
    print(f"  policies wired  : device.tools, runtime")
    print(f"  executors held  : {len(EXECUTORS)} behind the bridge")
    print(f"  rules run       : {checks}")
    if findings:
        print()
        for finding in findings:
            print(f"  - {finding}")
        print(f"\n  result: FAIL - {len(findings)} finding(s)")
        return 1
    print("\n  result: OK - one center, no bypass, every domain routed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
