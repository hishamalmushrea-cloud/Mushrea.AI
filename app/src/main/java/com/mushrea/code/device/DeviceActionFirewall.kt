package com.mushrea.code.device

import com.mushrea.code.device.tool.DeviceToolCatalog

/**
 * How strictly the Permission Firewall treats a device action.
 */
enum class ConfirmationLevel {
    /** Runs immediately without asking the user. */
    AUTO,

    /** Asks the user once (notification with Allow / Reject actions) before running. */
    CONFIRM,

    /** Asks the user and requires an explicit acknowledgment every single time. */
    STRONG,
}

/**
 * The Permission Firewall for the Device Agent (prompt sections 35 and 45).
 *
 * Every device action is classified before execution:
 *
 * ```text
 * open app / read screen / search files / scroll / type  → AUTO   (per the product spec)
 * share file / delete file / move / rename               → CONFIRM
 * tap on a sensitive control (pay, delete, send…)        → escalated to CONFIRM
 * ```
 *
 * The user can override the level of any action from the Device Agent screen; overrides are
 * persisted by [DeviceAgentStore] and passed back in here. Sensitive-tap escalation cannot be
 * overridden to AUTO for STRONG-classified keywords such as payments, so a tap on "Pay now" is
 * never fired blind.
 */
class DeviceActionFirewall(overrides: Map<String, ConfirmationLevel> = emptyMap()) {
    private val overrides: Map<String, ConfirmationLevel> =
        overrides.entries
            .filter { it.key in ALL_ACTIONS && it.value != levelFor(it.key, userOverrides = null) }
            .associate { it.key.lowercase() to it.value }

    /** The effective level for [action], taking stored user overrides into account. */
    fun levelFor(action: String): ConfirmationLevel = levelFor(action, overrides)

    /**
     * The effective level for an element tap described by [elementText], which is escalated to
     * CONFIRM when the text looks like a sensitive control (pay / delete / send …).
     */
    fun levelForTap(elementText: String?): ConfirmationLevel {
        val base = levelFor(ACTION_TAP)
        if (base == ConfirmationLevel.STRONG) return base
        if (elementText != null && containsSensitiveKeyword(elementText)) {
            return ConfirmationLevel.CONFIRM
        }
        return base
    }

    private fun levelFor(
        action: String,
        userOverrides: Map<String, ConfirmationLevel>?,
    ): ConfirmationLevel {
        val key = action.lowercase()
        if (userOverrides != null) {
            userOverrides[key]?.let { return it }
        }
        // The declared level comes from the tool catalog, so this file cannot disagree with the
        // shipped tool table. An id that is not a tool still falls through to AUTO, which is a known
        // fail-open hole (the bridge rejects unknown ids first, but a future caller might not); it is
        // scheduled for the Permission & Safety Center rather than changed silently here.
        return DeviceToolCatalog.confirmationFor(key) ?: ConfirmationLevel.AUTO
    }

    companion object {
        const val ACTION_GET_CURRENT_APP = "get_current_app"
        const val ACTION_READ_SCREEN = "read_screen"
        const val ACTION_FIND_ELEMENT = "find_element"
        const val ACTION_LIST_APPS = "list_apps"
        const val ACTION_SEARCH_FILES = "search_files"
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_OPEN_URL = "open_url"
        const val ACTION_OPEN_FILE = "open_file"
        const val ACTION_PRESS_BACK = "press_back"
        const val ACTION_PRESS_HOME = "press_home"
        const val ACTION_OPEN_RECENTS = "open_recents"
        const val ACTION_SCROLL = "scroll"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_TAP = "tap"
        const val ACTION_LONG_PRESS = "long_press"
        const val ACTION_TYPE_TEXT = "type_text"
        const val ACTION_CLEAR_TEXT = "clear_text"
        const val ACTION_SEARCH_AND_TYPE = "search_and_type"
        const val ACTION_SCROLL_UNTIL_FOUND = "scroll_until_found"
        const val ACTION_WAIT_FOR_ELEMENT = "wait_for_element"
        const val ACTION_SHARE_FILE = "share_file"
        const val ACTION_DELETE_FILE = "delete_file"
        const val ACTION_MOVE_FILE = "move_file"
        const val ACTION_COPY_FILE = "copy_file"
        const val ACTION_RENAME_FILE = "rename_file"
        const val ACTION_SET_TASK = "set_task"
        const val ACTION_STOP = "stop_agent"
        const val ACTION_FIND_CONTACT = "find_contact"
        const val ACTION_CALL_AGENT = "call_agent"
        const val ACTION_CALL_STATE = "call_state"
        const val ACTION_CALL_STOP = "call_stop"
        const val ACTION_READ_CALL_LOG = "read_call_log"
        const val ACTION_PING = "ping"
        const val ACTION_DEVICE_STATUS = "device_status"
        const val ACTION_CALL_SUMMARIES = "call_summaries"
        const val ACTION_USB_DEVICES = "usb_devices"
        const val ACTION_USB_SHELL = "usb_shell"
        const val ACTION_USB_LIST = "usb_list"
        const val ACTION_USB_PULL = "usb_pull"
        const val ACTION_USB_PUSH = "usb_push"
        const val ACTION_USB_TRANSFER_MEDIA = "usb_transfer_media"
        const val ACTION_USB_SCREENSHOT = "usb_screenshot"
        const val ACTION_USB_INSTALL = "usb_install"
        const val ACTION_USB_LOGCAT = "usb_logcat"
        const val ACTION_USB_INFO = "usb_info"
        const val ACTION_USB_SERIAL_SEND = "usb_serial_send"
        const val ACTION_USB_SERIAL_READ = "usb_serial_read"
        const val ACTION_USB_TCPIP = "usb_tcpip_enable"
        const val ACTION_TCP_SHELL = "tcp_shell"
        const val ACTION_SSH_EXEC = "ssh_exec"
        const val ACTION_SSH_LIST = "ssh_list"
        const val ACTION_SSH_DOWNLOAD = "ssh_download"
        const val ACTION_SSH_UPLOAD = "ssh_upload"
        const val ACTION_PAYLOAD_INFO = "payload_info"
        const val ACTION_PAYLOAD_EXTRACT = "payload_extract"
        const val ACTION_FASTBOOT_GETVAR = "fastboot_getvar"
        const val ACTION_MIRROR_START = "mirror_start"
        const val ACTION_MIRROR_STOP = "mirror_stop"
        const val ACTION_SCRCPY_START = "scrcpy_start"
        const val ACTION_SCRCPY_STOP = "scrcpy_stop"
        const val ACTION_USB_HUB_LIST = "usb_hub_list"
        const val ACTION_MTP_LIST = "mtp_list"
        const val ACTION_MTP_DOWNLOAD = "mtp_download"
        const val ACTION_HID_READ = "hid_read"
        const val ACTION_STORAGE_VOLUMES = "storage_volumes"
        const val ACTION_CAMERA_LIST = "camera_list"
        const val ACTION_NET_BROWSE = "net_browse"
        const val ACTION_REMOTE_LIST = "remote_list"
        const val ACTION_REMOTE_DOWNLOAD = "remote_download"
        const val ACTION_WIFI_INFO = "wifi_info"
        const val ACTION_DNS_LOOKUP = "dns_lookup"
        const val ACTION_NET_PING = "net_ping"
        const val ACTION_PORT_CHECK = "port_check"
        const val ACTION_HTTP_REQUEST = "http_request"
        const val ACTION_WEBSOCKET = "websocket"
        const val ACTION_BT_INFO = "bt_info"
        const val ACTION_BT_DEVICES = "bt_devices"
        const val ACTION_BT_SCAN = "bt_scan"
        const val ACTION_BLE_SCAN = "ble_scan"

        // On-device bridge + flashing safety (Phase 1/2 of the Termux-bridge work). The destructive
        // half (stage / oem unlock / flash / erase / lock) is deliberately NOT an action yet:
        // TermuxCommandPolicy refuses those commands, and wiring them needs the typed-confirmation
        // flow that a later phase adds.
        const val ACTION_USB_MODE = "usb_mode"
        const val ACTION_USB_DIAGNOSTICS = "usb_diagnostics"
        const val ACTION_FASTBOOT_GETVAR_FULL = "fastboot_getvar_full"
        const val ACTION_PAYLOAD_GUARD = "payload_guard"
        const val ACTION_SAFETY_PREFLIGHT = "safety_preflight"
        const val ACTION_AUDIT_EXPORT = "audit_export"
        const val ACTION_TERMUX_STATUS = "termux_status"
        const val ACTION_TERMUX_RUN = "termux_run"
        const val ACTION_TERMUX_FASTBOOT_RUN = "termux_fastboot_run"
        const val ACTION_MITOOL_WRAPPER = "mitool_wrapper"

        /** Every action the bridge accepts, from the tool catalog; unknown actions are rejected first. */
        val ALL_ACTIONS: Set<String> = DeviceToolCatalog.actions

        /** Actions that run without asking (unless the user overrides them the other way). */
        val AUTO_ACTIONS: Set<String> = DeviceToolCatalog.autoActions

        /** Actions that ask the user first, derived from the same table. */
        val CONFIRM_ACTIONS: Set<String> = DeviceToolCatalog.confirmActions

        /**
         * Read-Only Default: the actions allowed while the read-only switch is on. It is the
         * explicit reader list from the tool catalog - every action that changes this phone, the
         * other phone, the bootloader, or the network is absent, so a mistake here fails closed (a
         * name that is not in the set is blocked, it does not slip through).
         */
        val READ_ONLY_ACTIONS: Set<String> = DeviceToolCatalog.readOnlyActions

        /** True when [action] may run while Read-Only Default is enabled. */
        fun isAllowedInReadOnly(action: String): Boolean = action.lowercase() in READ_ONLY_ACTIONS

        /** Actions exposed on the firewall customization list. */
        val CONFIGURABLE_ACTIONS: List<String> =
            listOf(
                ACTION_TAP,
                ACTION_TYPE_TEXT,
                ACTION_OPEN_APP,
                ACTION_OPEN_FILE,
                ACTION_CALL_AGENT,
                ACTION_SHARE_FILE,
                ACTION_DELETE_FILE,
                ACTION_MOVE_FILE,
                ACTION_COPY_FILE,
                ACTION_RENAME_FILE,
                ACTION_READ_CALL_LOG,
                ACTION_PING,
                ACTION_DEVICE_STATUS,
                ACTION_CALL_SUMMARIES,
                ACTION_USB_DEVICES,
                ACTION_USB_SHELL,
                ACTION_USB_PULL,
                ACTION_USB_PUSH,
                ACTION_USB_TRANSFER_MEDIA,
                ACTION_USB_SCREENSHOT,
                ACTION_USB_INSTALL,
                ACTION_USB_LOGCAT,
                ACTION_USB_SERIAL_SEND,
                ACTION_USB_TCPIP,
                ACTION_TCP_SHELL,
                ACTION_SSH_EXEC,
                ACTION_SSH_DOWNLOAD,
                ACTION_SSH_UPLOAD,
                ACTION_MIRROR_STOP,
            )

        // Kept deliberately short and high-precision: a missed match just means the tap runs as
        // AUTO, while a false match only costs the user one confirmation tap.
        internal val SENSITIVE_KEYWORDS: List<String> =
            listOf(
                // English
                "pay",
                "payment",
                "checkout",
                "purchase",
                "buy",
                "delete",
                "remove",
                "send",
                "transfer",
                "confirm payment",
                "place order",
                "sign out",
                "logout",
                // Arabic
                "ادفع",
                "دفع",
                "شراء",
                "احذف",
                "حذف",
                "إرسال",
                "ارسال",
                "تحويل",
                "تأكيد",
            )

        /** True when [text] (a button/element label) matches a sensitive keyword. */
        fun containsSensitiveKeyword(text: String): Boolean {
            val normalized = text.lowercase()
            return SENSITIVE_KEYWORDS.any { keyword -> normalized.contains(keyword) }
        }
    }
}
