package com.mushrea.code.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiUnlockTest {
    @Test
    fun `the official link is the https Xiaomi page`() {
        assertTrue(XiaomiUnlock.OFFICIAL_URL.startsWith("https://"))
        assertTrue(XiaomiUnlock.OFFICIAL_URL.contains("miui.com"))
    }

    @Test
    fun `a locked device is refused with the official path and the link`() {
        val notice = XiaomiUnlock.lockedNotice("sky")
        assertTrue(notice.contains("sky"))
        assertTrue(notice.contains("LOCKED"))
        assertTrue(notice.lowercase().contains("refused"))
        assertTrue(notice.contains("Mi Unlock"))
        assertTrue("the notice must carry the link: $notice", notice.contains(XiaomiUnlock.OFFICIAL_URL))
    }

    @Test
    fun `an unknown codename still produces the same refusal`() {
        val notice = XiaomiUnlock.lockedNotice(null)
        assertTrue(notice.contains("LOCKED"))
        assertTrue(notice.contains(XiaomiUnlock.OFFICIAL_URL))
        assertEquals(notice, XiaomiUnlock.lockedNotice("   "))
    }

    @Test
    fun `the waiting period is described as real and unshortenable`() {
        val reminder = XiaomiUnlock.waitingPeriodReminder()
        assertTrue(reminder.contains("72h/168h"))
        assertTrue(reminder.contains("must actually elapse"))
        assertTrue(reminder.contains("nothing in this app can shorten it"))
        assertTrue(reminder.contains(XiaomiUnlock.OFFICIAL_URL))
    }
}
