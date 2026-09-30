package com.mushrea.code.device.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mushrea.code.core.util.safeMessage
import com.mushrea.code.device.usb.AdbException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Bluetooth and BLE eyes of the device agent (user request: the network layer's
 * bluetooth and BLE leaves).
 *
 * Four read-only tools: adapter state, already-paired devices, a classic discovery
 * window and a BLE scan window. Nothing here pairs, connects or writes - pairing stays
 * a deliberate action in the system settings, and the agent-context file says so.
 *
 * On Android 12+ discovery and scanning need the runtime "Nearby devices" permission;
 * on older releases discovery needs location. When the grant is missing the tools refuse
 * with an honest explanation instead of pretending the air is empty.
 */
@Suppress("DEPRECATION")
class BluetoothExecutor(private val context: Context) {
    /** Adapter state plus permission and low-energy hardware honesty flags. */
    suspend fun executeInfo(): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val bt = adapter() ?: throw AdbException("this device has no bluetooth adapter")
            return@withContext {
                put("enabled", bt.isEnabled)
                put("state", stateName(bt.state))
                put("le_available", runCatching { bt.bluetoothLeScanner }.isSuccess)
                put("scan_permission_granted", hasScanPermission())
                put("connect_permission_granted", hasConnectPermission())
                if (!hasScanPermission()) {
                    put(
                        "permission_note",
                        "grant the nearby-devices permission (or location on android 11 and older) to scan",
                    )
                }
            }
        }

    /** Devices this phone is already paired with (bond state, kind, name when readable). */
    suspend fun executeDevices(): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val bt = adapter() ?: throw AdbException("this device has no bluetooth adapter")
            if (!hasConnectPermission()) {
                throw AdbException("the nearby-devices permission is missing - grant it in system settings")
            }
            val entries = JSONArray()
            bt.bondedDevices.orEmpty().sortedBy { it.address }.forEach { device ->
                entries.put(
                    JSONObject()
                        .put("name", runCatching { device.name }.getOrNull() ?: "")
                        .put("address", device.address)
                        .put("kind", typeName(device.type))
                        .put("bond_state", bondName(device.bondState)),
                )
            }
            return@withContext {
                put("count", entries.length())
                put("paired", entries)
            }
        }

    /** Classic discovery window: whatever answers in the air, with signal and device kind. */
    suspend fun executeScan(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val bt = readyAdapter()
            val seconds = params.optInt("seconds", 12).coerceIn(5, 20)
            val found = Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())
            var finished = false
            val latch = CountDownLatch(1)
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device =
                                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                        ?: return
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                synchronized(found) {
                                    val existing = found[device.address]
                                    if (existing == null) {
                                        found[device.address] =
                                            JSONObject()
                                                .put("name", runCatching { device.name }.getOrNull() ?: "")
                                                .put("address", device.address)
                                                .put("rssi", rssi)
                                                .put("kind", classOf(device))
                                    } else if (rssi > existing.optInt("rssi", Short.MIN_VALUE.toInt())) {
                                        existing.put("rssi", rssi)
                                    }
                                }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                finished = true
                                latch.countDown()
                            }
                        }
                    }
                }
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val started = runCatching { bt.startDiscovery() }.getOrElse { t ->
                ContextCompat.unregisterReceiver(context.applicationContext, receiver)
                throw AdbException("discovery failed to start: " + t.safeMessage("not allowed"))
            }
            if (!started) {
                ContextCompat.unregisterReceiver(context.applicationContext, receiver)
                throw AdbException("discovery did not start - is bluetooth on?")
            }
            try {
                latch.await(seconds.toLong(), TimeUnit.SECONDS)
            } finally {
                runCatching { bt.cancelDiscovery() }
                runCatching { ContextCompat.unregisterReceiver(context.applicationContext, receiver) }
            }
            val entries = JSONArray()
            synchronized(found) { found.values.forEach { entries.put(it) } }
            return@withContext {
                put("seconds", seconds)
                put("count", entries.length())
                put("devices", entries)
                put("discovery_finished", finished)
                put("note", "machines that do not answer discovery simply do not show up - that is normal")
            }
        }

    /** BLE beacon window in low-latency mode; unnamed beacons are normal and labelled so. */
    suspend fun executeBleScan(params: JSONObject): JSONObject.() -> Unit =
        withContext(Dispatchers.IO) {
            val bt = readyAdapter()
            val scanner =
                runCatching { bt.bluetoothLeScanner }.getOrElse { t ->
                    throw AdbException("no low-energy hardware: " + t.safeMessage("unavailable"))
                }
            val seconds = params.optInt("seconds", 8).coerceIn(5, 20)
            val results = Collections.synchronizedList(mutableListOf<JSONObject>())
            var failure: String? = null
            val latch = CountDownLatch(1)
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.scanRecord?.deviceName
                            ?: runCatching { result.device.name }.getOrNull()
                        results.add(
                            JSONObject()
                                .put("name", name ?: "")
                                .put("address", result.device.address)
                                .put("rssi", result.rssi)
                                .put("connectable", result.isConnectable),
                        )
                        if (results.size >= MAX_BLE_RESULTS) latch.countDown()
                    }

                    override fun onScanFailed(errorCode: Int) {
                        failure = scanErrorName(errorCode)
                        latch.countDown()
                    }
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner.startScan(callback, settings) }.getOrElse { t ->
                throw AdbException("ble scan failed to start: " + t.safeMessage("not allowed"))
            }
            try {
                latch.await(seconds.toLong(), TimeUnit.SECONDS)
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            val snapshot = synchronized(results) { results.toList().take(MAX_BLE_RESULTS) }
            val entries = JSONArray()
            snapshot.forEach { entries.put(it) }
            return@withContext {
                put("seconds", seconds)
                put("count", snapshot.size)
                put("beacons", entries)
                if (failure != null) put("failure", failure)
                put("note", "unnamed beacons are normal - many ble devices never advertise a name")
            }
        }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.applicationContext.getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter
    }

    private fun readyAdapter(): BluetoothAdapter {
        val bt = adapter() ?: throw AdbException("this device has no bluetooth adapter")
        if (!bt.isEnabled) throw AdbException("bluetooth is off - turn it on first")
        if (!hasScanPermission()) {
            throw AdbException("the nearby-devices permission is missing - grant it in system settings")
        }
        return bt
    }

    private fun hasScanPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun classOf(device: BluetoothDevice): String {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return "unknown"
        return when (major) {
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio-video"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.TOY -> "toy"
            BluetoothClass.Device.Major.HEALTH -> "health"
            BluetoothClass.Device.Major.IMAGING -> "imaging"
            else -> "other"
        }
    }

    private fun stateName(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
            BluetoothAdapter.STATE_BLE_ON -> "le-only"
            else -> "unknown"
        }

    private fun typeName(type: Int): String =
        when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "le"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        }

    private fun bondName(bond: Int): String =
        when (bond) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "none"
        }

    private fun scanErrorName(errorCode: Int): String =
        when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently - wait a few seconds"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "le scanning unsupported"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware resources"
            else -> "scan failed ($errorCode)"
        }

    private companion object {
        const val MAX_BLE_RESULTS = 100
    }
}
