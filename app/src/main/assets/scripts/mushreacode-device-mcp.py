#!/usr/bin/env python3
"""MushreaCode device-control MCP server (stdlib only).

Bridges the coding agent to the MushreaCode Android app's Device Agent so the agent can operate
THE device it runs on: open apps, read the current screen through the Accessibility engine, find
elements, tap, swipe, type, navigate (Back/Home/Recents), and search/open/share files.

Transport: a command file in the active workspace (``.mushrea-code/device-command.json``). The app's
accessibility service polls it, applies the Permission Firewall (asking the user when an action
needs confirmation), executes the action, verifies it, and writes ``.mushrea-code/device-result.json``.
This is the same file-channel pattern the guest-browser MCP uses.

Requirements on the app side: enable Mushrea Code in Android's Accessibility settings (the app's
Device Agent screen links straight there).

Speaks MCP over stdio (newline-delimited JSON-RPC) with no third-party deps so it runs on the bare
runtime rootfs python3.
"""

import json
import os
import sys
import time
import uuid
from pathlib import Path

COMMAND_FILE = Path(".mushrea-code") / "device-command.json"
RESULT_FILE = Path(".mushrea-code") / "device-result.json"

DEFAULT_TIMEOUT = 45.0
CONFIRM_TIMEOUT = 150.0


def _request(action: str, params: dict | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict:
    """Drops one command and waits for the app's result. Raises RuntimeError on failure."""
    params = params or {}
    COMMAND_FILE.parent.mkdir(parents=True, exist_ok=True)
    # Clear any stale result so we never read an older response.
    RESULT_FILE.unlink(missing_ok=True)
    request_id = uuid.uuid4().hex[:12]
    COMMAND_FILE.write_text(
        json.dumps({"id": request_id, "action": action, "params": params, "ts": int(time.time() * 1000)}),
        encoding="utf-8",
    )
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = _consume_result(request_id)
        if result is not None:
            if result.get("ok"):
                return result.get("result", {})
            raise RuntimeError(result.get("error", "device action failed"))
        time.sleep(0.3)
    raise RuntimeError(
        f"timed out waiting for the app to run '{action}'. "
        "Is Mushrea Code enabled in Accessibility settings, and is this chat's workspace still open?"
    )


def _consume_result(request_id: str) -> dict | None:
    try:
        if not RESULT_FILE.is_file():
            return None
        data = json.loads(RESULT_FILE.read_text(encoding="utf-8"))
        if data.get("id") != request_id:
            return None
        RESULT_FILE.unlink(missing_ok=True)
        return data
    except (OSError, json.JSONDecodeError):
        return None


def _text_result(payload: dict) -> str:
    summary = payload.get("summary")
    extras = {k: v for k, v in payload.items() if k not in ("summary",)}
    if not extras:
        return str(summary or "done")
    parts = [f"summary: {summary}"] if summary else []
    parts.append(json.dumps(extras, ensure_ascii=False))
    return "\n".join(parts)


def tool_open_app(args: dict) -> str:
    return _text_result(_request("open_app", {"app": args["app"]}, timeout=30))


def tool_current_app(_args: dict) -> str:
    return _text_result(_request("get_current_app", {}, timeout=15))


def tool_read_screen(args: dict) -> str:
    payload = _request("read_screen", {})
    return payload.get("screen") or json.dumps(payload, ensure_ascii=False)


def tool_find_element(args: dict) -> str:
    return _text_result(_request("find_element", {"query": args["query"]}))


def tool_tap(args: dict) -> str:
    params = {}
    if "query" in args:
        params["query"] = args["query"]
    if "index" in args:
        params["index"] = args["index"]
    if "x" in args and "y" in args:
        params["x"] = args["x"]
        params["y"] = args["y"]
    return _text_result(_request("tap", params, timeout=CONFIRM_TIMEOUT))


def tool_long_press(args: dict) -> str:
    params = {"query": args["query"]} if "query" in args else {"index": args.get("index", -1)}
    return _text_result(_request("long_press", params, timeout=CONFIRM_TIMEOUT))


def tool_swipe(args: dict) -> str:
    return _text_result(
        _request(
            "swipe",
            {"from": args["from"], "to": args["to"], "durationMs": args.get("durationMs", 250)},
            timeout=CONFIRM_TIMEOUT,
        )
    )


def tool_scroll(args: dict) -> str:
    return _text_result(_request("scroll", {"direction": args.get("direction", "down")}, timeout=CONFIRM_TIMEOUT))


def tool_type_text(args: dict) -> str:
    return _text_result(
        _request("type_text", {"text": args["text"], "append": args.get("append", False)}, timeout=CONFIRM_TIMEOUT)
    )


def tool_clear_text(_args: dict) -> str:
    return _text_result(_request("clear_text", {}, timeout=CONFIRM_TIMEOUT))


def tool_press(args: dict) -> str:
    which = args.get("which", "back")
    action = {"back": "press_back", "home": "press_home", "recents": "open_recents"}.get(which)
    if action is None:
        raise RuntimeError("which must be one of: back, home, recents")
    return _text_result(_request(action, {}))


def tool_open_url(args: dict) -> str:
    return _text_result(_request("open_url", {"url": args["url"]}, timeout=30))


def tool_list_apps(_args: dict) -> str:
    return _text_result(_request("list_apps", {}, timeout=30))


def tool_search_files(args: dict) -> str:
    params = {}
    if args.get("query"):
        params["query"] = args["query"]
    if args.get("extension"):
        params["extension"] = args["extension"]
    if args.get("dir"):
        params["dir"] = args["dir"]
    return _text_result(_request("search_files", params, timeout=60))


def tool_open_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", "")}
    return _text_result(_request("open_file", params, timeout=30))


def tool_share_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", "")}
    return _text_result(_request("share_file", params, timeout=CONFIRM_TIMEOUT))


def tool_delete_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", "")}
    return _text_result(_request("delete_file", params, timeout=CONFIRM_TIMEOUT))


def tool_move_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", ""), "to": args["to"]}
    return _text_result(_request("move_file", params, timeout=CONFIRM_TIMEOUT))


def tool_copy_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", ""), "to": args["to"]}
    return _text_result(_request("copy_file", params, timeout=CONFIRM_TIMEOUT))


def tool_rename_file(args: dict) -> str:
    params = {"path": args.get("path", ""), "name": args.get("name", ""), "new_name": args["new_name"]}
    return _text_result(_request("rename_file", params, timeout=CONFIRM_TIMEOUT))


def tool_stop(_args: dict) -> str:
    return _text_result(_request("stop_agent", {}, timeout=15))


def tool_status(_args: dict) -> str:
    try:
        payload = _request("get_current_app", {}, timeout=10)
        return f"connected: the Device Agent bridge answered; current app: {payload.get('package', '?')}"
    except RuntimeError as exc:
        return f"not connected: {exc}"


TOOLS = [
    {
        "name": "device_open_app",
        "description": "Open an installed app by name (\"youtube\", \"يوتيوب\") or package name. Verified afterwards.",
        "inputSchema": {"type": "object", "properties": {"app": {"type": "string"}}, "required": ["app"]},
    },
    {
        "name": "device_current_app",
        "description": "Report the current foreground app and activity.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_read_screen",
        "description": "Read the current screen through Accessibility: app, activity, numbered list of visible elements with labels, roles and bounds.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_find_element",
        "description": "Find UI elements on the current screen by text/label/id and get their element indexes.",
        "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
    },
    {
        "name": "device_tap",
        "description": "Tap a screen element by query or index (semantic, preferred), or by x/y coordinates (fallback). Sensitive controls (pay/delete/send) require user confirmation.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "index": {"type": "integer"},
                "x": {"type": "number"},
                "y": {"type": "number"},
            },
        },
    },
    {
        "name": "device_long_press",
        "description": "Long-press an element by query or index.",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string"}, "index": {"type": "integer"}},
        },
    },
    {
        "name": "device_swipe",
        "description": "Swipe between two screen points: from{x,y} to{x,y}, optional durationMs.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "from": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}}},
                "to": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}}},
                "durationMs": {"type": "integer"},
            },
            "required": ["from", "to"],
        },
    },
    {
        "name": "device_scroll",
        "description": "Scroll the current screen: direction up/down/left/right.",
        "inputSchema": {"type": "object", "properties": {"direction": {"type": "string"}}, "required": ["direction"]},
    },
    {
        "name": "device_type_text",
        "description": "Type text into the focused input field (append=true keeps existing text).",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}, "append": {"type": "boolean"}},
            "required": ["text"],
        },
    },
    {
        "name": "device_clear_text",
        "description": "Clear the focused input field.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_press",
        "description": "Global navigation: which = back | home | recents.",
        "inputSchema": {"type": "object", "properties": {"which": {"type": "string"}}, "required": ["which"]},
    },
    {
        "name": "device_open_url",
        "description": "Open an http/https URL in the default browser/app.",
        "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]},
    },
    {
        "name": "device_list_apps",
        "description": "List installed launchable apps (label + package).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_search_files",
        "description": "Search device storage files by name query and/or extension (e.g. pdf).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "extension": {"type": "string"},
                "dir": {"type": "string"},
            },
        },
    },
    {
        "name": "device_open_file",
        "description": "Open a file on the device (by path, or by name which is searched).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "name": {"type": "string"}},
        },
    },
    {
        "name": "device_share_file",
        "description": "Open the Android share sheet for a file (confirmation required by default).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "name": {"type": "string"}},
        },
    },
    {
        "name": "device_delete_file",
        "description": "Delete a file (confirmation required by default).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "name": {"type": "string"}},
        },
    },
    {
        "name": "device_move_file",
        "description": "Move a file into directory `to` (confirmation required by default).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "name": {"type": "string"}, "to": {"type": "string"}},
            "required": ["to"],
        },
    },
    {
        "name": "device_copy_file",
        "description": "Copy a file into directory `to` (confirmation required by default).",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "name": {"type": "string"}, "to": {"type": "string"}},
            "required": ["to"],
        },
    },
    {
        "name": "device_rename_file",
        "description": "Rename a file to new_name (confirmation required by default).",
        "inputSchema": {
            "type": "object",
            "properties": {"new_name": {"type": "string"}},
            "required": ["new_name"],
        },
    },
    {
        "name": "device_stop",
        "description": "EMERGENCY STOP: ask the app to halt the current device task; no further device action starts after this.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_status",
        "description": "Check whether the on-device bridge is reachable (accessibility enabled) and report the current app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

HANDLERS = {
    "device_open_app": tool_open_app,
    "device_current_app": tool_current_app,
    "device_read_screen": tool_read_screen,
    "device_find_element": tool_find_element,
    "device_tap": tool_tap,
    "device_long_press": tool_long_press,
    "device_swipe": tool_swipe,
    "device_scroll": tool_scroll,
    "device_type_text": tool_type_text,
    "device_clear_text": tool_clear_text,
    "device_press": tool_press,
    "device_open_url": tool_open_url,
    "device_list_apps": tool_list_apps,
    "device_search_files": tool_search_files,
    "device_open_file": tool_open_file,
    "device_share_file": tool_share_file,
    "device_delete_file": tool_delete_file,
    "device_move_file": tool_move_file,
    "device_copy_file": tool_copy_file,
    "device_rename_file": tool_rename_file,
    "device_stop": tool_stop,
    "device_status": tool_status,
}


def respond(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            respond(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "result": {
                        "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "mushrea-code-device", "version": "1.0.0"},
                    },
                }
            )
        elif method in ("notifications/initialized", "initialized"):
            continue
        elif method == "tools/list":
            respond({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {}) or {}
            handler = HANDLERS.get(name)
            if handler is None:
                respond(
                    {
                        "jsonrpc": "2.0",
                        "id": mid,
                        "result": {"content": [{"type": "text", "text": f"unknown tool: {name}"}], "isError": True},
                    }
                )
            else:
                try:
                    text = handler(args)
                    respond({"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}]}})
                except Exception as exc:  # noqa: BLE001 - surfaced to the agent as a tool error
                    respond(
                        {
                            "jsonrpc": "2.0",
                            "id": mid,
                            "result": {"content": [{"type": "text", "text": str(exc)}], "isError": True},
                        }
                    )
        elif method == "ping":
            respond({"jsonrpc": "2.0", "id": mid, "result": {}})


if __name__ == "__main__":
    main()
