package com.mushrea.code.core.execution

/**
 * The bridge between the canonical capability names and the flat `name = value` map the platform
 * stores per device.
 *
 * Two facts make this necessary and small:
 *  * the **store** is what a settings row, the device record and the agent's `capabilities` output
 *    read, and it is a plain string map - so a report has to be written to it and read back without
 *    losing the difference between "measured absent" (a blank value) and "never measured" (no entry);
 *  * the **first probe** named binary capabilities plainly (`pm`, `screencap`), and devices the user
 *    already paired have maps in that shape. Reading those maps has to keep working: their names are
 *    *aliases* of the canonical `bin:<program>` name, not a second capability.
 *
 * Nothing here adds or removes a capability. A name that already is canonical round-trips unchanged,
 * which is what lets the naming convention be extended later without touching stored data.
 */
object CapabilityAliases {
    /**
     * Plain names from earlier releases, mapped to the canonical name of the same measurement.
     *
     * Only the names the platform used to publish appear here; a device map that carries anything
     * else is read as-is, because an unknown name is still a capability.
     */
    val ALIASES: Map<String, String> =
        mapOf(
            "sh" to CapabilityNames.binary("sh"),
            "bash" to CapabilityNames.binary("bash"),
            "python" to CapabilityNames.binary("python"),
            "python3" to CapabilityNames.binary("python3"),
            "toybox" to CapabilityNames.binary("toybox"),
            "toolbox" to CapabilityNames.binary("toolbox"),
            "cmd" to CapabilityNames.binary("cmd"),
            "pm" to CapabilityNames.binary("pm"),
            "am" to CapabilityNames.binary("am"),
            "monkey" to CapabilityNames.binary("monkey"),
            "dumpsys" to CapabilityNames.binary("dumpsys"),
            "settings" to CapabilityNames.binary("settings"),
            "logcat" to CapabilityNames.binary("logcat"),
            "screencap" to CapabilityNames.binary("screencap"),
            "input" to CapabilityNames.binary("input"),
            "uiautomator" to CapabilityNames.binary("uiautomator"),
            "wm" to CapabilityNames.binary("wm"),
            "service" to CapabilityNames.binary("service"),
            "su" to CapabilityNames.binary("su"),
        )

    /** The canonical name for [name]: an alias is folded onto the fact it names, everything else stands. */
    fun canonical(name: String): String = ALIASES[name] ?: name

    /** The legacy names that read as [name], for a re-probe that has to keep a stored key in step. */
    fun aliasesOf(name: String): List<String> = ALIASES.filterValues { canonicalName -> canonicalName == name }.keys.toList()

    /**
     * The report as the flat map the platform stores.
     *
     * A capability the probe measured as absent is written with a blank value (that is the difference
     * the reader needs), and everything else carries its detail - a path, a version, `yes`.
     */
    fun store(report: CapabilityReport): Map<String, String> =
        report.all
            .sortedBy { it.name }
            .associate { capability ->
                capability.name to
                    when (capability.status) {
                        CapabilityStatus.MISSING -> ""
                        else -> capability.detail.ifBlank { "ok" }
                    }
            }

    /**
     * Reads a stored map back.
     *
     * A blank value is a measurement of absence; a missing key is nothing measured. Legacy plain names
     * are folded onto their canonical name, so an older stored map yields the same report a new probe
     * would - no migration step, no second code path.
     */
    fun report(values: Map<String, String>): CapabilityReport =
        CapabilityReport.of(
            values.map { (name, value) ->
                val canonicalName = canonical(name)
                if (value.isBlank()) {
                    CapabilityReport.missing(canonicalName)
                } else {
                    CapabilityReport.available(canonicalName, value)
                }
            },
        )
}
