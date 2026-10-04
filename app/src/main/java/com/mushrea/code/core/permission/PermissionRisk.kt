package com.mushrea.code.core.permission

/**
 * How much damage an operation can do if it runs when it should not.
 *
 * The level is about the *impact*, not about whether the user is asked: a read-only probe and a
 * shell command can both be `AUTO`, but they are not equally dangerous.
 *
 * It lives in `core` because the unified decision record carries it: the Permission Center explains
 * *why* a level was chosen, and "risk HIGH" is part of that explanation whether the operation came
 * from the device catalog or from a runtime lifecycle request. `device/tool/DeviceToolCatalog.kt`
 * keeps the name `ToolRisk` for its 90 entries through a typealias, so the vocabulary is one type,
 * not two.
 */
enum class PermissionRisk {
    /** Reads state on this phone; nothing outside it changes. */
    LOW,

    /** Changes reversible state, or reads something outside this phone. */
    MEDIUM,

    /** Irreversible, or acts on another device or an account: deletes, installs, shells, uploads. */
    HIGH,
}
