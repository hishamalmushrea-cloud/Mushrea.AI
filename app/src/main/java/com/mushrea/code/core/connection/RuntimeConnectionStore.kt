package com.mushrea.code.core.connection

/**
 * Storage the runtime registry reads and writes through, implemented by the encrypted settings
 * store in the data layer.
 *
 * Declared in ``core`` so that ``data`` can implement it without depending on the runtime layer,
 * and the runtime layer can consume it without depending on how it is persisted.
 */
interface RuntimeConnectionStore {
    var selectedRuntimeId: String?

    fun connections(): List<ConnectionProfile>

    fun upsertConnection(profile: ConnectionProfile)

    fun deleteConnection(id: String)
}
