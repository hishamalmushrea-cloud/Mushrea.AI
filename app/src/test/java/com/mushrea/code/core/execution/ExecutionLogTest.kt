package com.mushrea.code.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the execution log keeps, and - more importantly - what it refuses to keep. */
class ExecutionLogTest {
    private fun record(
        correlationId: String,
        targetId: String = "serial-x",
        stage: ExecutionStage = ExecutionStage.SUCCEEDED,
    ) = ExecutionRecord(
        correlationId = correlationId,
        timestampMillis = 1_000L,
        targetId = targetId,
        providerId = "peer-adb",
        operation = ExecutionOperation.SHELL,
        commandIdentity = ExecutionRecord.identity("pm list packages"),
        stage = stage,
        exitCode = 0,
        durationMillis = 12,
        verification = "not requested",
        errorCode = null,
        error = "",
    )

    @Test
    fun `a command identity names the program and never the raw command`() {
        val identity = ExecutionRecord.identity("pm list packages --user 0", listOf("--user", "0"))

        assertTrue(identity.startsWith("pm#"))
        assertEquals("pm#".length + 8, identity.length)
        assertFalse("the raw text must never be in the record", identity.contains("packages"))
        assertFalse(identity.contains("--user"))
    }

    @Test
    fun `the same command has the same identity, and arguments change it`() {
        val first = ExecutionRecord.identity("adb shell id")
        val second = ExecutionRecord.identity("adb shell id")
        val withArgument = ExecutionRecord.identity("id", listOf("-u"))

        assertEquals(first, second)
        assertFalse(first == withArgument)
    }

    @Test
    fun `the log keeps a bounded window of the newest records`() {
        val log = ExecutionLog(capacity = 2)

        log.record(record("one"))
        log.record(record("two"))
        log.record(record("three"))

        assertEquals(2, log.size())
        assertEquals(listOf("two", "three"), log.recent(10).map { it.correlationId })
        assertEquals(listOf("three"), log.recent(1).map { it.correlationId })
    }

    @Test
    fun `records can be read per target, which is what a device screen needs`() {
        val log = ExecutionLog()
        log.record(record("a", targetId = "phone-b"))
        log.record(record("b", targetId = "tablet-c"))
        log.record(record("c", targetId = "phone-b"))

        assertEquals(listOf("a", "c"), log.forTarget("phone-b").map { it.correlationId })
        assertTrue(log.forTarget("unknown").isEmpty())
        log.clear()
        assertEquals(0, log.size())
    }

    @Test
    fun `a refused request is recorded as not executed`() {
        assertFalse(record("x", stage = ExecutionStage.REJECTED).executed)
        assertFalse(record("y", stage = ExecutionStage.TRANSPORT_FAILED).executed)
        assertTrue(record("z", stage = ExecutionStage.SUCCEEDED).executed)
        assertTrue(record("v", stage = ExecutionStage.VERIFIED).executed)
    }

    @Test
    fun `a result separates transport, command and verification success`() {
        val succeeded = ExecutionResult(stage = ExecutionStage.SUCCEEDED, exitCode = 0)
        assertTrue(succeeded.ok)
        assertFalse("running is not proving", succeeded.verified)

        val commandFailed = ExecutionResult(stage = ExecutionStage.COMMAND_FAILED, exitCode = 1, message = "no such file")
        assertFalse(commandFailed.ok)
        assertFalse(commandFailed.rejected)

        val transportFailed = ExecutionResult.transportFailed("network is unreachable", errorCode = "NETWORK_UNREACHABLE")
        assertFalse(transportFailed.ok)
        // No command existed, so there is no exit code to report - not a zero.
        assertTrue(transportFailed.exitCode == null)

        assertEquals(ExecutionStage.VERIFIED, succeeded.withVerification(confirmed = true, detail = "the file exists").stage)
        assertEquals(ExecutionStage.SUCCEEDED, succeeded.withVerification(confirmed = false).stage)
        assertEquals(ExecutionStage.TRANSPORT_FAILED, transportFailed.withVerification(confirmed = true).stage)
    }
}
