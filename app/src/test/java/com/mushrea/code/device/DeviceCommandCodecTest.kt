package com.mushrea.code.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandCodecTest {
    @Test
    fun `parses a full request`() {
        val command =
            DeviceCommandCodec.parseRequest(
                """{"id":"c1","action":"open_app","params":{"app":"youtube"},"ts":1720000000000}""",
            )
        assertNotNull(command)
        assertEquals("c1", command?.id)
        assertEquals("open_app", command?.action)
        assertEquals("youtube", command?.params?.optString("app"))
    }

    @Test
    fun `params default to empty and action is lowercased`() {
        val command = DeviceCommandCodec.parseRequest("""{"id":"c2","action":"READ_SCREEN"}""")
        assertEquals("read_screen", command?.action)
        assertEquals(0, command?.params?.length())
    }

    @Test
    fun `malformed or incomplete requests are rejected`() {
        assertNull(DeviceCommandCodec.parseRequest("not json"))
        assertNull(DeviceCommandCodec.parseRequest("""{"action":"open_app"}"""))
        assertNull(DeviceCommandCodec.parseRequest("""{"id":"c3"}"""))
    }

    @Test
    fun `success payload round trip`() {
        val result = DeviceCommandCodec.success("c1") { put("package", "com.whatsapp") }
        assertEquals("c1", result.optString("id"))
        assertTrue(result.optBoolean("ok"))
        assertEquals("com.whatsapp", result.optJSONObject("result")?.optString("package"))
        assertFalse(result.has("error"))
    }

    @Test
    fun `failure payload carries the error and confirmation flag`() {
        val result = DeviceCommandCodec.failure("c9", "denied by user", needsConfirmation = true)
        assertFalse(result.optBoolean("ok"))
        assertEquals("denied by user", result.optString("error"))
        assertTrue(result.optBoolean("needs_confirmation"))
    }

    @Test
    fun `unknown actions are recognized by the firewall action list`() {
        // The bridge rejects anything outside ALL_ACTIONS before touching the device.
        assertTrue(DeviceActionFirewall.ALL_ACTIONS.contains("open_app"))
        assertTrue(DeviceActionFirewall.ALL_ACTIONS.contains("stop_agent"))
        assertEquals(
            DeviceActionFirewall.ALL_ACTIONS.size,
            DeviceActionFirewall.AUTO_ACTIONS.size +
                setOf(
                    DeviceActionFirewall.ACTION_SHARE_FILE,
                    DeviceActionFirewall.ACTION_DELETE_FILE,
                    DeviceActionFirewall.ACTION_MOVE_FILE,
                    DeviceActionFirewall.ACTION_COPY_FILE,
                    DeviceActionFirewall.ACTION_RENAME_FILE,
                    DeviceActionFirewall.ACTION_CALL_AGENT,
                    DeviceActionFirewall.ACTION_USB_SHELL,
                    DeviceActionFirewall.ACTION_USB_PULL,
                    DeviceActionFirewall.ACTION_USB_PUSH,
                    DeviceActionFirewall.ACTION_USB_TRANSFER_MEDIA,
                    DeviceActionFirewall.ACTION_USB_SCREENSHOT,
                    DeviceActionFirewall.ACTION_USB_INSTALL,
                    DeviceActionFirewall.ACTION_USB_LOGCAT,
                    DeviceActionFirewall.ACTION_USB_SERIAL_SEND,
                    DeviceActionFirewall.ACTION_USB_TCPIP,
                    DeviceActionFirewall.ACTION_TCP_SHELL,
                    DeviceActionFirewall.ACTION_SSH_EXEC,
                    DeviceActionFirewall.ACTION_SSH_DOWNLOAD,
                    DeviceActionFirewall.ACTION_SSH_UPLOAD,
                    DeviceActionFirewall.ACTION_MIRROR_START,
                    DeviceActionFirewall.ACTION_SCRCPY_START,
                    DeviceActionFirewall.ACTION_SCRCPY_STOP,
                    DeviceActionFirewall.ACTION_MTP_DOWNLOAD,
                    DeviceActionFirewall.ACTION_HID_READ,
                    DeviceActionFirewall.ACTION_REMOTE_DOWNLOAD,
                    DeviceActionFirewall.ACTION_HTTP_REQUEST,
                    DeviceActionFirewall.ACTION_WEBSOCKET,
                ).size,
        )
    }

    @Test
    fun `result json is a strict object`() {
        val result = DeviceCommandCodec.failure("x", "boom")
        assertEquals(JSONObject(result.toString()).optString("error"), "boom")
    }
}
