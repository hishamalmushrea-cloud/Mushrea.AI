package com.mushrea.code.device.tool

import com.mushrea.code.device.ConfirmationLevel
import com.mushrea.code.device.DeviceActionFirewall

/** What a tool acts on, so a screen or a policy can group the device surface without a second list. */
enum class ToolFamily {
    SCREEN,
    FILES,
    CALL,
    USB,
    SERIAL,
    HUB,
    MTP,
    MIRROR,
    SSH,
    REMOTE,
    NETWORK,
    BT,
    PAYLOAD,
    TERMUX,
    SAFETY,
    AUDIT,
    STATUS,
    CONTEXT,
}

/**
 * How much damage a tool can do if it runs when it should not.
 *
 * The level is about the *impact*, not about whether the user is asked: a read-only probe and a
 * shell command can both be `AUTO`, but they are not equally dangerous.
 */
enum class ToolRisk {
    /** Reads state on this phone; nothing outside it changes. */
    LOW,

    /** Changes reversible state, or reads something outside this phone. */
    MEDIUM,

    /** Irreversible, or acts on another device or an account: deletes, installs, shells, uploads. */
    HIGH,
}

/**
 * Whether the app records the tool in its audit trail, and how much of it.
 *
 * Every tool is `SUMMARY` today: the bridge logs the action, the outcome and the executor's summary
 * line for each command (`DeviceAgentBridge.log`), while the raw payloads are not kept. The other
 * values exist so a future policy can raise the level where evidence matters without a second enum.
 */
enum class AuditPolicy {
    NONE,
    SUMMARY,
    FULL,
}

/** How a tool reaches the app. */
enum class ToolTransport {
    /** The bridge command channel: `.mushrea-code/device-command.json` in, result file out. */
    BRIDGE,

    /** The tool reads a file the app already publishes in the workspace; no command round-trip. */
    WORKSPACE_FILE,
}

/**
 * A condition that must hold before a tool can work.
 *
 * These are declarations, not checks: each one names the mechanism that answers it, and the Device
 * Agent readiness screen (`DeviceReadiness`) is what actually evaluates the ones it can. They exist
 * so a policy or a screen can say "this tool needs X" without re-deriving it from the executor.
 */
enum class ToolRequirement {
    /** Mushrea Code is enabled in Accessibility settings (`MushreaCodeAccessibilityService.isRunning`). */
    ACCESSIBILITY,

    /** File access on this phone (`DeviceStorageAccess`: MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE). */
    DEVICE_STORAGE,

    /** CALL_PHONE + READ_CONTACTS + READ_PHONE_STATE (`PhoneCallController.permissions`). */
    CALL_PERMISSIONS,

    /** The app is the default dialer, which the call agent needs (`PhoneCallController.isDefaultDialer`). */
    DEFAULT_DIALER,

    /** An Android phone answering ADB over USB with permission granted (`UsbDeviceAgent.ensurePermission`). */
    USB_ADB_DEVICE,

    /** A USB-serial adapter is attached and permitted (`UsbSerialAgent`). */
    USB_SERIAL_DEVICE,

    /** Any USB device is attached and permitted (`UsbDeviceAgent`). */
    USB_ANY_DEVICE,

    /** A phone switched to MTP/File-Transfer mode is attached (`MtpAgent`). */
    MTP_DEVICE,

    /** ACCESS_WIFI_STATE, for the Wi-Fi state of this phone. */
    WIFI_STATE,

    /** INTERNET, for anything that leaves this phone. */
    NETWORK,

    /** BLUETOOTH_CONNECT / BLUETOOTH_SCAN and Bluetooth switched on (`BluetoothExecutor`). */
    BT_NEARBY,

    /** A host, user and credential supplied in the call (`SshExecutor.credentials`). */
    SSH_TARGET,

    /** A protocol host and credential supplied in the call (`RemoteExecutor`). */
    REMOTE_TARGET,

    /** An OTA payload/ROM file path supplied in the call (`PayloadExecutor`). */
    PAYLOAD_FILE,

    /** Termux installed with `allow-external-apps` (`TermuxBridge.status`). */
    TERMUX_BRIDGE,

    /** `termux-fastboot` available inside Termux, for the bootloader tools. */
    TERMUX_FASTBOOT,

    /** A ROM archive, a device codename or an attached device to compare them (`DeviceSafetyPreflight`). */
    SAFETY_TARGET,

    /** An activity log to export (`DeviceAgentStore` activity + `DeviceAuditLog`). */
    AUDIT_LOG,

    /** The workspace command channel the agent and the app share (`DeviceAgentBridge.COMMAND_DIR_NAME`). */
    WORKSPACE_CHANNEL,
}

/**
 * One device tool, described once.
 *
 * The same device action used to be defined in four places with no link between them: the MCP tool
 * table in `assets/scripts/mushreacode-device-mcp.py` (name, description, input schema, wait), the
 * firewall's action sets, the bridge's dispatch, and the agent-context document. This catalog is the
 * app-side truth; `scripts/check_tool_catalog.py` fails the build when the script, the firewall or
 * the bridge stops agreeing with it, and Kotlin tests prove the classification it declares.
 *
 * [timeoutMillis] is how long the agent's tool call waits for the app, taken from the shipped
 * script; a [ToolTransport.WORKSPACE_FILE] tool has no round-trip and reports 0.
 *
 * [id] is the action the bridge receives, or a stable name for a tool that has no action. [mcpTools]
 * is what the agent sees, and it can be empty (a bridge-only probe) or hold several names for one
 * action (`get_current_app` is reachable through both `device_current_app` and `device_bridge_status`).
 */
data class DeviceTool(
    val id: String,
    val mcpTools: List<String>,
    val purpose: String,
    val family: ToolFamily,
    val risk: ToolRisk,
    val confirmation: ConfirmationLevel,
    val timeoutMillis: Long,
    val requires: Set<ToolRequirement>,
    val requiredParams: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val configurable: Boolean = false,
    val transport: ToolTransport = ToolTransport.BRIDGE,
)
/**
 * The device tool surface, one entry per action the bridge accepts.
 *
 * Kept in one flat list on purpose: it is meant to be read top to bottom, and the derived views
 * below ([actions], [autoActions], [readOnlyActions], [confirmActions]) are what the firewall uses,
 * so the firewall no longer keeps its own hand-written sets that could drift from this table.
 *
 * Ordering note: the list is alphabetical by [DeviceTool.id] to make a missing or duplicated entry
 * obvious in review. The Device Agent screen's customization list keeps its own curated order
 * (`DeviceActionFirewall.CONFIGURABLE_ACTIONS`), which a test checks against this table.
 */
object DeviceToolCatalog {
    private fun tool(
        id: String,
        mcpTools: List<String>,
        purpose: String,
        family: ToolFamily,
        risk: ToolRisk,
        confirmation: ConfirmationLevel,
        timeoutMillis: Long,
        requires: Set<ToolRequirement>,
        requiredParams: List<String> = emptyList(),
        readOnly: Boolean = false,
        configurable: Boolean = false,
        transport: ToolTransport = ToolTransport.BRIDGE,
    ) = DeviceTool(
        id = id,
        mcpTools = mcpTools,
        purpose = purpose,
        family = family,
        risk = risk,
        confirmation = confirmation,
        timeoutMillis = timeoutMillis,
        requires = requires,
        requiredParams = requiredParams,
        readOnly = readOnly,
        configurable = configurable,
        transport = transport,
    )

    /** Every tool, sorted by id. */
    val all: List<DeviceTool> =
        listOf(
    tool(
        id = DeviceActionFirewall.ACTION_AUDIT_EXPORT,
        mcpTools = listOf("audit_export"),
        purpose = "Export the device activity log as a JSON document.",
        family = ToolFamily.AUDIT,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.AUDIT_LOG, ToolRequirement.DEVICE_STORAGE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_BLE_SCAN,
        mcpTools = listOf("ble_scan"),
        purpose = "Run a low-energy beacon window and list advertisers (unnamed beacons are normal).",
        family = ToolFamily.BT,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.BT_NEARBY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_BT_DEVICES,
        mcpTools = listOf("bt_devices"),
        purpose = "Devices this phone is already paired with: name, address, kind and bond state.",
        family = ToolFamily.BT,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.BT_NEARBY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_BT_INFO,
        mcpTools = listOf("bt_info"),
        purpose = "Bluetooth adapter state: on/off, low-energy availability and whether the nearby-devices permission is granted.",
        family = ToolFamily.BT,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.BT_NEARBY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_BT_SCAN,
        mcpTools = listOf("bt_scan"),
        purpose = "Run a classic Bluetooth discovery window and list what answers (needs the nearby-devices permission).",
        family = ToolFamily.BT,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.BT_NEARBY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CALL_AGENT,
        mcpTools = listOf("device_call_agent"),
        purpose = "Start the voice call agent with a natural-language command.",
        family = ToolFamily.CALL,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("command"),
        requires = setOf(ToolRequirement.CALL_PERMISSIONS, ToolRequirement.DEFAULT_DIALER),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CALL_STATE,
        mcpTools = listOf("device_call_state"),
        purpose = "Report the call agent's live state: call state, goals answered so far, last statements.",
        family = ToolFamily.CALL,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.CALL_PERMISSIONS),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CALL_STOP,
        mcpTools = listOf("device_call_stop"),
        purpose = "Stop the call agent immediately (it halts before its next turn).",
        family = ToolFamily.CALL,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.CALL_PERMISSIONS),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CALL_SUMMARIES,
        mcpTools = listOf("device_call_summaries"),
        purpose = "Stored call-agent summaries: purpose, answers, caller facts.",
        family = ToolFamily.CALL,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.CALL_PERMISSIONS),
        readOnly = true,
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CAMERA_LIST,
        mcpTools = listOf("camera_list"),
        purpose = "All cameras Android exposes, flagging externally attached USB cameras (platform-dependent).",
        family = ToolFamily.HUB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_CLEAR_TEXT,
        mcpTools = listOf("device_clear_text"),
        purpose = "Clear the focused input field.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_COPY_FILE,
        mcpTools = listOf("device_copy_file"),
        purpose = "Copy a file into directory `to` (confirmation required by default).",
        family = ToolFamily.FILES,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("to"),
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_DELETE_FILE,
        mcpTools = listOf("device_delete_file"),
        purpose = "Delete a file (confirmation required by default).",
        family = ToolFamily.FILES,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_DEVICE_STATUS,
        mcpTools = listOf("device_status"),
        purpose = "Battery, charging, network, ringer and screen-lock state of this phone.",
        family = ToolFamily.STATUS,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_DNS_LOOKUP,
        mcpTools = listOf("dns_lookup"),
        purpose = "Resolve a hostname into its IPv4/IPv6 addresses.",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requiredParams = listOf("host"),
        requires = setOf(ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_FASTBOOT_GETVAR,
        mcpTools = listOf("fastboot_getvar"),
        purpose = "Read-only identity of a phone in fastboot mode over USB (product, serial, bootloader version, unlocked state).",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_FASTBOOT_GETVAR_FULL,
        mcpTools = listOf("fastboot_getvar_full"),
        purpose = "Full fastboot identity including the unlock token.",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_FIND_CONTACT,
        mcpTools = listOf("device_find_contact"),
        purpose = "Look up a contact by name and get their phone number.",
        family = ToolFamily.CALL,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("query"),
        requires = setOf(ToolRequirement.CALL_PERMISSIONS),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_FIND_ELEMENT,
        mcpTools = listOf("device_find_element"),
        purpose = "Find UI elements on the current screen and get their element indexes.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requiredParams = listOf("query"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_GET_CURRENT_APP,
        mcpTools = listOf("device_bridge_status", "device_current_app"),
        purpose = "Check whether the on-device bridge is reachable (accessibility enabled) and report the current app.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 15_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_HID_READ,
        mcpTools = listOf("hid_read"),
        purpose = "Capture raw HID input reports (hex) from an attached USB keyboard, mouse or sensor.",
        family = ToolFamily.HUB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_HTTP_REQUEST,
        mcpTools = listOf("http_request"),
        purpose = "Send an HTTP/HTTPS request (user confirms).",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("url"),
        requires = setOf(ToolRequirement.NETWORK),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_LIST_APPS,
        mcpTools = listOf("device_list_apps"),
        purpose = "List installed launchable apps (label + package).",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_LONG_PRESS,
        mcpTools = listOf("device_long_press"),
        purpose = "Long-press an element by query or index.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MIRROR_START,
        mcpTools = listOf("mirror_start"),
        purpose = "Show the attached phone's live screen inside this app, view-only (user confirms).",
        family = ToolFamily.MIRROR,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MIRROR_STOP,
        mcpTools = listOf("mirror_stop"),
        purpose = "Stop the live screen mirror if one is running.",
        family = ToolFamily.MIRROR,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MITOOL_WRAPPER,
        mcpTools = listOf("mitool_wrapper"),
        purpose = "Run the on-device Mi-tool wrapper (termux-miunlock) and report its status.",
        family = ToolFamily.TERMUX,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 300_000L,
        requires = setOf(ToolRequirement.TERMUX_BRIDGE, ToolRequirement.TERMUX_FASTBOOT),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MOVE_FILE,
        mcpTools = listOf("device_move_file"),
        purpose = "Move a file into directory `to` (confirmation required by default).",
        family = ToolFamily.FILES,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("to"),
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MTP_DOWNLOAD,
        mcpTools = listOf("mtp_download"),
        purpose = "Copy one file from an MTP/PTP device into this phone's Download folder.",
        family = ToolFamily.MTP,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 1_800_000L,
        requiredParams = listOf("handle"),
        requires = setOf(ToolRequirement.MTP_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_MTP_LIST,
        mcpTools = listOf("mtp_list"),
        purpose = "List the storage volumes of a phone in MTP/File-Transfer mode, or one folder inside it.",
        family = ToolFamily.MTP,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.MTP_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_NET_BROWSE,
        mcpTools = listOf("net_browse"),
        purpose = "Browse the local network for services that advertise themselves (mDNS/SSDP).",
        family = ToolFamily.REMOTE,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_NET_PING,
        mcpTools = listOf("net_ping"),
        purpose = "ICMP ping a user-named host (authorized diagnostics only): sent/received/loss and round-trip stats.",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 120_000L,
        requiredParams = listOf("host"),
        requires = setOf(ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_OPEN_APP,
        mcpTools = listOf("device_open_app"),
        purpose = "Open an installed app by name (\"youtube\", \"يوتيوب\") or package name.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requiredParams = listOf("app"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_OPEN_FILE,
        mcpTools = listOf("device_open_file"),
        purpose = "Open a file on the device (by path, or by name which is searched).",
        family = ToolFamily.FILES,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_OPEN_RECENTS,
        mcpTools = listOf("device_press"),
        purpose = "Open the recents screen.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requiredParams = listOf("which"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_OPEN_URL,
        mcpTools = listOf("device_open_url"),
        purpose = "Open an http/https URL in the default browser/app.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requiredParams = listOf("url"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PAYLOAD_EXTRACT,
        mcpTools = listOf("payload_extract"),
        purpose = "Reconstruct one partition image (boot, system, …) from a payload.bin.",
        family = ToolFamily.PAYLOAD,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 1_800_000L,
        requiredParams = listOf("file_path", "partition"),
        requires = setOf(ToolRequirement.PAYLOAD_FILE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PAYLOAD_GUARD,
        mcpTools = listOf("payload_guard"),
        purpose = "Check a ROM archive against the device codename and unlock policy before flashing.",
        family = ToolFamily.PAYLOAD,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("file_path"),
        requires = setOf(ToolRequirement.PAYLOAD_FILE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PAYLOAD_INFO,
        mcpTools = listOf("payload_info"),
        purpose = "Read an OTA payload.bin (or its zip) and list the partitions inside.",
        family = ToolFamily.PAYLOAD,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requiredParams = listOf("file_path"),
        requires = setOf(ToolRequirement.PAYLOAD_FILE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PING,
        mcpTools = emptyList(),
        purpose = "Prove the app side answers on the workspace channel (readiness probe).",
        family = ToolFamily.STATUS,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PORT_CHECK,
        mcpTools = listOf("port_check"),
        purpose = "One TCP connect against a user-named host:port (authorized diagnostics only): open/closed and latency.",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requiredParams = listOf("host", "port"),
        requires = setOf(ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PRESS_BACK,
        mcpTools = listOf("device_press"),
        purpose = "Press Back.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requiredParams = listOf("which"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_PRESS_HOME,
        mcpTools = listOf("device_press"),
        purpose = "Press Home.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requiredParams = listOf("which"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_READ_CALL_LOG,
        mcpTools = listOf("device_call_log"),
        purpose = "The most recent calls (missed included): number, contact name, time, duration.",
        family = ToolFamily.CALL,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.CALL_PERMISSIONS),
        readOnly = true,
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_READ_SCREEN,
        mcpTools = listOf("device_read_screen"),
        purpose = "Read the current screen: app, activity, numbered visible elements.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 45_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_REMOTE_DOWNLOAD,
        mcpTools = listOf("remote_download"),
        purpose = "Copy one file from a remote machine (smb, ftp, webdav or scp).",
        family = ToolFamily.REMOTE,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 1_800_000L,
        requiredParams = listOf("protocol", "host", "path"),
        requires = setOf(ToolRequirement.REMOTE_TARGET, ToolRequirement.NETWORK),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_REMOTE_LIST,
        mcpTools = listOf("remote_list"),
        purpose = "List a folder on a machine the user has credentials for.",
        family = ToolFamily.REMOTE,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 120_000L,
        requiredParams = listOf("protocol", "host"),
        requires = setOf(ToolRequirement.REMOTE_TARGET, ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_RENAME_FILE,
        mcpTools = listOf("device_rename_file"),
        purpose = "Rename a file to new_name (confirmation required by default).",
        family = ToolFamily.FILES,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("new_name"),
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SAFETY_PREFLIGHT,
        mcpTools = listOf("safety_preflight"),
        purpose = "ROM-vs-device codename, archive, battery and space preflight before unlock/flash.",
        family = ToolFamily.SAFETY,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.SAFETY_TARGET),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SCRCPY_START,
        mcpTools = listOf("scrcpy_start"),
        purpose = "Start the scrcpy session: the attached phone's screen with real control.",
        family = ToolFamily.MIRROR,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SCRCPY_STOP,
        mcpTools = listOf("scrcpy_stop"),
        purpose = "Stop the scrcpy session if one is running.",
        family = ToolFamily.MIRROR,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SCROLL,
        mcpTools = listOf("device_scroll"),
        purpose = "Scroll the current screen: direction up/down/left/right.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("direction"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SCROLL_UNTIL_FOUND,
        mcpTools = listOf("device_scroll_until_found"),
        purpose = "Scroll down (max_swipes, default 8) until an element matching query appears; reports swipes used, optionally.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("query"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SEARCH_AND_TYPE,
        mcpTools = listOf("device_search_and_type"),
        purpose = "Find the search field (Arabic or English), tap it, type text and submit.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("text"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SEARCH_FILES,
        mcpTools = listOf("device_search_files"),
        purpose = "Search device storage files by name query and/or extension (e.g.",
        family = ToolFamily.FILES,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SET_TASK,
        mcpTools = listOf("device_set_task"),
        purpose = "Record the current high-level goal (e.g.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 15_000L,
        requiredParams = listOf("goal"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SHARE_FILE,
        mcpTools = listOf("device_share_file"),
        purpose = "Open the Android share sheet for a file (confirmation required by default).",
        family = ToolFamily.FILES,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.DEVICE_STORAGE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SSH_DOWNLOAD,
        mcpTools = listOf("ssh_download"),
        purpose = "Copy a remote file to this phone's Download folder (SFTP).",
        family = ToolFamily.SSH,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 360_000L,
        requiredParams = listOf("host", "username", "remote_path"),
        requires = setOf(ToolRequirement.SSH_TARGET, ToolRequirement.NETWORK),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SSH_EXEC,
        mcpTools = listOf("ssh_exec"),
        purpose = "Run a command on the user's server over SSH.",
        family = ToolFamily.SSH,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("host", "username", "command"),
        requires = setOf(ToolRequirement.SSH_TARGET, ToolRequirement.NETWORK),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SSH_LIST,
        mcpTools = listOf("ssh_list"),
        purpose = "List a directory on the user's server over SFTP.",
        family = ToolFamily.SSH,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requiredParams = listOf("host", "username"),
        requires = setOf(ToolRequirement.SSH_TARGET, ToolRequirement.NETWORK),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SSH_UPLOAD,
        mcpTools = listOf("ssh_upload"),
        purpose = "Copy a local file to the server (SFTP).",
        family = ToolFamily.SSH,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 360_000L,
        requiredParams = listOf("host", "username", "local_path"),
        requires = setOf(ToolRequirement.SSH_TARGET, ToolRequirement.NETWORK),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_STOP,
        mcpTools = listOf("device_stop"),
        purpose = "EMERGENCY STOP: ask the app to halt the current device task; no further device action starts after this.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 15_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_STORAGE_VOLUMES,
        mcpTools = listOf("storage_volumes"),
        purpose = "The storage volumes Android sees - built-in and removable USB drives - with mount states.",
        family = ToolFamily.HUB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_SWIPE,
        mcpTools = listOf("device_swipe"),
        purpose = "Swipe between two screen points: from{x,y} to{x,y}, optional durationMs.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("from", "to"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TAP,
        mcpTools = listOf("device_tap"),
        purpose = "Tap a screen element by query or index (semantic, preferred), or by x/y coordinates (fallback).",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TCP_SHELL,
        mcpTools = listOf("tcp_shell"),
        purpose = "Run a shell command over Wi-Fi on a phone with wireless debugging on.",
        family = ToolFamily.USB,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("host", "command"),
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TERMUX_FASTBOOT_RUN,
        mcpTools = listOf("termux_fastboot_run"),
        purpose = "Run termux-fastboot inside Termux (USB access comes from termux-usb).",
        family = ToolFamily.TERMUX,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 180_000L,
        requiredParams = listOf("args"),
        requires = setOf(ToolRequirement.TERMUX_BRIDGE, ToolRequirement.TERMUX_FASTBOOT),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TERMUX_RUN,
        mcpTools = listOf("termux_run"),
        purpose = "Run an allowlisted read/verify command in the user's Termux.",
        family = ToolFamily.TERMUX,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("command"),
        requires = setOf(ToolRequirement.TERMUX_BRIDGE),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TERMUX_STATUS,
        mcpTools = listOf("termux_status"),
        purpose = "Report whether the Termux bridge is usable and what is missing.",
        family = ToolFamily.TERMUX,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.TERMUX_BRIDGE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_TYPE_TEXT,
        mcpTools = listOf("device_type_text"),
        purpose = "Type text into the focused input field (append=true keeps existing text).",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("text"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_DEVICES,
        mcpTools = listOf("usb_devices"),
        purpose = "Android phones attached over USB that speak ADB (OTG cable required).",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        readOnly = true,
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_DIAGNOSTICS,
        mcpTools = listOf("usb_diagnostics"),
        purpose = "One diagnostics view: USB devices and modes, plus the codename and lock state.",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_HUB_LIST,
        mcpTools = listOf("usb_hub_list"),
        purpose = "Every attached USB device classified by kind (adb, fastboot, serial, mtp, hid, storage).",
        family = ToolFamily.HUB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_INFO,
        mcpTools = listOf("usb_info"),
        purpose = "The attached phone's model, Android version, battery and storage.",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 60_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_INSTALL,
        mcpTools = listOf("usb_install"),
        purpose = "Install a local .apk on the attached phone.",
        family = ToolFamily.USB,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 600_000L,
        requiredParams = listOf("local_path"),
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_LIST,
        mcpTools = listOf("usb_list"),
        purpose = "Browse a directory on the attached phone: names, sizes, folders.",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_LOGCAT,
        mcpTools = listOf("usb_logcat"),
        purpose = "Recent log lines from the attached phone (line-capped).",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_MODE,
        mcpTools = listOf("usb_mode"),
        purpose = "List every attached USB device and classify its mode (fastboot, adb, mtp, serial, …).",
        family = ToolFamily.USB,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_ANY_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_PULL,
        mcpTools = listOf("usb_pull"),
        purpose = "Copy a file or a whole folder from the attached phone into this phone.",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 600_000L,
        requiredParams = listOf("remote_path"),
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_PUSH,
        mcpTools = listOf("usb_push"),
        purpose = "Copy one local file to the attached phone's Download folder.",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 600_000L,
        requiredParams = listOf("local_path"),
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_SCREENSHOT,
        mcpTools = listOf("usb_screenshot"),
        purpose = "Capture the attached phone's screen as a PNG into this phone's Downloads.",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_SERIAL_READ,
        mcpTools = listOf("usb_serial_read"),
        purpose = "Collect what the serial board prints for a moment.",
        family = ToolFamily.SERIAL,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.USB_SERIAL_DEVICE),
        readOnly = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_SERIAL_SEND,
        mcpTools = listOf("usb_serial_send"),
        purpose = "Send text to an Arduino/ESP32 board over a USB-serial adapter (user confirms).",
        family = ToolFamily.SERIAL,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("text"),
        requires = setOf(ToolRequirement.USB_SERIAL_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_SHELL,
        mcpTools = listOf("usb_shell"),
        purpose = "Run a shell command on the attached Android phone over USB.",
        family = ToolFamily.USB,
        risk = ToolRisk.HIGH,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 180_000L,
        requiredParams = listOf("command"),
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_TCPIP,
        mcpTools = listOf("usb_tcpip_enable"),
        purpose = "Switch the attached phone's wireless debugging on and report its address (user confirms).",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_USB_TRANSFER_MEDIA,
        mcpTools = listOf("usb_transfer_media"),
        purpose = "Bulk-copy photos and videos from the other phone's DCIM and Pictures.",
        family = ToolFamily.USB,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 1_800_000L,
        requires = setOf(ToolRequirement.USB_ADB_DEVICE),
        configurable = true,
    ),
    tool(
        id = DeviceActionFirewall.ACTION_WAIT_FOR_ELEMENT,
        mcpTools = listOf("device_wait_for_element"),
        purpose = "Poll the screen until an element matching query appears (timeout_ms, default 5000); optionally taps it.",
        family = ToolFamily.SCREEN,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 150_000L,
        requiredParams = listOf("query"),
        requires = setOf(ToolRequirement.ACCESSIBILITY),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_WEBSOCKET,
        mcpTools = listOf("websocket"),
        purpose = "Open a WebSocket (user confirms), optionally send one message and collect replies for a short window.",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.MEDIUM,
        confirmation = ConfirmationLevel.CONFIRM,
        timeoutMillis = 150_000L,
        requiredParams = listOf("url"),
        requires = setOf(ToolRequirement.NETWORK),
    ),
    tool(
        id = DeviceActionFirewall.ACTION_WIFI_INFO,
        mcpTools = listOf("wifi_info"),
        purpose = "Wi-Fi state on this phone: enabled, ssid (hidden by Android without location permission), ip, gateway, signal.",
        family = ToolFamily.NETWORK,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 30_000L,
        requires = setOf(ToolRequirement.WIFI_STATE),
        readOnly = true,
    ),
    tool(
        id = "get_context",
        mcpTools = listOf("device_get_context"),
        purpose = "Read the app-maintained device context file; no command round-trip.",
        family = ToolFamily.CONTEXT,
        risk = ToolRisk.LOW,
        confirmation = ConfirmationLevel.AUTO,
        timeoutMillis = 0L,
        requires = setOf(ToolRequirement.WORKSPACE_CHANNEL),
        transport = ToolTransport.WORKSPACE_FILE,
    ),
        )

    private val byId: Map<String, DeviceTool> = all.associateBy { it.id }

    /** The tools the bridge can execute - i.e. the action ids it must accept. */
    val actions: Set<String> = all.filter { it.transport == ToolTransport.BRIDGE }.map { it.id }.toSet()

    /** Tools that run without asking, unless the user overrides them the other way. */
    val autoActions: Set<String> =
        all.filter { it.transport == ToolTransport.BRIDGE && it.confirmation == ConfirmationLevel.AUTO }.map { it.id }.toSet()

    /** Tools that ask the user first. */
    val confirmActions: Set<String> =
        all.filter { it.transport == ToolTransport.BRIDGE && it.confirmation == ConfirmationLevel.CONFIRM }.map { it.id }.toSet()

    /** The explicit reader list the Read-Only switch allows; anything else fails closed. */
    val readOnlyActions: Set<String> = all.filter { it.readOnly }.map { it.id }.toSet()

    /** The curation the Device Agent screen edits, in catalog order. */
    val configurableActions: List<String> = all.filter { it.configurable }.map { it.id }

    /** The agent-facing names of every tool, in catalog order. */
    val mcpTools: List<String> = all.flatMap { it.mcpTools }

    fun tool(id: String): DeviceTool? = byId[id.lowercase()]

    /** Every tool the given agent-facing name reaches (one name can cover several actions). */
    fun toolsForMcp(mcpTool: String): List<DeviceTool> = all.filter { mcpTool in it.mcpTools }

    /**
     * The confirmation level declared for [id], or null when the id is not a tool.
     *
     * Null is deliberate: the firewall treats an unknown action as AUTO today (a known fail-open
     * hole scheduled for the Permission & Safety Center), and this catalog must not silently
     * change that by inventing a default here.
     */
    fun confirmationFor(id: String): ConfirmationLevel? = byId[id.lowercase()]?.confirmation

    /** The risk declared for [id], or null when the id is not a tool. */
    fun riskFor(id: String): ToolRisk? = byId[id.lowercase()]?.risk

    /** The requirements declared for [id], empty when the id is not a tool. */
    fun requirementsFor(id: String): Set<ToolRequirement> = byId[id.lowercase()]?.requires.orEmpty()
}
