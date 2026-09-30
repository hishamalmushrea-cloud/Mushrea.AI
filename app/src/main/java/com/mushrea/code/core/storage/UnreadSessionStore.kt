package com.mushrea.code.core.storage

/**
 * Persists which chats finished without the user having read them.
 *
 * Declared in ``core`` because the encrypted settings store in the data layer implements it while
 * the runtime layer reads it: putting it in either of those layers would make the other one depend
 * on it in the wrong direction.
 */
interface UnreadSessionStore {
    var unreadSessionIds: Set<String>
}
