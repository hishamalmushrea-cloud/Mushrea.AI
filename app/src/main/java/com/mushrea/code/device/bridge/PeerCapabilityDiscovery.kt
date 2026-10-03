package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.Capability
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.peer.PeerIdentity
import com.mushrea.code.core.execution.CapabilityStatus
import com.mushrea.code.runtime.local.AdbShellRunner

/**
 * What one probe learned about a peer: what it is, what it can do, and whether it reports exit codes.
 *
 * [reachable] is separate from the identity fields on purpose. A device can answer `getprop` (so the
 * channel works) while saying nothing useful about itself, and the caller has to be able to tell that
 * apart from "no channel at all".
 */
data class PeerProbeReport(
    val identity: PeerIdentity = PeerIdentity(),
    val capabilities: CapabilityReport = CapabilityReport.unknown(),
    val exitCodeSupport: Boolean = false,
    val output: String = "",
) {
    val reachable: Boolean get() = capabilities.has(CapabilityNames.SHELL)
}

/**
 * Asks a peer device what it can actually do.
 *
 * Android devices are not interchangeable, and the differences that matter are exactly the ones an
 * agent would otherwise assume away: `cmd` is missing on older builds, `uiautomator` is absent from
 * some OEM images, `python3` only exists if something shipped it, and `toybox` applets differ per
 * vendor. So instead of a hardcoded "ADB can do X", the platform runs one batched probe and records
 * the answer per device.
 *
 * The probe is one round trip: a shell script that prints `bin:<name>=<path>` and `prop:<key>=<value>`
 * lines. It is generated as *device* shell code (the local runtime shell must not expand it - see
 * [AdbCommandLine.shell]), and it is parsed by a pure function so the parsing can be tested against
 * real device output without a device.
 */
class PeerCapabilityDiscovery(private val runner: AdbShellRunner) {
    /** Runs the batched probe and the exit-code check. */
    suspend fun probe(
        serial: String,
        timeoutSeconds: Long = PROBE_TIMEOUT_SECONDS,
    ): PeerProbeReport {
        val output = runCatching { runner.runShellOnIo(AdbCommandLine.shell(serial, PeerCapabilityScript.script()), timeoutSeconds) }
        val text = output.getOrNull()?.output.orEmpty()
        val report = PeerCapabilityScript.parse(text)
        val exitCodes = runCatching { exitCodeSupport(serial) }.getOrDefault(false)
        return report.copy(exitCodeSupport = exitCodes)
    }

    /**
     * Whether the device's shell reports exit codes (`adb shell 'exit 7'` → exit code 7).
     *
     * This decides whether a command's failure can be *detected* at all: on a device where the code
     * is lost, a command that fails looks exactly like one that succeeded, so the platform has to
     * verify the effect instead of trusting the channel. It is measured, never assumed.
     */
    suspend fun exitCodeSupport(
        serial: String,
        timeoutSeconds: Long = EXIT_CODE_TIMEOUT_SECONDS,
    ): Boolean {
        val result = runner.runShellOnIo(AdbCommandLine.shell(serial, EXIT_CODE_PROBE_SCRIPT), timeoutSeconds)
        return result.exitCode == EXIT_CODE_PROBE_VALUE
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 60L
        const val EXIT_CODE_TIMEOUT_SECONDS = 20L
        const val EXIT_CODE_PROBE_VALUE = 7
        const val EXIT_CODE_PROBE_SCRIPT = "exit 7"
    }
}

/** The probe text and its parser, split out so both are unit-testable. */
object PeerCapabilityScript {
    /** Binaries the platform cares about; each becomes one capability entry. */
    private val BINARIES =
        listOf(
            CapabilityNames.SH to "sh",
            CapabilityNames.TOYBOX to "toybox",
            CapabilityNames.CMD to "cmd",
            CapabilityNames.PM to "pm",
            CapabilityNames.AM to "am",
            CapabilityNames.DUMPSYS to "dumpsys",
            CapabilityNames.SETTINGS to "settings",
            CapabilityNames.LOGCAT to "logcat",
            CapabilityNames.SCREENCAP to "screencap",
            CapabilityNames.INPUT to "input",
            CapabilityNames.UI_AUTOMATOR to "uiautomator",
            CapabilityNames.SU to "su",
            CapabilityNames.PYTHON to "python3",
            CapabilityNames.BASH to "bash",
        )

    private val PROPS =
        listOf(
            "ro.product.model" to "model",
            "ro.product.manufacturer" to "manufacturer",
            "ro.build.version.release" to "android",
            "ro.build.version.sdk" to "sdk",
            "ro.product.cpu.abi" to "abi",
        )

    /** The device-side script. No single quotes anywhere, so the caller can wrap it in one. */
    fun script(): String =
        buildString {
            append("for b in ")
            append(BINARIES.joinToString(" ") { it.second })
            append("; do echo \"bin:\$b=\$(command -v \$b 2>/dev/null)\"; done; ")
            PROPS.forEach { (prop, _) ->
                append("echo \"prop:$prop=\$(getprop $prop 2>/dev/null)\"; ")
            }
            append("echo \"marker=probe-done\"")
        }

    /**
     * Parses the probe output.
     *
     * A line the script did not print is ignored; a binary with an empty path is recorded as
     * [CapabilityStatus.MISSING] (the device answered, and it does not have it) while everything the
     * parse never saw stays unknown. That difference is what lets the planner say "this phone has no
     * `python3`, use `sh`" instead of "unknown, try anyway".
     */
    fun parse(output: String): CapabilityReport {
        val text = output.replace("\r\n", "\n")
        val capabilities = mutableListOf<Capability>()
        var sawShellOutput = false
        BINARIES.forEach { (capability, binary) ->
            val path = value(text, "bin:$binary=")
            sawShellOutput = sawShellOutput || text.contains("bin:$binary=")
            if (text.contains("bin:$binary=")) {
                capabilities.add(
                    if (path.isBlank()) {
                        Capability.missing(capability, "not on this device")
                    } else {
                        Capability.available(capability, path)
                    },
                )
            }
        }
        if (sawShellOutput) {
            // The probe ran through the device's shell, so `sh` exists whatever the lookup printed.
            capabilities.removeAll { it.name == CapabilityNames.SH && it.status == CapabilityStatus.MISSING }
            capabilities.add(Capability.available(CapabilityNames.SHELL, "shell answered the probe"))
            val packageManager = capabilities.firstOrNull { it.name == CapabilityNames.PM }
            capabilities.add(
                if (packageManager?.status == CapabilityStatus.AVAILABLE) {
                    Capability.available(CapabilityNames.PACKAGE_MANAGER, "pm is present")
                } else {
                    Capability.missing(CapabilityNames.PACKAGE_MANAGER, "pm is not on this device")
                },
            )
            // `sync:` is a service adbd always serves, but only when it is reachable at all.
            capabilities.add(Capability.available(CapabilityNames.SYNC, "adbd answered"))
        }
        return CapabilityReport.of(capabilities)
    }

    /** The device identity from the same output. */
    fun identity(output: String): PeerIdentity {
        val text = output.replace("\r\n", "\n")
        return PeerIdentity(
            model = value(text, "prop:ro.product.model="),
            manufacturer = value(text, "prop:ro.product.manufacturer="),
            androidVersion = value(text, "prop:ro.build.version.release="),
            sdk = value(text, "prop:ro.build.version.sdk=").toIntOrNull() ?: 0,
            abi = value(text, "prop:ro.product.cpu.abi="),
        )
    }

    private fun value(
        text: String,
        key: String,
    ): String =
        text
            .lineSequence()
            .firstOrNull { it.startsWith(key) }
            ?.removePrefix(key)
            ?.trim()
            .orEmpty()
}
