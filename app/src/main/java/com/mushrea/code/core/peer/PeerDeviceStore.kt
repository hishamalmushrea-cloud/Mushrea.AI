package com.mushrea.code.core.peer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where the known peers are kept. Implementations decide the storage (settings, file, memory). */
interface PeerDeviceStore {
    fun load(): List<PeerDevice>

    fun save(devices: List<PeerDevice>)
}

/** The safe default: remembers devices for the lifetime of the process only. */
class InMemoryPeerDeviceStore : PeerDeviceStore {
    @Volatile
    private var devices: List<PeerDevice> = emptyList()

    override fun load(): List<PeerDevice> = devices

    override fun save(devices: List<PeerDevice>) {
        this.devices = devices
    }
}

/** JSON codec for the store, so a settings-backed implementation has one implementation of the shape. */
object PeerDeviceCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encode(devices: List<PeerDevice>): String = json.encodeToString(devices)

    fun decode(text: String): List<PeerDevice> {
        if (text.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PeerDevice>>(text) }.getOrDefault(emptyList())
    }
}
