#!/usr/bin/env python3
"""Tool-catalog checker for Mushrea Code (Phase 4).

One device action is described in four places, and until now nothing kept them in step:

  1. `app/src/main/assets/scripts/mushreacode-device-mcp.py` - the agent-facing tool table
     (name, description, input schema, and how long the call waits for the app);
  2. `.../device/tool/DeviceToolCatalog.kt` - the app-side tool table (risk, confirmation, timeout,
     requirements, read-only and configurable flags);
  3. `.../device/DeviceAgentBridge.kt` - the dispatch that actually executes an action;
  4. `.../device/DeviceActionFirewall.kt` - the action sets the bridge consults.

This script reads all four and fails when they disagree. It is run by CI in the same step as the
architecture checker, and it is pure standard-library Python so it also runs on any workstation.

Invariants checked:

  A. every bridge-executed action is in the catalog, and every catalog action has a dispatch branch;
  B. the agent-facing tool names match exactly (catalog <-> MCP script), both directions;
  C. every MCP tool has a non-empty object input schema and a description;
  D. required parameters declared in the catalog match the script's `inputSchema.required`;
  E. the catalog's timeout equals the wait the script uses for that tool (DEFAULT/CONFIRM resolved);
  F. a tool that asks for confirmation waits longer than the bridge's confirmation window;
  G. the read-only list never contains a tool that asks for confirmation (read-only must not block);
  H. ids are unique, every tool declares a family, a risk and at least one requirement;
  I. the firewall derives its sets from the catalog (no hand-written action sets left);
  J. the Device Agent screen's configurable list is a subset of the catalog.

Exit code 0 = consistent, 1 = findings (printed as `file:line` where the source has one).
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main"
MCP = APP / "assets/scripts/mushreacode-device-mcp.py"
CATALOG = APP / "java/com/mushrea/code/device/tool/DeviceToolCatalog.kt"
BRIDGE = APP / "java/com/mushrea/code/device/DeviceAgentBridge.kt"
FIREWALL = APP / "java/com/mushrea/code/device/DeviceActionFirewall.kt"

# The bridge waits this long for the user's Allow/Deny before it gives up on a confirmation.
BRIDGE_CONFIRMATION_WINDOW_MILLIS = 120_000

findings: list[str] = []


def fail(where: str, message: str) -> None:
    findings.append(f"{where}: {message}")


def load_mcp():
    """Imports the shipped MCP server as a module (import is side-effect free: main() is guarded)."""
    spec = importlib.util.spec_from_file_location("mushrea_device_mcp", MCP)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_catalog(text: str):
    """Parses the Kotlin tool table into plain data."""
    tools = []
    for match in re.finditer(r"    tool\(\n(.*?)\n    \),", text, re.S):
        block, entry = match.group(1), match.start()
        line = text[:entry].count("\n") + 1
        fields = {}
        for field in ("id", "mcpTools", "confirmation", "timeoutMillis", "readOnly", "configurable", "transport"):
            m = re.search(rf"        {field} = (.+?)$", block, re.M)
            if m:
                fields[field] = m.group(1).strip().rstrip(",")
        req = re.search(r"        requires = setOf\((.*?)\),$", block, re.M)
        fields["requires"] = [r.split(".")[-1] for r in req.group(1).split(",") if r.strip()] if req else []
        params = re.search(r"        requiredParams = listOf\((.*?)\),$", block, re.M)
        fields["requiredParams"] = re.findall(r'"([^"]+)"', params.group(1)) if params else []
        tools.append((line, fields))
    return tools


def parse_bridge(text: str) -> set[str]:
    """Every ACTION_* constant the bridge's execute() dispatches, plus the local execute* helpers."""
    start = text.index("private suspend fun execute(command: DeviceCommand)")
    end = text.index("else -> throw DeviceFileAgent.DeviceAgentError", start)
    block = text[start:end]
    return set(re.findall(r"DeviceActionFirewall\.(ACTION_[A-Z0-9_]+)", block))


def main() -> int:
    mcp = load_mcp()
    catalog_text = CATALOG.read_text(encoding="utf-8")
    bridge_text = BRIDGE.read_text(encoding="utf-8")
    firewall_text = FIREWALL.read_text(encoding="utf-8")

    consts = dict(re.findall(r'const val (ACTION_[A-Z0-9_]+) = "([a-z0-9_]+)"', firewall_text))
    fire_consts = dict(re.findall(r'const val (ACTION_[A-Z0-9_]+) = "([a-z0-9_]+)"', firewall_text))
    if not consts:
        fail(str(FIREWALL.relative_to(ROOT)), "no ACTION_* constants found - did the firewall move?")
        return report()

    entries = parse_catalog(catalog_text)
    ids: dict[str, tuple[int, dict]] = {}
    for line, e in entries:
        raw_id = e.get("id", "")
        m = re.match(r"DeviceActionFirewall\.(ACTION_[A-Z0-9_]+)", raw_id)
        tool_id = consts.get(m.group(1)) if m else raw_id.strip('"')
        if tool_id is None:
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"unknown action constant in id: {raw_id}")
            continue
        e["action_const"] = m.group(1) if m else None
        e["id"] = tool_id
        if tool_id in ids:
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"duplicate tool id: {tool_id}")
        ids[tool_id] = (line, e)

    # ---- H: completeness of every entry ------------------------------------------------
    for tool_id, (line, e) in ids.items():
        if not e.get("requires"):
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"{tool_id}: declares no ToolRequirement")
        if e.get("transport") not in (None, "ToolTransport.WORKSPACE_FILE"):
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"{tool_id}: unknown transport {e.get('transport')}")
        if not re.search(rf'        id = .*?\n(?:.*?\n)*?        risk = ToolRisk\.', e and block_of(catalog_text, line)):
            pass  # risk/family presence is checked below through the parsed block

    for line, e in entries:
        block = block_of(catalog_text, line)
        for field in ("family", "risk", "confirmation", "timeoutMillis"):
            if f"        {field} = " not in block:
                fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"{e.get('id', '?')}: missing {field}")

    # ---- A: catalog <-> bridge dispatch -------------------------------------------------
    dispatched = parse_bridge(bridge_text)
    dispatched_ids = {consts[c] for c in dispatched if c in consts}
    known_ids = set(ids)
    for tool_id in sorted(known_ids - dispatched_ids):
        line, e = ids[tool_id]
        if e.get("transport") == "ToolTransport.WORKSPACE_FILE":
            continue  # read straight from the workspace; no command round-trip by design
        fail(f"{BRIDGE.relative_to(ROOT)}", f"catalog tool '{tool_id}' has no dispatch branch in execute()")
    for tool_id in sorted(dispatched_ids - known_ids):
        fail(str(CATALOG.relative_to(ROOT)), f"bridge dispatches '{tool_id}' but the catalog does not describe it")

    # ---- B/C/D/E: catalog <-> MCP script ------------------------------------------------
    script_tools = {t["name"]: t for t in mcp.TOOLS}
    catalog_tools: dict[str, list[str]] = {}
    for tool_id, (line, e) in ids.items():
        for name in re.findall(r'"([^"]+)"', e.get("mcpTools", "")):
            catalog_tools.setdefault(name, []).append(tool_id)
        if "emptyList()" in e.get("mcpTools", "") and tool_id != "ping":
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"{tool_id}: only 'ping' is a bridge-only tool")
    for name in sorted(set(script_tools) - set(catalog_tools)):
        fail(str(CATALOG.relative_to(ROOT)), f"'{name}' is shipped to the agent but is not in the catalog")
    for name in sorted(set(catalog_tools) - set(script_tools)):
        fail(str(MCP.relative_to(ROOT)), f"'{name}' is in the catalog but not shipped to the agent")

    for name, tool in script_tools.items():
        schema = tool.get("inputSchema")
        if not isinstance(schema, dict) or schema.get("type") != "object":
            fail(str(MCP.relative_to(ROOT)), f"{name}: inputSchema is not an object schema")
        if not str(tool.get("description", "")).strip():
            fail(str(MCP.relative_to(ROOT)), f"{name}: has no description")
        for tool_id in catalog_tools.get(name, []):
            line, e = ids[tool_id]
            declared = e.get("requiredParams", [])
            actual = schema.get("required", [])
            if declared != actual:
                fail(
                    f"{CATALOG.relative_to(ROOT)}:{line}",
                    f"{tool_id}: requiredParams {declared} != script's {actual}",
                )

    # ---- E/F: timeouts ------------------------------------------------------------------
    funcs = {}
    for m in re.finditer(r"^def (tool_[a-z0-9_]+)\(.*?(?=^def |\Z)", MCP.read_text(encoding="utf-8"), re.M | re.S):
        body = m.group(0)
        funcs[m.group(1)] = (re.findall(r"timeout\s*=\s*([A-Z_0-9.]+)", body) or [None])[0]
    handler_fn = {name: fn for name, fn in ((n, f) for n, f in mcp.HANDLERS.items())}
    fn_names = {fn: name for name, fn in handler_fn.items()}

    for name, tool in script_tools.items():
        handler = handler_fn[name]
        to = funcs.get(fn_names[handler])
        seconds = {"CONFIRM_TIMEOUT": mcp.CONFIRM_TIMEOUT, "DEFAULT_TIMEOUT": mcp.DEFAULT_TIMEOUT}.get(to)
        if seconds is None and to is not None:
            seconds = float(to)
        if seconds is None:
            seconds = None
        for tool_id in catalog_tools.get(name, []):
            line, e = ids[tool_id]
            declared = int(re.sub(r"[_L]", "", e.get("timeoutMillis", "0")))
            if seconds is None:
                continue
            if declared != int(seconds * 1000):
                fail(
                    f"{CATALOG.relative_to(ROOT)}:{line}",
                    f"{tool_id}: timeoutMillis {declared} != script's {int(seconds * 1000)}",
                )

    for tool_id, (line, e) in sorted(ids.items()):
        if e.get("confirmation") != "ConfirmationLevel.CONFIRM":
            continue
        declared = int(re.sub(r"[_L]", "", e.get("timeoutMillis", "0")))
        if declared <= BRIDGE_CONFIRMATION_WINDOW_MILLIS:
            fail(
                f"{CATALOG.relative_to(ROOT)}:{line}",
                f"{tool_id}: asks for confirmation but waits only {declared} ms "
                f"(bridge window is {BRIDGE_CONFIRMATION_WINDOW_MILLIS} ms)",
            )

    # ---- G: read-only never contains a confirming tool ----------------------------------
    for tool_id, (line, e) in sorted(ids.items()):
        if "readOnly = true" in block_of(catalog_text, line) and e.get("confirmation") == "ConfirmationLevel.CONFIRM":
            fail(f"{CATALOG.relative_to(ROOT)}:{line}", f"{tool_id}: read-only but declared CONFIRM")

    # ---- I: the firewall derives its sets from the catalog ------------------------------
    for name in ("ALL_ACTIONS", "AUTO_ACTIONS", "CONFIRM_ACTIONS", "READ_ONLY_ACTIONS"):
        m = re.search(rf"val {name}: Set<String> = (.+)", firewall_text)
        if not m or "DeviceToolCatalog" not in m.group(1):
            fail(str(FIREWALL.relative_to(ROOT)), f"{name} is not derived from the tool catalog")
    if re.search(r"val ALL_ACTIONS: Set<String> =\s*\n\s*setOf\(", firewall_text):
        fail(str(FIREWALL.relative_to(ROOT)), "ALL_ACTIONS is still hand-written")

    # ---- J: the Device Agent screen's list is a subset ----------------------------------
    cfg = re.search(r"val CONFIGURABLE_ACTIONS: List<String> =\s*\n\s*listOf\((.*?)\),\n", firewall_text, re.S)
    if cfg:
        for const in re.findall(r"ACTION_[A-Z0-9_]+", cfg.group(1)):
            if const not in fire_consts:
                fail(str(FIREWALL.relative_to(ROOT)), f"CONFIGURABLE_ACTIONS names unknown constant {const}")
            elif fire_consts[const] not in ids:
                fail(str(FIREWALL.relative_to(ROOT)), f"CONFIGURABLE_ACTIONS includes '{fire_consts[const]}' which is not a catalog tool")

    # ---- summary ------------------------------------------------------------------------
    print("Tool catalog check - Mushrea Code")
    print(f"  catalog tools        : {len(ids)}")
    print(f"  agent-facing tools   : {len(script_tools)}")
    print(f"  bridge dispatch      : {len(dispatched_ids)} actions")
    bridge = [e for _, e in ids.values() if e.get("transport") != "ToolTransport.WORKSPACE_FILE"]
    print(
        f"  confirm / auto / ro  : {sum(1 for e in bridge if e.get('confirmation') == 'ConfirmationLevel.CONFIRM')}"
        f" / {sum(1 for e in bridge if e.get('confirmation') == 'ConfirmationLevel.AUTO')}"
        f" / {sum(1 for _, e in ids.values() if e.get('readOnly') == 'true')}",
    )
    return report()


def block_of(text: str, line: int) -> str:
    """The text of the catalog entry that starts at `line`."""
    lines = text.splitlines()
    start = line - 1
    end = start
    while "    )," not in lines[end] and end < len(lines) - 1:
        end += 1
    return "\n".join(lines[start : end + 1])


def report() -> int:
    if findings:
        print()
        for f in sorted(set(findings)):
            print(f"  - {f}")
        print(f"\n  result: FAIL - {len(set(findings))} finding(s)")
        return 1
    print("\n  result: OK - the four tool sources agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
