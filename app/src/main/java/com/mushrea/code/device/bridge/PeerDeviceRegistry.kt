package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityReport
import com.mushrea.code.core.peer.InMemoryPeerDeviceStore
import com.mushrea.code.core.peer.PeerDevice
import com.mushrea.code.core.peer.PeerDeviceState
import com.mushrea.code.core.peer.PeerDeviceStore
import com.mushrea.code.core.peer.PeerIdentity

/**
 * The known peer devices, kept in one place.
 *
 * It exists so nothing else has to answer "which phone was that?" from a port number. Every mutation
 * is a merge, not a replace: a device that appears in an mDNS announcement without an identity must
 * not erase the identity learned the last time it was connected, and a device that goes quiet must
 * keep its place in the list (and its paired state) so the user does not have to pair again.
 */
class PeerDeviceRegistry(
    private val store: PeerDeviceStore = InMemoryPeerDeviceStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun all(): List<PeerDevice> = synchronized(lock) { store.load().sortedBy { it.label.lowercase() } }

    fun find(serial: String): PeerDevice? = synchronized(lock) { store.load().firstOrNull { it.serial == serial } }

    fun findByInstance(instanceName: String): PeerDevice? =
        synchronized(lock) { store.load().firstOrNull { it.instanceName.isNotBlank() && it.instanceName == instanceName } }

    /** Records that a device was announced; creates the row when it is new. */
    fun discovered(service: PeerAdbService): PeerDevice =
        merge(
            PeerDevice(
                serial = service.adbSerial,
                instanceName = service.instanceName,
                host = service.host,
                port = service.port,
                state = PeerDeviceState.DISCOVERED,
            ),
        )

    /** Records a successful pairing (the key is accepted; the channel is not open yet). */
    fun paired(service: PeerAdbService): PeerDevice =
        merge(
            PeerDevice(
                serial = service.adbSerial,
                instanceName = service.instanceName,
                host = service.host,
                port = service.port,
                state = PeerDeviceState.PAIRED,
            ),
        )

    /** Records a live channel plus whatever the device said about itself. */
    fun connected(
        serial: String,
        host: String,
        port: Int,
        identity: PeerIdentity = PeerIdentity(),
    ): PeerDevice =
        merge(
            PeerDevice(
                serial = serial,
                host = host,
                port = port,
                state = PeerDeviceState.CONNECTED,
                model = identity.model,
                manufacturer = identity.manufacturer,
                androidVersion = identity.androidVersion,
                sdk = identity.sdk,
                abi = identity.abi,
            ),
        )

    /** Records that a real command answered: this is the state execution is allowed from. */
    fun verified(
        serial: String,
        identity: PeerIdentity = PeerIdentity(),
    ): PeerDevice {
        val updated =
            merge(
                PeerDevice(
                    serial = serial,
                    state = PeerDeviceState.VERIFIED,
                    model = identity.model,
                    manufacturer = identity.manufacturer,
                    androidVersion = identity.androidVersion,
                    sdk = identity.sdk,
                    abi = identity.abi,
                ),
            )
        return updated
    }

    fun capabilities(
        serial: String,
        report: CapabilityReport,
    ): PeerDevice = merge(find(serial).orEmpty(serial).withCapabilities(report))

    fun state(
        serial: String,
        newState: PeerDeviceState,
    ): PeerDevice = merge(find(serial).orEmpty(serial).withState(newState))

    fun forget(serial: String): Boolean {
        synchronized(lock) {
            val known = store.load()
            val remaining = known.filterNot { it.serial == serial }
            store.save(remaining)
            return remaining.size != known.size
        }
    }

    private fun PeerDevice?.orEmpty(serial: String): PeerDevice = this ?: PeerDevice(serial = serial)

    /**
     * Merges a row by serial.
     *
     * Blank fields never overwrite a known value - the identity learned from a device must survive a
     * later announcement that carries only an address - and the timestamp is refreshed on every
     * merge, which is what "last seen" means.
     */
    private fun merge(update: PeerDevice): PeerDevice {
        synchronized(lock) {
            val current = store.load().firstOrNull { it.serial == update.serial }
            val merged =
                if (current == null) {
                    update.copy(lastSeenMillis = clock())
                } else {
                    current.copy(
                        instanceName = update.instanceName.ifBlank { current.instanceName },
                        host = update.host.ifBlank { current.host },
                        port = if (update.port > 0) update.port else current.port,
                        state = update.state,
                        model = update.model.ifBlank { current.model },
                        manufacturer = update.manufacturer.ifBlank { current.manufacturer },
                        androidVersion = update.androidVersion.ifBlank { current.androidVersion },
                        sdk = if (update.sdk > 0) update.sdk else current.sdk,
                        abi = update.abi.ifBlank { current.abi },
                        capabilities = if (update.capabilities.isEmpty()) current.capabilities else update.capabilities,
                        lastSeenMillis = clock(),
                    )
                }
            store.save(store.load().filterNot { it.serial == update.serial } + merged)
            return merged
        }
    }
}

/** What the device said about itself, filled from one `getprop` batch. */
data class PeerIdentity(
    val model: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val sdk: Int = 0,
    val abi: String = "",
) {
    val known: Boolean get() = model.isNotBlank() || androidVersion.isNotBlank()
}
