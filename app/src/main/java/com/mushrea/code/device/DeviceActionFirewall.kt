package com.mushrea.code.device

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
        return when (key) {
            in AUTO_ACTIONS -> ConfirmationLevel.AUTO
            ACTION_SHARE_FILE, ACTION_DELETE_FILE, ACTION_MOVE_FILE, ACTION_COPY_FILE, ACTION_RENAME_FILE -> ConfirmationLevel.CONFIRM
            else -> ConfirmationLevel.AUTO
        }
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
        const val ACTION_SHARE_FILE = "share_file"
        const val ACTION_DELETE_FILE = "delete_file"
        const val ACTION_MOVE_FILE = "move_file"
        const val ACTION_COPY_FILE = "copy_file"
        const val ACTION_RENAME_FILE = "rename_file"
        const val ACTION_STOP = "stop_agent"

        /** Every action the bridge accepts; unknown actions are rejected before the firewall runs. */
        val ALL_ACTIONS: Set<String> =
            setOf(
                ACTION_GET_CURRENT_APP,
                ACTION_READ_SCREEN,
                ACTION_FIND_ELEMENT,
                ACTION_LIST_APPS,
                ACTION_SEARCH_FILES,
                ACTION_OPEN_APP,
                ACTION_OPEN_URL,
                ACTION_OPEN_FILE,
                ACTION_PRESS_BACK,
                ACTION_PRESS_HOME,
                ACTION_OPEN_RECENTS,
                ACTION_SCROLL,
                ACTION_SWIPE,
                ACTION_TAP,
                ACTION_LONG_PRESS,
                ACTION_TYPE_TEXT,
                ACTION_CLEAR_TEXT,
                ACTION_SHARE_FILE,
                ACTION_DELETE_FILE,
                ACTION_MOVE_FILE,
                ACTION_COPY_FILE,
                ACTION_RENAME_FILE,
                ACTION_SET_TASK,
                ACTION_STOP,
            )

        /** Actions that run without asking (unless the user overrides them the other way). */
        val AUTO_ACTIONS: Set<String> =
            setOf(
                ACTION_GET_CURRENT_APP,
                ACTION_READ_SCREEN,
                ACTION_FIND_ELEMENT,
                ACTION_LIST_APPS,
                ACTION_SEARCH_FILES,
                ACTION_OPEN_APP,
                ACTION_OPEN_URL,
                ACTION_OPEN_FILE,
                ACTION_PRESS_BACK,
                ACTION_PRESS_HOME,
                ACTION_OPEN_RECENTS,
                ACTION_SCROLL,
                ACTION_SWIPE,
                ACTION_TAP,
                ACTION_LONG_PRESS,
                ACTION_TYPE_TEXT,
                ACTION_CLEAR_TEXT,
                ACTION_SET_TASK,
                ACTION_STOP,
            )

        /** Actions exposed on the firewall customization list. */
        val CONFIGURABLE_ACTIONS: List<String> =
            listOf(
                ACTION_TAP,
                ACTION_TYPE_TEXT,
                ACTION_OPEN_APP,
                ACTION_OPEN_FILE,
                ACTION_SHARE_FILE,
                ACTION_DELETE_FILE,
                ACTION_MOVE_FILE,
                ACTION_COPY_FILE,
                ACTION_RENAME_FILE,
            )

        // Kept deliberately short and high-precision: a missed match just means the tap runs as
        // AUTO, while a false match only costs the user one confirmation tap.
        internal val SENSITIVE_KEYWORDS: List<String> =
            listOf(
                // English
                "pay", "payment", "checkout", "purchase", "buy", "delete", "remove",
                "send", "transfer", "confirm payment", "place order", "sign out", "logout",
                // Arabic
                "ادفع", "دفع", "شراء", "احذف", "حذف", "إرسال", "ارسال", "تحويل", "تأكيد",
            )

        /** True when [text] (a button/element label) matches a sensitive keyword. */
        fun containsSensitiveKeyword(text: String): Boolean {
            val normalized = text.lowercase()
            return SENSITIVE_KEYWORDS.any { keyword -> normalized.contains(keyword) }
        }
    }
}
