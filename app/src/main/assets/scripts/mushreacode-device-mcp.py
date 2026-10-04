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
CONTEXT_FILE = Path(".mushrea-code") / "device-context.json"

DEFAULT_TIMEOUT = 45.0
CONFIRM_TIMEOUT = 150.0
# Provisioning is a sequence (pair -> connect -> probe -> settings -> proof), and a reconnect may
# legitimately wait out a bounded backoff, so both need their own budget rather than a longer default.
PROVISION_TIMEOUT = 300.0
RECONNECT_TIMEOUT = 240.0


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
        "Is Mushrea Code enabled in Accessibility settings, and is the app (runtime) running? "
        "The bridge listens on this workspace automatically; retry once if the app was just started."
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


def tool_search_and_type(args: dict) -> str:
    return _text_result(
        _request(
            "search_and_type",
            {
                "text": args["text"],
                "query": args.get("query", ""),
                "clear": args.get("clear", True),
                "submit": args.get("submit", True),
            },
            timeout=CONFIRM_TIMEOUT,
        )
    )


def tool_scroll_until_found(args: dict) -> str:
    return _text_result(
        _request(
            "scroll_until_found",
            {
                "query": args["query"],
                "max_swipes": args.get("max_swipes", 8),
                "tap": args.get("tap", False),
            },
            timeout=CONFIRM_TIMEOUT,
        )
    )


def tool_wait_for_element(args: dict) -> str:
    return _text_result(
        _request(
            "wait_for_element",
            {
                "query": args["query"],
                "timeout_ms": args.get("timeout_ms", 5000),
                "tap": args.get("tap", False),
            },
            timeout=CONFIRM_TIMEOUT,
        )
    )


def tool_find_contact(args: dict) -> str:
    return _text_result(_request("find_contact", {"query": args["query"]}, timeout=CONFIRM_TIMEOUT))


def tool_call_agent(args: dict) -> str:
    return _text_result(
        _request("call_agent", {"command": args["command"]}, timeout=CONFIRM_TIMEOUT)
    )


def tool_call_state(_args: dict) -> str:
    return _text_result(_request("call_state", {}, timeout=CONFIRM_TIMEOUT))


def tool_call_stop(_args: dict) -> str:
    return _text_result(_request("call_stop", {}, timeout=CONFIRM_TIMEOUT))


def tool_call_record_start(args: dict) -> str:
    payload = {}
    if "seconds" in args:
        payload["seconds"] = int(args["seconds"])
    return _text_result(_request("call_record_start", payload, timeout=CONFIRM_TIMEOUT))


def tool_call_record_stop(_args: dict) -> str:
    return _text_result(_request("call_record_stop", {}, timeout=30.0))


def tool_call_log(_args: dict) -> str:
    return _text_result(_request("read_call_log", {}, timeout=CONFIRM_TIMEOUT))


def tool_device_status(_args: dict) -> str:
    return _text_result(_request("device_status", {}, timeout=CONFIRM_TIMEOUT))


def tool_call_summaries(args: dict) -> str:
    return _text_result(_request("call_summaries", {"limit": int(args.get("limit", 10))}, timeout=CONFIRM_TIMEOUT))


def tool_usb_devices(_args: dict) -> str:
    return _text_result(_request("usb_devices", {}, timeout=CONFIRM_TIMEOUT))


def tool_usb_shell(args: dict) -> str:
    return _text_result(_request("usb_shell", {"command": args["command"]}, timeout=180.0))


def tool_usb_list(args: dict) -> str:
    return _text_result(_request("usb_list", {"remote_path": args.get("remote_path", "/sdcard")}, timeout=CONFIRM_TIMEOUT))


def tool_usb_pull(args: dict) -> str:
    return _text_result(_request("usb_pull", {"remote_path": args["remote_path"]}, timeout=600.0))


def tool_usb_push(args: dict) -> str:
    return _text_result(_request("usb_push", {"local_path": args["local_path"], "remote_dir": args.get("remote_dir", "/sdcard/Download/")}, timeout=600.0))


def tool_usb_transfer_media(args: dict) -> str:
    return _text_result(_request("usb_transfer_media", {"max_megabytes": int(args.get("max_megabytes", 200))}, timeout=1800.0))


def tool_usb_screenshot(_args: dict) -> str:
    return _text_result(_request("usb_screenshot", {}, timeout=CONFIRM_TIMEOUT))


def tool_mirror_start(_args: dict) -> str:
    return _text_result(_request("mirror_start", {}, timeout=CONFIRM_TIMEOUT))


def tool_mirror_stop(_args: dict) -> str:
    return _text_result(_request("mirror_stop", {}, timeout=30.0))


def tool_scrcpy_start(_args: dict) -> str:
    return _text_result(_request("scrcpy_start", {}, timeout=CONFIRM_TIMEOUT))


def tool_scrcpy_stop(_args: dict) -> str:
    return _text_result(_request("scrcpy_stop", {}, timeout=CONFIRM_TIMEOUT))


def tool_usb_hub_list(_args: dict) -> str:
    return _text_result(_request("usb_hub_list", {}, timeout=30.0))


def tool_mtp_list(args: dict) -> str:
    return _text_result(_request("mtp_list", {k: args[k] for k in ("device_id", "storage_id", "parent") if k in args}, timeout=60.0))


def tool_mtp_download(args: dict) -> str:
    payload = {"handle": args["handle"]}
    if "name" in args:
        payload["name"] = args["name"]
    if "device_id" in args:
        payload["device_id"] = args["device_id"]
    return _text_result(_request("mtp_download", payload, timeout=1800.0))


def tool_mtp_upload(args: dict) -> str:
    payload = {"local_path": args["local_path"]}
    for key in ("name", "device_id", "storage_id", "parent"):
        if key in args:
            payload[key] = args[key]
    return _text_result(_request("mtp_upload", payload, timeout=1800.0))


def tool_hid_read(args: dict) -> str:
    payload = {}
    if "device_id" in args:
        payload["device_id"] = args["device_id"]
    if "seconds" in args:
        payload["seconds"] = args["seconds"]
    return _text_result(_request("hid_read", payload, timeout=CONFIRM_TIMEOUT))


def tool_storage_volumes(_args: dict) -> str:
    return _text_result(_request("storage_volumes", {}, timeout=30.0))


def tool_camera_list(_args: dict) -> str:
    return _text_result(_request("camera_list", {}, timeout=30.0))


def tool_camera_capture(args: dict) -> str:
    payload = {k: args[k] for k in ("camera_id", "facing") if k in args}
    return _text_result(_request("camera_capture", payload, timeout=CONFIRM_TIMEOUT))


def tool_net_browse(args: dict) -> str:
    payload = {"seconds": args["seconds"]} if "seconds" in args else {}
    return _text_result(_request("net_browse", payload, timeout=60.0))


def tool_remote_list(args: dict) -> str:
    payload = {k: args[k] for k in ("protocol", "host", "port", "path", "user", "password") if k in args}
    return _text_result(_request("remote_list", payload, timeout=120.0))


def tool_remote_download(args: dict) -> str:
    payload = {k: args[k] for k in ("protocol", "host", "port", "path", "name", "user", "password") if k in args}
    return _text_result(_request("remote_download", payload, timeout=1800.0))


def tool_wifi_info(_args: dict) -> str:
    return _text_result(_request("wifi_info", {}, timeout=30.0))


def tool_dns_lookup(args: dict) -> str:
    return _text_result(_request("dns_lookup", {"host": args["host"]}, timeout=30.0))


def tool_net_ping(args: dict) -> str:
    payload = {"host": args["host"], "count": int(args.get("count", 4))}
    return _text_result(_request("net_ping", payload, timeout=120.0))


def tool_port_check(args: dict) -> str:
    payload = {"host": args["host"], "port": int(args["port"]), "seconds": int(args.get("seconds", 3))}
    return _text_result(_request("port_check", payload, timeout=60.0))


def tool_http_request(args: dict) -> str:
    payload = {k: args[k] for k in ("url", "method", "headers", "body", "seconds") if k in args}
    return _text_result(_request("http_request", payload, timeout=CONFIRM_TIMEOUT))


def tool_websocket(args: dict) -> str:
    payload = {k: args[k] for k in ("url", "message", "seconds") if k in args}
    return _text_result(_request("websocket", payload, timeout=CONFIRM_TIMEOUT))


def tool_bt_info(_args: dict) -> str:
    return _text_result(_request("bt_info", {}, timeout=30.0))


def tool_bt_devices(_args: dict) -> str:
    return _text_result(_request("bt_devices", {}, timeout=30.0))


def tool_bt_scan(args: dict) -> str:
    payload = {"seconds": int(args.get("seconds", 12))}
    return _text_result(_request("bt_scan", payload, timeout=60.0))


def tool_ble_scan(args: dict) -> str:
    payload = {"seconds": int(args.get("seconds", 8))}
    return _text_result(_request("ble_scan", payload, timeout=60.0))


def tool_usb_install(args: dict) -> str:
    return _text_result(_request("usb_install", {"local_path": args["local_path"]}, timeout=600.0))


def tool_usb_logcat(args: dict) -> str:
    return _text_result(_request("usb_logcat", {"lines": int(args.get("lines", 200))}, timeout=CONFIRM_TIMEOUT))


def tool_usb_info(_args: dict) -> str:
    return _text_result(_request("usb_info", {}, timeout=60.0))


def tool_usb_serial_send(args: dict) -> str:
    return _text_result(_request("usb_serial_send", {"text": args["text"], "baudrate": int(args.get("baudrate", 115200)), "newline": bool(args.get("newline", True))}, timeout=CONFIRM_TIMEOUT))


def tool_usb_serial_read(args: dict) -> str:
    return _text_result(_request("usb_serial_read", {"baudrate": int(args.get("baudrate", 115200)), "milliseconds": int(args.get("milliseconds", 1000))}, timeout=30.0))


def tool_usb_tcpip_enable(_args: dict) -> str:
    return _text_result(_request("usb_tcpip_enable", {}, timeout=CONFIRM_TIMEOUT))


def tool_tcp_shell(args: dict) -> str:
    return _text_result(_request("tcp_shell", {"host": args["host"], "port": int(args.get("port", 5555)), "command": args["command"]}, timeout=CONFIRM_TIMEOUT))


def _ssh_params(args: dict) -> dict:
    params = {"host": args["host"], "username": args["username"], "port": int(args.get("port", 22))}
    if args.get("password"):
        params["password"] = args["password"]
    if args.get("private_key"):
        params["private_key"] = args["private_key"]
    return params


def tool_ssh_exec(args: dict) -> str:
    params = _ssh_params(args)
    params["command"] = args["command"]
    params["timeout_seconds"] = int(args.get("timeout_seconds", 60))
    return _text_result(_request("ssh_exec", params, timeout=int(params["timeout_seconds"]) + 30.0))


def tool_ssh_list(args: dict) -> str:
    params = _ssh_params(args)
    params["path"] = args.get("path", ".")
    return _text_result(_request("ssh_list", params, timeout=60.0))


def tool_ssh_download(args: dict) -> str:
    params = _ssh_params(args)
    params["remote_path"] = args["remote_path"]
    return _text_result(_request("ssh_download", params, timeout=360.0))


def tool_ssh_upload(args: dict) -> str:
    params = _ssh_params(args)
    params["local_path"] = args["local_path"]
    params["remote_dir"] = args.get("remote_dir", ".")
    return _text_result(_request("ssh_upload", params, timeout=360.0))


def tool_payload_info(args: dict) -> str:
    return _text_result(_request("payload_info", {"file_path": args["file_path"]}, timeout=60.0))


def tool_payload_extract(args: dict) -> str:
    return _text_result(_request("payload_extract", {"file_path": args["file_path"], "partition": args["partition"]}, timeout=1800.0))


def tool_fastboot_getvar(_args: dict) -> str:
    return _text_result(_request("fastboot_getvar", {}, timeout=60.0))


def tool_usb_mode(_args: dict) -> str:
    return _text_result(_request("usb_mode", {}, timeout=30.0))


def tool_usb_diagnostics(_args: dict) -> str:
    return _text_result(_request("usb_diagnostics", {}, timeout=60.0))


def tool_fastboot_getvar_full(args: dict) -> str:
    return _text_result(
        _request(
            "fastboot_getvar_full",
            {"reveal_token": bool(args.get("reveal_token", False))},
            timeout=CONFIRM_TIMEOUT,
        )
    )


def tool_payload_guard(args: dict) -> str:
    return _text_result(
        _request(
            "payload_guard",
            {"file_path": args["file_path"], "device_product": args.get("device_product", "")},
            timeout=150.0,
        )
    )


def tool_safety_preflight(args: dict) -> str:
    params: dict = {}
    if args.get("file_path"):
        params["file_path"] = args["file_path"]
    if args.get("device_product"):
        params["device_product"] = args["device_product"]
    return _text_result(_request("safety_preflight", params, timeout=150.0))


def tool_audit_export(_args: dict) -> str:
    return _text_result(_request("audit_export", {}, timeout=CONFIRM_TIMEOUT))


def tool_termux_status(_args: dict) -> str:
    return _text_result(_request("termux_status", {}, timeout=30.0))


def tool_termux_run(args: dict) -> str:
    return _text_result(
        _request(
            "termux_run",
            {"command": args["command"], "args": args.get("args", [])},
            timeout=150.0,
        )
    )


def tool_termux_fastboot_run(args: dict) -> str:
    return _text_result(
        _request(
            "termux_fastboot_run",
            {"args": args["args"], "device_product": args.get("device_product", "")},
            timeout=180.0,
        )
    )


def tool_mitool_wrapper(args: dict) -> str:
    return _text_result(_request("mitool_wrapper", {"step": args.get("step", "status")}, timeout=300.0))


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


def tool_get_context(_args: dict) -> str:
    """Reads the app-maintained context file directly (no command round-trip)."""
    try:
        data = json.loads(CONTEXT_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return (
            "no device context yet — call device_status or device_current_app once; "
            "the app publishes context as soon as its accessibility engine is running"
        )
    except (OSError, json.JSONDecodeError) as exc:
        return f"context unreadable: {exc}"
    return json.dumps(data, ensure_ascii=False)


def tool_set_task(args: dict) -> str:
    return _text_result(_request("set_task", {"goal": args["goal"]}, timeout=15))


def tool_status(_args: dict) -> str:
    try:
        payload = _request("get_current_app", {}, timeout=10)
        return f"connected: the Device Agent bridge answered; current app: {payload.get('package', '?')}"
    except RuntimeError as exc:
        return f"not connected: {exc}"


# --- Peer ADB: another phone over wireless debugging ---------------------------------------------
# The user pairs the phone once (QR or code, from the app's Devices screen or the tools above); the
# agent then drives it through one generic execution tool, so the ceiling is what adb and that phone
# can do rather than what is enumerated here.


def tool_peer_devices(_args: dict) -> str:
    return _text_result(_request("peer_devices", {}, timeout=DEFAULT_TIMEOUT))


def tool_peer_capabilities(args: dict) -> str:
    return _text_result(_request("peer_capabilities", {"serial": args["serial"]}, timeout=120.0))


def tool_peer_plan(args: dict) -> str:
    params = {"serial": args["serial"]}
    for key in ("operations", "goal", "recipe"):
        if args.get(key):
            params[key] = args[key]
    if args.get("parameters"):
        params["parameters"] = args["parameters"]
    return _text_result(_request("peer_plan", params, timeout=DEFAULT_TIMEOUT))


def tool_peer_pair_qr(_args: dict) -> str:
    return _text_result(_request("peer_pair_qr", {}, timeout=CONFIRM_TIMEOUT))


def tool_peer_pair_code(args: dict) -> str:
    return _text_result(
        _request("peer_pair_code", {"host": args["host"], "port": int(args["port"]), "code": args["code"]}, timeout=CONFIRM_TIMEOUT)
    )


def tool_peer_connect(args: dict) -> str:
    return _text_result(_request("peer_connect", {"serial": args["serial"]}, timeout=CONFIRM_TIMEOUT))


def tool_peer_disconnect(args: dict) -> str:
    return _text_result(_request("peer_disconnect", {"serial": args["serial"]}, timeout=DEFAULT_TIMEOUT))


def tool_peer_execute(args: dict) -> str:
    params = {"serial": args["serial"]}
    for key in ("operation", "command", "interpreter", "reason", "verify_command", "goal", "recipe"):
        if args.get(key):
            params[key] = args[key]
    if args.get("parameters"):
        params["parameters"] = args["parameters"]
    if args.get("arguments"):
        params["arguments"] = args["arguments"]
    if args.get("files"):
        params["files"] = args["files"]
    if args.get("timeout_seconds"):
        params["timeout_seconds"] = int(args["timeout_seconds"])
    return _text_result(_request("peer_execute", params, timeout=180.0))


def tool_peer_provision(args: dict) -> str:
    params = {"serial": args["serial"]}
    for key in ("code", "purpose"):
        if args.get(key):
            params[key] = args[key]
    if args.get("hints"):
        params["hints"] = args["hints"]
    if args.get("names"):
        params["names"] = args["names"]
    if args.get("persistence") is not None:
        params["persistence"] = bool(args["persistence"])
    if args.get("allow_public") is not None:
        params["allow_public"] = bool(args["allow_public"])
    if args.get("attempts"):
        params["attempts"] = int(args["attempts"])
    return _text_result(_request("peer_provision", params, timeout=PROVISION_TIMEOUT))


def tool_peer_reconnect(args: dict) -> str:
    params = {"serial": args["serial"]}
    for key in ("attempts", "initial_delay_ms", "max_delay_ms"):
        if args.get(key) is not None:
            params[key] = int(args[key])
    if args.get("endpoints"):
        params["endpoints"] = args["endpoints"]
    return _text_result(_request("peer_reconnect", params, timeout=RECONNECT_TIMEOUT))


def tool_peer_endpoints(args: dict) -> str:
    return _text_result(_request("peer_endpoints", {"serial": args["serial"]}, timeout=DEFAULT_TIMEOUT))


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
        "name": "device_find_contact",
        "description": "Look up a contact by name and get their phone number.",
        "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
    },
    {
        "name": "device_call_agent",
        "description": "Start the voice call agent with a natural command like: \u0627\u062a\u0635\u0644 \u0628\u0623\u062d\u0645\u062f \u0648\u0627\u0633\u0623\u0644\u0647 \u0623\u064a\u0646 \u0647\u0648. The agent dials, introduces itself as an automated assistant, asks the goals, and summarizes. Requires user confirmation.",
        "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]},
    },
    {
        "name": "device_call_state",
        "description": "Report the call agent's live state: call state, goals answered so far, last statements.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_call_stop",
        "description": "Stop the call agent immediately (it halts before its next turn).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "call_record_start",
        "description": "Record this phone's microphone during an active call (user confirms). The other party's audio is not available to unprivileged apps on stock Android — say so.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "seconds": {"type": "integer", "description": "Optional auto-stop after 5-1800 s; omit to record until call_record_stop"},
            },
        },
    },
    {
        "name": "call_record_stop",
        "description": "Stop an in-progress near-end call recording and report the saved file.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_call_log",
        "description": "The most recent calls (missed included): number, contact name, type.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_status",
        "description": "Battery, charging, network, ringer and screen-lock state of the phone.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_call_summaries",
        "description": "Stored call-agent summaries: purpose, answers, caller facts and outcome.",
        "inputSchema": {
            "type": "object",
            "properties": {"limit": {"type": "integer", "description": "How many (newest last, default 10)"}},
        },
    },
    {
        "name": "usb_devices",
        "description": "Android phones attached over USB that speak ADB (OTG cable required).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "usb_shell",
        "description": "Run a shell command on the attached Android phone over USB (asks the user to confirm).",
        "inputSchema": {
            "type": "object",
            "properties": {"command": {"type": "string", "description": "Shell command to run on the other phone"}},
            "required": ["command"],
        },
    },
    {
        "name": "usb_list",
        "description": "Browse a directory on the attached phone: names, sizes, folders.",
        "inputSchema": {
            "type": "object",
            "properties": {"remote_path": {"type": "string", "description": "Path on the other phone (default /sdcard)"}},
        },
    },
    {
        "name": "usb_pull",
        "description": "Copy a file or a whole folder from the attached phone into this phone's Download/mushrea-usb (asks the user to confirm).",
        "inputSchema": {
            "type": "object",
            "properties": {"remote_path": {"type": "string", "description": "File or folder on the other phone, e.g. /sdcard/DCIM/Camera"}},
            "required": ["remote_path"],
        },
    },
    {
        "name": "usb_push",
        "description": "Copy one local file to the attached phone's Download folder (asks the user to confirm).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "local_path": {"type": "string", "description": "File on this phone"},
                "remote_dir": {"type": "string", "description": "Target folder on the other phone (default /sdcard/Download/)"},
            },
            "required": ["local_path"],
        },
    },
    {
        "name": "usb_transfer_media",
        "description": "Bulk-copy photos and videos from the other phone's DCIM and Pictures (the moved-to-a-new-phone helper; size-capped, user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {"max_megabytes": {"type": "integer", "description": "Total size cap in MB (default 200)"}},
        },
    },
    {
        "name": "usb_screenshot",
        "description": "Capture the attached phone's screen as a PNG into this phone's Download/mushrea-usb (user confirms).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "mirror_start",
        "description": "Show the attached phone's live screen inside this app, view-only (user confirms). Uses the phone's own screenrecord streaming; nothing is installed on it. Each take is capped near 3 minutes by the system and restarts itself.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "mirror_stop",
        "description": "Stop the live screen mirror if one is running.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "scrcpy_start",
        "description": "Start the scrcpy session: the attached phone's live screen with real control - touches, scrolls and hardware keys act on the other phone (user confirms). Uses the official scrcpy server pushed over adb; nothing is installed as an app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "scrcpy_stop",
        "description": "Stop the scrcpy session if one is running.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "usb_hub_list",
        "description": "Every attached USB device classified by the hub: kind (adb, fastboot, serial, mtp/ptp, hid, storage, other), driver guess, and which tools handle it.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "mtp_list",
        "description": "List the storage volumes of a phone in MTP/File-Transfer mode, or the files inside one folder (pass storage_id, optionally parent handle from a previous listing).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "device_id": {"type": "integer", "description": "Optional USB device id from usb_hub_list"},
                "storage_id": {"type": "integer", "description": "Optional storage id (first volume when omitted)"},
                "parent": {"type": "integer", "description": "Optional parent folder handle (0 = root of the volume)"},
            },
        },
    },
    {
        "name": "mtp_download",
        "description": "Copy one file from an MTP/PTP device into this phone's Download/Mushrea-mtp (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "handle": {"type": "integer", "description": "Object handle from mtp_list"},
                "name": {"type": "string", "description": "File name to save as"},
                "device_id": {"type": "integer", "description": "Optional USB device id"},
            },
            "required": ["handle"],
        },
    },
    {
        "name": "mtp_upload",
        "description": "Copy one local file onto an MTP/PTP device under a parent folder from mtp_list (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "local_path": {"type": "string", "description": "Path of the file on this phone"},
                "name": {"type": "string", "description": "File name to store as on the other device"},
                "device_id": {"type": "integer", "description": "Optional USB device id"},
                "storage_id": {"type": "integer", "description": "Optional storage id (first volume when omitted)"},
                "parent": {"type": "integer", "description": "Optional parent folder handle (0 = root)"},
            },
            "required": ["local_path"],
        },
    },
    {
        "name": "hid_read",
        "description": "Capture HID input reports from an attached USB keyboard/mouse/sensor for a few seconds (user confirms) and decode boot-protocol keys and mouse motion. Raw hex is kept.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "device_id": {"type": "integer", "description": "Optional USB device id from usb_hub_list"},
                "seconds": {"type": "integer", "description": "Capture window 1-10 s (default 3)"},
            },
        },
    },
    {
        "name": "storage_volumes",
        "description": "The storage volumes Android sees - built-in and removable USB drives - with mount states.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "camera_list",
        "description": "All cameras Android exposes, flagging externally attached USB cameras (platform-dependent).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "camera_capture",
        "description": "Take one JPEG still from a Camera2-visible camera, including USB/external cameras Android exposes (user confirms). Webcams the platform does not publish are out of scope.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "camera_id": {"type": "string", "description": "Id from camera_list"},
                "facing": {"type": "string", "description": "front | back | external when camera_id is omitted"},
            },
        },
    },
    {
        "name": "net_browse",
        "description": "Browse the local network for services that advertise themselves (mDNS): ssh, smb, ftp, http, webdav, nfs, ipp.",
        "inputSchema": {
            "type": "object",
            "properties": {"seconds": {"type": "integer", "description": "Browse window 2-15 s (default 6)"}},
        },
    },
    {
        "name": "remote_list",
        "description": "List a folder on a machine the user has credentials for, over smb, ftp or webdav. Entries carry name, size and date.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "protocol": {"type": "string", "description": "smb | ftp | webdav"},
                "host": {"type": "string"},
                "port": {"type": "integer"},
                "path": {"type": "string", "description": "Folder path; smb paths look like host/share/dir"},
                "user": {"type": "string"},
                "password": {"type": "string"},
            },
            "required": ["protocol", "host"],
        },
    },
    {
        "name": "remote_download",
        "description": "Copy one file from a remote machine (smb, ftp, webdav or scp) into this phone's Download/Mushrea-remote (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "protocol": {"type": "string", "description": "smb | ftp | webdav | scp"},
                "host": {"type": "string"},
                "port": {"type": "integer"},
                "path": {"type": "string", "description": "The file to copy; smb paths look like host/share/dir/file"},
                "name": {"type": "string", "description": "File name to save as"},
                "user": {"type": "string"},
                "password": {"type": "string"},
            },
            "required": ["protocol", "host", "path"],
        },
    },
    {
        "name": "wifi_info",
        "description": "Wi-Fi state on this phone: enabled, ssid (hidden by Android without location permission), ip, gateway, signal and band.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "dns_lookup",
        "description": "Resolve a hostname into its IPv4/IPv6 addresses.",
        "inputSchema": {
            "type": "object",
            "properties": {"host": {"type": "string"}},
            "required": ["host"],
        },
    },
    {
        "name": "net_ping",
        "description": "ICMP ping a user-named host (authorized diagnostics only): sent/received/loss and round-trip stats.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "count": {"type": "integer", "description": "Echo requests 1-10 (default 4)"},
            },
            "required": ["host"],
        },
    },
    {
        "name": "port_check",
        "description": "One TCP connect against a user-named host:port (authorized diagnostics only): open/closed and latency.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "port": {"type": "integer"},
                "seconds": {"type": "integer", "description": "Connect timeout 1-10 s (default 3)"},
            },
            "required": ["host", "port"],
        },
    },
    {
        "name": "http_request",
        "description": "Send an HTTP/HTTPS request (user confirms). Body is captured up to 64 KiB.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string"},
                "method": {"type": "string", "description": "GET HEAD POST PUT DELETE PATCH (default GET)"},
                "headers": {"type": "object"},
                "body": {"type": "string"},
                "seconds": {"type": "integer", "description": "Timeout 2-60 s (default 15)"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "websocket",
        "description": "Open a WebSocket (user confirms), optionally send one message and collect replies for a short window.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "ws:// or wss://"},
                "message": {"type": "string"},
                "seconds": {"type": "integer", "description": "Listen window 1-30 s (default 5)"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "bt_info",
        "description": "Bluetooth adapter state: on/off, low-energy availability and whether the nearby-devices permission is granted.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "bt_devices",
        "description": "Devices this phone is already paired with: name, address, kind and bond state.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "bt_scan",
        "description": "Run a classic Bluetooth discovery window and list what answers (needs the nearby-devices permission).",
        "inputSchema": {
            "type": "object",
            "properties": {"seconds": {"type": "integer", "description": "Discovery window 5-20 s (default 12)"}},
        },
    },
    {
        "name": "ble_scan",
        "description": "Run a low-energy beacon window and list advertisers (unnamed beacons are normal).",
        "inputSchema": {
            "type": "object",
            "properties": {"seconds": {"type": "integer", "description": "Scan window 5-20 s (default 8)"}},
        },
    },
    {
        "name": "usb_install",
        "description": "Install a local .apk on the attached phone (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {"local_path": {"type": "string", "description": "Path of the .apk on this phone"}},
            "required": ["local_path"],
        },
    },
    {
        "name": "usb_logcat",
        "description": "Recent log lines from the attached phone (line-capped, user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {"lines": {"type": "integer", "description": "How many lines (20-500, default 200)"}},
        },
    },
    {
        "name": "usb_info",
        "description": "The attached phone's model, Android version, battery and storage.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "usb_serial_send",
        "description": "Send text to an Arduino/ESP32 board over a USB-serial adapter (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to send"},
                "baudrate": {"type": "integer", "description": "Baud rate (default 115200)"},
                "newline": {"type": "boolean", "description": "Append a newline (default true)"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "usb_serial_read",
        "description": "Collect what the serial board prints for a moment (default 1s at 115200).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "baudrate": {"type": "integer", "description": "Baud rate (default 115200)"},
                "milliseconds": {"type": "integer", "description": "How long to collect (100-10000)"},
            },
        },
    },
    {
        "name": "usb_tcpip_enable",
        "description": "Switch the attached phone's wireless debugging on and report its address (user confirms).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tcp_shell",
        "description": "Run a shell command over Wi-Fi on a phone whose wireless debugging is enabled (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string", "description": "The phone's IP address"},
                "port": {"type": "integer", "description": "adbd TCP port (default 5555)"},
                "command": {"type": "string", "description": "Shell command"},
            },
            "required": ["host", "command"],
        },
    },
    {
        "name": "ssh_exec",
        "description": "Run a command on the user's server over SSH (user confirms). Host keys are pinned on first use.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "username": {"type": "string"},
                "port": {"type": "integer", "description": "Default 22"},
                "password": {"type": "string", "description": "Password OR a private key path"},
                "private_key": {"type": "string", "description": "Path of an OpenSSH private key on this phone"},
                "command": {"type": "string"},
                "timeout_seconds": {"type": "integer", "description": "5-600, default 60"},
            },
            "required": ["host", "username", "command"],
        },
    },
    {
        "name": "ssh_list",
        "description": "List a directory on the user's server over SFTP.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "username": {"type": "string"},
                "port": {"type": "integer"},
                "password": {"type": "string"},
                "private_key": {"type": "string"},
                "path": {"type": "string", "description": "Default '.'"},
            },
            "required": ["host", "username"],
        },
    },
    {
        "name": "ssh_download",
        "description": "Copy a remote file to this phone's Download/Mushrea-ssh (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "username": {"type": "string"},
                "port": {"type": "integer"},
                "password": {"type": "string"},
                "private_key": {"type": "string"},
                "remote_path": {"type": "string"},
            },
            "required": ["host", "username", "remote_path"],
        },
    },
    {
        "name": "ssh_upload",
        "description": "Copy a local file to the server (user confirms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string"},
                "username": {"type": "string"},
                "port": {"type": "integer"},
                "password": {"type": "string"},
                "private_key": {"type": "string"},
                "local_path": {"type": "string"},
                "remote_dir": {"type": "string", "description": "Default '.'"},
            },
            "required": ["host", "username", "local_path"],
        },
    },
    {
        "name": "payload_info",
        "description": "Read an OTA payload.bin (or its zip) and list the partitions and compressions inside.",
        "inputSchema": {
            "type": "object",
            "properties": {"file_path": {"type": "string", "description": "Path of payload.bin or the OTA zip on this phone"}},
            "required": ["file_path"],
        },
    },
    {
        "name": "payload_extract",
        "description": "Reconstruct one partition image (boot, system, ...) from a payload.bin into Download/Mushrea-payload (analysis only, nothing is flashed).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string"},
                "partition": {"type": "string"},
            },
            "required": ["file_path", "partition"],
        },
    },
    {
        "name": "fastboot_getvar",
        "description": "Read-only identity of a phone in fastboot mode over USB (product, serial, bootloader version, unlocked state).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "usb_mode",
        "description": "List every attached USB device and classify its mode (fastboot / adb / mtp / serial / storage / other) with vendor and product ids.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "usb_diagnostics",
        "description": "One diagnostics view: USB devices and modes, plus the codename, slot, lock state and battery-soc-ok of an attached bootloader. Read-only.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "fastboot_getvar_full",
        "description": "Full fastboot identity including the unlock token. The token is masked unless reveal_token is true; it is never written to the activity log or stored off the phone. MTK oem commands are refused.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "reveal_token": {"type": "boolean", "description": "Show the token itself (for the official Mi unlock flow) instead of a mask."},
            },
        },
    },
    {
        "name": "payload_guard",
        "description": "Before flashing: check a ROM archive against the device codename (for example refuse flourite on sky), report its region and verify the container structure. Returns verdict match / mismatch / unverified plus a blocking flag.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "Path of the ROM zip / tgz / payload.bin on this phone"},
                "device_product": {"type": "string", "description": "Device codename; omit to read it from an attached bootloader"},
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "safety_preflight",
        "description": "The unlock/flash preflight: ROM-vs-device codename, archive integrity, target battery-soc-ok, host battery, free space, and the reminders software cannot verify (Mi 72h/168h wait, backup persist/nvram, unlock wipes userdata).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "Optional ROM archive to include in the check"},
                "device_product": {"type": "string", "description": "Optional codename override"},
            },
        },
    },
    {
        "name": "audit_export",
        "description": "Export the device activity log (action, result, summary, timestamp, safety switches) as a JSON document under the app's storage. No command parameters and no unlock token are included.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "termux_status",
        "description": "Is the Termux bridge usable? Reports whether Termux and Termux:API are installed, whether the RUN_COMMAND permission is granted, and exactly what is missing.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "termux_run",
        "description": "Run an allowlisted read/verify command in the user's Termux (for example getprop, uname, command -v, python3 --version, termux-usb -l, termux-fastboot getvar). Destructive fastboot subcommands and vendor (oem) commands are refused with a reason.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Command name, for example termux-fastboot or getprop"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Arguments, passed as separate argv entries"},
            },
            "required": ["command"],
        },
    },
    {
        "name": "termux_fastboot_run",
        "description": "Run termux-fastboot inside Termux (USB access comes from termux-usb; stock android-tools fastboot cannot see devices without root). Read/verify subcommands only — flash / erase / stage / lock / unlock are refused until the confirmed write phase.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "args": {"type": "array", "items": {"type": "string"}, "description": "For example [\"getvar\", \"product\"]"},
                "device_product": {"type": "string", "description": "Optional codename used to enforce the sky-never-flourite rule"},
            },
            "required": ["args"],
        },
    },
    {
        "name": "mitool_wrapper",
        "description": "The on-device Mi-tool wrapper (termux-miunlock): status of its prerequisites, install/update of its checkout, or its --help/--version output. It never fetches an unlock token and never runs stage/unlock on its own.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "step": {"type": "string", "enum": ["status", "install", "help"], "description": "Default status"},
            },
        },
    },
    {
        "name": "device_search_and_type",
        "description": "Find the search field (Arabic or English), tap it, type text and submit. Preferred for in-app search instead of tap+type chains.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "query": {"type": "string"},
                "clear": {"type": "boolean"},
                "submit": {"type": "boolean"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "device_scroll_until_found",
        "description": "Scroll down (max_swipes, default 8) until an element matching query appears; reports swipes used, optionally taps it.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "max_swipes": {"type": "integer"},
                "tap": {"type": "boolean"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "device_wait_for_element",
        "description": "Poll the screen until an element matching query appears (timeout_ms, default 5000); optionally taps it.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "timeout_ms": {"type": "integer"},
                "tap": {"type": "boolean"},
            },
            "required": ["query"],
        },
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
        "name": "device_get_context",
        "description": "Read the running Device Agent context: current app/activity, the current task (survives app switches), the last file the agent touched, and the recent app trail. Use it to resolve references like 'this' / 'open it' / 'send it' before acting.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_set_task",
        "description": "Record the current high-level goal (e.g. 'send this article to Ahmed on WhatsApp') so context survives app switches during multi-step tasks.",
        "inputSchema": {"type": "object", "properties": {"goal": {"type": "string"}}, "required": ["goal"]},
    },
    {
        "name": "device_stop",
        "description": "EMERGENCY STOP: ask the app to halt the current device task; no further device action starts after this.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "device_bridge_status",
        "description": "Check whether the on-device bridge is reachable (accessibility enabled) and report the current app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    # --- Peer ADB (a second phone over wireless debugging) -------------------------------------
    {
        "name": "peer_devices",
        "description": "The other phones paired with this one over wireless debugging: state, address, model, Android version.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "peer_capabilities",
        "description": "What a paired phone can actually do: its shell, toybox/cmd applets, interpreters, packages, exit codes.",
        "inputSchema": {
            "type": "object",
            "properties": {"serial": {"type": "string", "description": "The phone's serial, as peer_devices reports it"}},
            "required": ["serial"],
        },
    },
    {
        "name": "peer_plan",
        "description": "Plan a goal on a peer phone: give a recipe (or a goal in words) and get the steps that phone can actually run, the capability each one uses and the capability that is missing. The recipe catalogue covers device info, packages, apps, files, screen, input, UI dump, logs, settings, services and scripts.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "serial": {"type": "string", "description": "The phone's serial"},
                "recipe": {
                    "type": "string",
                    "description": 'What to achieve, e.g. "packages.list", "screen.capture", "script.run", "files.list"',
                },
                "goal": {"type": "string", "description": "The same thing in words, when no recipe id is known"},
                "parameters": {
                    "type": "object",
                    "description": 'Recipe parameters, e.g. {"path": "/sdcard"} or {"script": "echo hi", "interpreter": "sh"}',
                },
                "operations": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": 'Raw primitives instead of a recipe, e.g. ["SHELL", "PULL", "INSTALL"]',
                },
            },
            "required": ["serial"],
        },
    },
    {
        "name": "peer_pair_qr",
        "description": "Start pairing a second phone: returns the QR payload the user scans from that phone's wireless-debugging screen (asks the user to confirm).",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "peer_pair_code",
        "description": "Pair a second phone with the six-digit code and pairing port its wireless-debugging screen shows (asks the user to confirm).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string", "description": "The other phone's IP address"},
                "port": {"type": "integer", "description": "The pairing port shown next to the code"},
                "code": {"type": "string", "description": "The six-digit pairing code"},
            },
            "required": ["host", "port", "code"],
        },
    },
    {
        "name": "peer_connect",
        "description": "Open the ADB channel to a paired phone; its current port is rediscovered automatically (asks the user to confirm).",
        "inputSchema": {
            "type": "object",
            "properties": {"serial": {"type": "string", "description": "The phone's serial, as peer_devices reports it"}},
            "required": ["serial"],
        },
    },
    {
        "name": "peer_disconnect",
        "description": "Close the ADB channel to a peer phone; the pairing is kept, so no new QR is needed.",
        "inputSchema": {
            "type": "object",
            "properties": {"serial": {"type": "string", "description": "The phone's serial"}},
            "required": ["serial"],
        },
    },
    {
        "name": "peer_provision",
        "description": (
            "Set a peer phone up for remote work in one call: find a route to it, pair with the six-digit code "
            "the user read off its screen (when one is supplied), connect, verify with a real command, measure "
            "its capabilities, keep the arrangement alive and prove execution end to end. Returns per-step "
            "status (completed/skipped/requires_user_action/unsupported/failed) and a readiness level; a step "
            "only the phone's owner can take is reported as requires_user_action with the exact instruction."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "serial": {"type": "string", "description": "The device serial (see peer_devices)."},
                "code": {"type": "string", "description": "The six-digit pairing code shown next to Wireless debugging."},
                "hints": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Addresses to try, as host:port (the pairing address from the phone's screen).",
                },
                "purpose": {"type": "string", "enum": ["REMOTE_CONTROL", "FILE_TRANSFER", "DIAGNOSTICS", "TEST"]},
                "persistence": {"type": "boolean", "description": "Also try to make it survive a reboot and a new port."},
                "allow_public": {"type": "boolean", "description": "Allow routes on public addresses (refused by default)."},
                "attempts": {"type": "integer", "description": "How many times to walk the plan after a change (1-5)."},
            },
            "required": ["serial"],
        },
    },
    {
        "name": "peer_reconnect",
        "description": (
            "Get a known peer phone back after it slept, moved networks or changed its port: tries remembered "
            "addresses first, then discovery, waiting longer each attempt and stopping. Answers with the rung it "
            "reached (ready means a real command came back, not just a socket)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "serial": {"type": "string", "description": "The device serial (see peer_devices)."},
                "attempts": {"type": "integer", "description": "How many attempts to make (1-12, default 4)."},
                "initial_delay_ms": {"type": "integer", "description": "The first wait between attempts, in ms (default 2000)."},
                "max_delay_ms": {"type": "integer", "description": "The cap on the wait, in ms (default 60000)."},
                "endpoints": {"type": "array", "items": {"type": "string"}, "description": "Extra host:port routes to try."},
            },
            "required": ["serial"],
        },
    },
    {
        "name": "peer_endpoints",
        "description": (
            "How a peer phone could be reached right now: every candidate route with the evidence behind it "
            "(handed over, announced, remembered, named), its network scope, and this phone's own networking. "
            "Read-only - nothing is connected."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"serial": {"type": "string", "description": "The device serial (see peer_devices)."}},
            "required": ["serial"],
        },
    },
    {
        "name": "peer_execute",
        "description": "Run anything on a peer phone: a recipe (or a goal in words), or one raw operation - a shell line, a program with arguments, a script, a file push/pull or an APK install - then verify it (asks the user to confirm). Prefer a recipe or a goal: the platform plans it against what the phone reported it can do and falls back with a recorded reason.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "serial": {"type": "string", "description": "The phone's serial - there is no default phone"},
                "recipe": {
                    "type": "string",
                    "description": 'What to achieve, e.g. "packages.list", "screen.capture", "script.run", "files.push"',
                },
                "goal": {"type": "string", "description": "The same thing in words, when no recipe id is known"},
                "parameters": {
                    "type": "object",
                    "description": 'Recipe parameters, e.g. {"package": "com.android.settings"} or {"script": "echo hi", "interpreter": "python3"}',
                },
                "operation": {
                    "type": "string",
                    "enum": ["SHELL", "EXEC", "SCRIPT", "PUSH", "PULL", "INSTALL", "PROBE"],
                    "description": "How the work runs: a shell line, one program with an argv, a script, a file copy in either direction, an APK install, or a probe",
                },
                "command": {"type": "string", "description": "The shell line, program path or script body (not needed for push/pull/install)"},
                "arguments": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Arguments for EXEC, passed as an argv without shell interpretation",
                },
                "interpreter": {"type": "string", "description": "Interpreter for SCRIPT, e.g. sh or python3 (default sh)"},
                "files": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "local_path": {"type": "string", "description": "Path on this phone"},
                            "remote_path": {"type": "string", "description": "Path on the other phone"},
                        },
                    },
                    "description": "Files for PUSH (local to remote), PULL (remote to local) or INSTALL (the APK's local path)",
                },
                "timeout_seconds": {"type": "integer", "description": "How long the command may take on the other phone (default 30)"},
                "verify_command": {
                    "type": "string",
                    "description": "A read-only follow-up command that proves the effect; the result is reported as verified only when it exits 0",
                },
                "reason": {"type": "string", "description": "Why this runs, recorded in the execution log"},
            },
            "required": ["serial"],
        },
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
    "device_search_and_type": tool_search_and_type,
    "device_find_contact": tool_find_contact,
    "device_call_agent": tool_call_agent,
    "device_call_state": tool_call_state,
    "device_call_stop": tool_call_stop,
    "call_record_start": tool_call_record_start,
    "call_record_stop": tool_call_record_stop,
    "device_call_log": tool_call_log,
    "device_status": tool_device_status,
    "device_call_summaries": tool_call_summaries,
    "usb_devices": tool_usb_devices,
    "peer_devices": tool_peer_devices,
    "peer_capabilities": tool_peer_capabilities,
    "peer_plan": tool_peer_plan,
    "peer_pair_qr": tool_peer_pair_qr,
    "peer_pair_code": tool_peer_pair_code,
    "peer_connect": tool_peer_connect,
    "peer_disconnect": tool_peer_disconnect,
    "peer_execute": tool_peer_execute,
    "peer_provision": tool_peer_provision,
    "peer_reconnect": tool_peer_reconnect,
    "peer_endpoints": tool_peer_endpoints,
    "usb_shell": tool_usb_shell,
    "usb_list": tool_usb_list,
    "usb_pull": tool_usb_pull,
    "usb_push": tool_usb_push,
    "usb_transfer_media": tool_usb_transfer_media,
    "usb_screenshot": tool_usb_screenshot,
    "mirror_start": tool_mirror_start,
    "mirror_stop": tool_mirror_stop,
    "scrcpy_start": tool_scrcpy_start,
    "scrcpy_stop": tool_scrcpy_stop,
    "usb_hub_list": tool_usb_hub_list,
    "mtp_list": tool_mtp_list,
    "mtp_download": tool_mtp_download,
    "mtp_upload": tool_mtp_upload,
    "hid_read": tool_hid_read,
    "storage_volumes": tool_storage_volumes,
    "camera_list": tool_camera_list,
    "camera_capture": tool_camera_capture,
    "net_browse": tool_net_browse,
    "remote_list": tool_remote_list,
    "remote_download": tool_remote_download,
    "wifi_info": tool_wifi_info,
    "dns_lookup": tool_dns_lookup,
    "net_ping": tool_net_ping,
    "port_check": tool_port_check,
    "http_request": tool_http_request,
    "websocket": tool_websocket,
    "bt_info": tool_bt_info,
    "bt_devices": tool_bt_devices,
    "bt_scan": tool_bt_scan,
    "ble_scan": tool_ble_scan,
    "usb_install": tool_usb_install,
    "usb_logcat": tool_usb_logcat,
    "usb_info": tool_usb_info,
    "usb_serial_send": tool_usb_serial_send,
    "usb_serial_read": tool_usb_serial_read,
    "usb_tcpip_enable": tool_usb_tcpip_enable,
    "tcp_shell": tool_tcp_shell,
    "ssh_exec": tool_ssh_exec,
    "ssh_list": tool_ssh_list,
    "ssh_download": tool_ssh_download,
    "ssh_upload": tool_ssh_upload,
    "payload_info": tool_payload_info,
    "payload_extract": tool_payload_extract,
    "fastboot_getvar": tool_fastboot_getvar,
    "usb_mode": tool_usb_mode,
    "usb_diagnostics": tool_usb_diagnostics,
    "fastboot_getvar_full": tool_fastboot_getvar_full,
    "payload_guard": tool_payload_guard,
    "safety_preflight": tool_safety_preflight,
    "audit_export": tool_audit_export,
    "termux_status": tool_termux_status,
    "termux_run": tool_termux_run,
    "termux_fastboot_run": tool_termux_fastboot_run,
    "mitool_wrapper": tool_mitool_wrapper,
    "device_scroll_until_found": tool_scroll_until_found,
    "device_wait_for_element": tool_wait_for_element,
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
    "device_get_context": tool_get_context,
    "device_set_task": tool_set_task,
    "device_bridge_status": tool_status,
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
