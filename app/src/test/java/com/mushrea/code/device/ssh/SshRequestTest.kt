package com.mushrea.code.device.ssh

import com.mushrea.code.device.usb.AdbException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRequestTest {
    @Test
    fun `password credentials parse with the default port`() {
        val credentials =
            SshRequest.credentials(
                JSONObject().put("host", "box.local").put("username", "root").put("password", "secret"),
            )
        assertEquals("box.local", credentials.host)
        assertEquals(22, credentials.port)
        assertEquals("root", credentials.username)
        assertEquals("secret", credentials.password)
        assertNull(credentials.privateKeyPath)
    }

    @Test
    fun `a private key is enough without a password`() {
        val credentials =
            SshRequest.credentials(
                JSONObject()
                    .put("host", "10.0.0.8")
                    .put("port", 2222)
                    .put("username", "deploy")
                    .put("private_key", "/sdcard/id_ed25519"),
            )
        assertEquals(2222, credentials.port)
        assertEquals("/sdcard/id_ed25519", credentials.privateKeyPath)
        assertNull(credentials.password)
    }

    @Test
    fun `missing host user or proof is refused`() {
        assertTrue(
            runCatching { SshRequest.credentials(JSONObject().put("username", "root").put("password", "x")) }
                .exceptionOrNull() is AdbException,
        )
        assertTrue(
            runCatching { SshRequest.credentials(JSONObject().put("host", "box").put("password", "x")) }
                .exceptionOrNull() is AdbException,
        )
        assertTrue(
            runCatching { SshRequest.credentials(JSONObject().put("host", "box").put("username", "root")) }
                .exceptionOrNull() is AdbException,
        )
    }

    @Test
    fun `remote path helpers`() {
        assertEquals("notes.txt", SshRequest.remoteFileName("/var/data/notes.txt"))
        assertEquals("download", SshRequest.remoteFileName("/"))
        assertEquals("./photo.jpg", SshRequest.remoteUploadPath(".", "photo.jpg"))
        assertEquals("/inbox/photo.jpg", SshRequest.remoteUploadPath("/inbox", "photo.jpg"))
    }
}
