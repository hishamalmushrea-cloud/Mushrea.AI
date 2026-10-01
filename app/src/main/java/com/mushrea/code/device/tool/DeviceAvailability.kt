package com.mushrea.code.device.tool

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import com.mushrea.code.core.storage.DeviceStorage
import com.mushrea.code.device.DeviceAgentStore
import com.mushrea.code.device.MushreaCodeAccessibilityService
import com.mushrea.code.device.call.PhoneCallController
import com.mushrea.code.device.termux.TermuxBridge
import com.mushrea.code.device.usb.UsbDeviceAgent
import com.mushrea.code.device.usb.UsbSerialAgent
import com.mushrea.code.device.usbhub.MtpAgent
import java.io.File

/**
 * Whether the device can run a tool right now.
 *
 * [ToolRequirement] has always been a *declaration* ("this tool needs the accessibility service")
 * with nothing evaluating it: a blocked tool ran anyway and failed with whatever its executor
 * happened to hit, which is how an agent ends up guessing why nothing works. This class is the
 * missing evaluator.
 *
 * Three answers, because only two of them can be honest before the call runs:
 *  * [Ready] — every probe the catalog names answered yes;
 *  * [Blocked] — one answered no, with the reason worth showing to the user and the agent;
 *  * [CallDependent] — the requirement is satisfied *by the request itself* (a host, a credential, a
 *    file path, a device the user attaches), so no amount of probing beforehand can decide it. The
 *    executor still reports the real failure; claiming readiness or blocking would both be guesses.
 */
sealed interface ToolAvailability {
    data object Ready : ToolAvailability

    data class Blocked(val reason: String) : ToolAvailability

    data object CallDependent : ToolAvailability
}

/**
 * Evaluates the catalog's requirements against this device.
 *
 * The probe is injected so the rule ("blocked means the reason is surfaced, call-dependent never
 * blocks") is unit-testable without an Android device, which is the part that has to stay correct.
 */
class DeviceAvailability(
    private val probe: (ToolRequirement) -> ToolAvailability,
) {
    /** Every requirement the tool declares must hold; the first blocked one becomes the reason. */
    fun of(tool: DeviceTool): ToolAvailability =
        tool.requires
            .asSequence()
            .map(probe)
            .filterIsInstance<ToolAvailability.Blocked>()
            .firstOrNull()
            ?: ToolAvailability.Ready

    /**
     * The reason to refuse `action` now, or null when it may run.
     *
     * An action that is not in the catalog is not refused here: an unknown id is already denied by
     * [com.mushrea.code.device.permission.ToolPermissionPolicy], and this evaluator only speaks
     * about declared requirements.
     */
    fun blockedReason(action: String): String? {
        val tool = DeviceToolCatalog.tool(action) ?: return null
        val availability = of(tool)
        return (availability as? ToolAvailability.Blocked)?.reason
    }

    /**
     * Every tool this device cannot run right now, in catalog order — what the readiness button
     * shows so the user sees the consequence of a missing permission or an unplugged device before
     * the agent runs into it.
     */
    fun blockedTools(): List<Pair<String, String>> =
        DeviceToolCatalog.all.mapNotNull { tool ->
            (of(tool) as? ToolAvailability.Blocked)?.let { tool.id to it.reason }
        }

    companion object {
        /** The real device probes; all of them are local state reads, none opens a dialog. */
        fun onDevice(context: Context): DeviceAvailability {
            val app = context.applicationContext
            val probes = DeviceProbes(app)
            return DeviceAvailability(probes::probe)
        }
    }
}

/**
 * One probe per requirement, each reading state the platform already exposes.
 *
 * Nothing here asks the user for anything: an interactive permission request belongs to the tool
 * that needs it (and to the confirmation gate), not to a pre-flight check that may run while the
 * agent is working unattended.
 */
private class DeviceProbes(private val context: Context) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun probe(requirement: ToolRequirement): ToolAvailability =
        when (requirement) {
            ToolRequirement.ACCESSIBILITY ->
                blockedUnless(MushreaCodeAccessibilityService.isRunning()) {
                    "the Mushrea Code accessibility service is not enabled"
                }

            ToolRequirement.DEVICE_STORAGE ->
                blockedUnless(!DeviceStorage.mounts().isEmpty) {
                    "all-files access has not been granted to Mushrea Code"
                }

            ToolRequirement.CALL_PERMISSIONS -> {
                val granted =
                    CALL_PERMISSIONS.count {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                blockedUnless(granted == CALL_PERMISSIONS.size) {
                    "phone permissions are not granted ($granted/${CALL_PERMISSIONS.size})"
                }
            }

            ToolRequirement.DEFAULT_DIALER ->
                blockedUnless(runCatching { PhoneCallController(context).isDefaultDialer() }.getOrDefault(false)) {
                    "Mushrea Code is not the default dialer"
                }

            ToolRequirement.USB_ADB_DEVICE ->
                blockedUnless(runCatching { UsbDeviceAgent(context).adbDevices().isNotEmpty() }.getOrDefault(false)) {
                    "no Android phone answering ADB is attached over USB"
                }

            ToolRequirement.USB_SERIAL_DEVICE ->
                blockedUnless(runCatching { UsbSerialAgent(context).ports().isNotEmpty() }.getOrDefault(false)) {
                    "no USB serial adapter is attached"
                }

            ToolRequirement.USB_ANY_DEVICE ->
                blockedUnless(runCatching { usbManager?.deviceList?.isNotEmpty() == true }.getOrDefault(false)) {
                    "no USB device is attached"
                }

            ToolRequirement.MTP_DEVICE ->
                blockedUnless(runCatching { MtpAgent(context).mtpDevices().isNotEmpty() }.getOrDefault(false)) {
                    "no phone in file-transfer (MTP) mode is attached"
                }

            ToolRequirement.WIFI_STATE ->
                blockedUnless(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) ==
                        PackageManager.PERMISSION_GRANTED,
                ) {
                    "Wi-Fi state is not readable (ACCESS_WIFI_STATE not granted)"
                }

            ToolRequirement.NETWORK ->
                blockedUnless(runCatching { activeNetwork() }.getOrDefault(false)) {
                    "this phone has no network connection"
                }

            ToolRequirement.BT_NEARBY -> {
                val adapter = runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }.getOrNull()
                val permitted = BLUETOOTH_PERMISSIONS.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                when {
                    adapter == null -> ToolAvailability.Blocked("this device has no Bluetooth adapter")
                    !adapter.isEnabled -> ToolAvailability.Blocked("Bluetooth is turned off")
                    !permitted -> ToolAvailability.Blocked("nearby-devices permission is not granted (BLUETOOTH_CONNECT)")
                    else -> ToolAvailability.Ready
                }
            }

            ToolRequirement.TERMUX_BRIDGE -> termuxReady()

            // `termux-fastboot` lives inside Termux; proving the binary exists means running a
            // command in the guest, which is the tool's own first step. What can be checked here is
            // whether Termux itself is usable at all.
            ToolRequirement.TERMUX_FASTBOOT -> termuxReady()

            ToolRequirement.WORKSPACE_CHANNEL ->
                blockedUnless(workspaceRegistered()) {
                    "the agent workspace is not registered yet - open the Device Agent once"
                }

            ToolRequirement.AUDIT_LOG ->
                blockedUnless(runCatching { DeviceAgentStore(context).activityLog().isNotEmpty() }.getOrDefault(false)) {
                    "there is no recorded activity to export yet"
                }

            // Supplied by the call: a host and credential, a file path, a codename, a device.
            ToolRequirement.SSH_TARGET,
            ToolRequirement.REMOTE_TARGET,
            ToolRequirement.PAYLOAD_FILE,
            ToolRequirement.SAFETY_TARGET,
            -> ToolAvailability.CallDependent
        }

    private fun termuxReady(): ToolAvailability {
        val status = runCatching { TermuxBridge(context).status() }.getOrNull()
        return when {
            status == null -> ToolAvailability.Blocked("Termux state could not be read")
            status.ready -> ToolAvailability.Ready
            status.missing.isNotEmpty() -> ToolAvailability.Blocked("Termux is not ready: ${status.missing.joinToString()}")
            else -> ToolAvailability.Blocked("Termux is not ready")
        }
    }

    private fun activeNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return manager.activeNetwork != null
    }

    /** The same answer the readiness screen gives: a registered workspace, or the default one. */
    private fun workspaceRegistered(): Boolean {
        val store = DeviceAgentStore(context)
        val registered = store.readActiveWorkspace()
        if (registered != null) return true
        return File(context.filesDir, "runtime/workspace").isDirectory
    }

    private fun blockedUnless(
        condition: Boolean,
        reason: () -> String,
    ): ToolAvailability = if (condition) ToolAvailability.Ready else ToolAvailability.Blocked(reason())

    private companion object {
        val CALL_PERMISSIONS =
            listOf(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_PHONE_STATE,
            )
        val BLUETOOTH_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    }
}
