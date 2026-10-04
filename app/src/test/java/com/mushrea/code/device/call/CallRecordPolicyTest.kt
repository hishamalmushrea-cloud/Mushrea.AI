package com.mushrea.code.device.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordPolicyTest {
    @Test
    fun `recording starts only while a call is off-hook and the mic is granted`() {
        val allowed =
            CallRecordPolicy.mayStart(
                hasMicrophonePermission = true,
                callState = CallRecordPolicy.STATE_OFFHOOK,
                alreadyRecording = false,
            )
        assertEquals(CallRecordPolicy.Decision.Allow, allowed)
    }

    @Test
    fun `missing microphone permission is refused`() {
        val decision =
            CallRecordPolicy.mayStart(
                hasMicrophonePermission = false,
                callState = CallRecordPolicy.STATE_OFFHOOK,
                alreadyRecording = false,
            )
        assertTrue(decision is CallRecordPolicy.Decision.Refuse)
        assertTrue((decision as CallRecordPolicy.Decision.Refuse).reason.contains("microphone"))
    }

    @Test
    fun `idle and ringing calls are refused`() {
        val idle =
            CallRecordPolicy.mayStart(true, CallRecordPolicy.STATE_IDLE, false) as CallRecordPolicy.Decision.Refuse
        assertTrue(idle.reason.contains("no call is active"))
        val ringing =
            CallRecordPolicy.mayStart(true, CallRecordPolicy.STATE_RINGING, false) as CallRecordPolicy.Decision.Refuse
        assertTrue(ringing.reason.contains("ringing"))
    }

    @Test
    fun `a second start while already recording is refused`() {
        val decision = CallRecordPolicy.mayStart(true, CallRecordPolicy.STATE_OFFHOOK, alreadyRecording = true)
        assertTrue(decision is CallRecordPolicy.Decision.Refuse)
    }

    @Test
    fun `unread phone state is refused rather than guessed`() {
        val decision = CallRecordPolicy.mayStart(true, callState = null, alreadyRecording = false)
        assertTrue(decision is CallRecordPolicy.Decision.Refuse)
    }

    @Test
    fun `seconds are clamped to the documented window`() {
        assertEquals(0, CallRecordPolicy.clampSeconds(0))
        assertEquals(5, CallRecordPolicy.clampSeconds(1))
        assertEquals(1_800, CallRecordPolicy.clampSeconds(9_999))
        assertEquals(60, CallRecordPolicy.clampSeconds(60))
    }
}
