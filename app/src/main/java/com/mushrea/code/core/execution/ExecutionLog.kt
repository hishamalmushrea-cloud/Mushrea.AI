package com.mushrea.code.core.execution

import java.security.MessageDigest

/**
 * One line of the execution history: what was asked, where, and how it ended.
 *
 * It carries no raw command line and no output. A shell line can contain a password, a token or a
 * path that names the user's private file, and the log is read by screens and exported by the audit
 * path - so the command travels as [commandIdentity] (program plus a short digest) and the outcome as
 * counts. Anything a caller needs to explain to the user they still have: this is the *record*, not
 * the transcript.
 */
data class ExecutionRecord(
    val correlationId: String,
    val timestampMillis: Long,
    val targetId: String,
    val providerId: String,
    val operation: ExecutionOperation,
    val commandIdentity: String,
    val stage: ExecutionStage,
    val exitCode: Int?,
    val durationMillis: Long,
    val verification: String,
    val errorCode: String?,
    val error: String,
) {
    /** True when a command ran, whatever it reported. */
    val executed: Boolean get() = stage != ExecutionStage.REJECTED && stage != ExecutionStage.TRANSPORT_FAILED

    companion object {
        /**
         * A short, stable identity for a command: the program name plus the first eight hex digits of
         * the SHA-256 of the whole line. Enough to correlate two records of the same call, useless as
         * a secret, and never the raw text.
         */
        fun identity(
            command: String,
            arguments: List<String> = emptyList(),
        ): String {
            val program = command.trim().substringBefore(' ').substringAfterLast('/')
            val full = (listOf(command) + arguments).joinToString(" ")
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(full.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
                    .take(8)
            return if (program.isBlank()) "#$digest" else "$program#$digest"
        }
    }
}

/** Keeps the last [capacity] records in memory. Oldest are dropped; nothing is persisted here. */
class ExecutionLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val records = ArrayDeque<ExecutionRecord>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun record(record: ExecutionRecord) {
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = records.size): List<ExecutionRecord> = records.toList().takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun forTarget(targetId: String): List<ExecutionRecord> = records.filter { it.targetId == targetId }

    @Synchronized
    fun clear() = records.clear()

    @Synchronized
    fun size(): Int = records.size

    private companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
