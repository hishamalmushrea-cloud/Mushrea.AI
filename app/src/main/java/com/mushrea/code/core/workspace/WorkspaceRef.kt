package com.mushrea.code.core.workspace

/**
 * One workspace a runtime can open a session in: an id, the name shown in the UI, and the path the
 * runtime itself uses.
 *
 * Declared in ``core`` because the runtime layer reports these, the data layer lists them and the
 * UI renders them - putting it in any one of those layers would force the others to depend on it.
 */
data class WorkspaceRef(
    val id: String,
    val name: String,
    val path: String,
)
