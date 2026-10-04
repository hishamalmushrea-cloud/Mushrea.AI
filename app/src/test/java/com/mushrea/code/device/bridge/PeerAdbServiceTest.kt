package com.mushrea.code.device.bridge

import com.mushrea.code.core.peer.PeerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The service types, the serial adb will use, and the two pieces of adb output we have to read. */
class PeerAdbServiceTest {
    @Test
    fun `the three service types are the ones AOSP documents`() {
        assertEquals("_adb-tls-pairing._tcp", PeerAdbServiceType.PAIRING.dnsType)
        assertEquals("_adb-tls-connect._tcp", PeerAdbServiceType.CONNECT.dnsType)
        assertEquals("_adb._tcp", PeerAdbServiceType.LEGACY.dnsType)
    }

    @Test
    fun `the predicted serial is the instance name plus the service type`() {
        val service =
            PeerAdbService(
                type = PeerAdbServiceType.CONNECT,
                instanceName = "adb-43081FDAS000VS-QXjCrW",
                host = "192.168.1.20",
                port = 37099,
            )

        assertEquals("adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp", service.adbSerial)
    }

    @Test
    fun `matching the pairing instance is case-insensitive and exact`() {
        val service = PeerAdbService(PeerAdbServiceType.PAIRING, "studio-AbcDef1234", "10.0.0.5", 37123)

        assertTrue(service.matches("studio-abcdef1234"))
        // The phone's own six-digit pairing server is a *different* instance: pairing against it
        // fails with a protocol fault, so this must never match.
        assertFalse(service.matches("studio-zzzzzzzzzz"))
        assertFalse(service.matches("adb-43081FDAS000VS-QXjCrW"))
    }

    @Test
    fun `adb devices output is parsed down to serial, state and properties`() {
        val output =
            """
            List of devices attached
            192.168.1.20:37099      device product:sky model:Pixel_6a device:bluejay transport_id:3
            adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp   offline
            * daemon not running; starting now at tcp:5037
            * daemon started successfully

            """.trimIndent()

        val devices = AdbOutputParser.devices(output)

        assertEquals(2, devices.size)
        assertEquals("192.168.1.20:37099", devices[0].serial)
        assertEquals("device", devices[0].state)
        assertTrue(devices[0].usable)
        assertEquals("Pixel_6a", devices[0].props["model"])
        assertFalse(devices[1].usable)
    }

    @Test
    fun `pairing and connecting are recognised from adb's own wording`() {
        assertTrue(AdbOutputParser.pairedSuccessfully("Successfully paired to 192.168.1.20:37123 [guid=adb-x]"))
        assertFalse(AdbOutputParser.pairedSuccessfully("Failed: Wrong password"))
        assertTrue(AdbOutputParser.connected("connected to 192.168.1.20:37099"))
        assertTrue(AdbOutputParser.connected("already connected to 192.168.1.20:37099"))
        assertFalse(AdbOutputParser.connected("failed to connect to '10.0.0.9:5555'"))
    }

    @Test
    fun `a known device is recognised by its announcement`() {
        val known = PeerDevice(serial = "adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp", instanceName = "adb-43081FDAS000VS-QXjCrW")

        assertTrue(
            AdbOutputParser.serialMatches(
                known,
                PeerAdbService(PeerAdbServiceType.CONNECT, "adb-43081FDAS000VS-QXjCrW", "192.168.1.20", 37099),
            ),
        )
        assertFalse(
            AdbOutputParser.serialMatches(
                known,
                PeerAdbService(PeerAdbServiceType.CONNECT, "adb-other-device-token", "192.168.1.21", 37099),
            ),
        )
    }

    @Test
    fun `the serial of a fresh connection is resolved from the session, not from luck`() {
        val devices =
            listOf(
                AdbDeviceLine("192.168.1.20:37099", "device", emptyMap()),
                AdbDeviceLine("adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp", "device", emptyMap()),
            )

        assertEquals(
            "adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp",
            AdbOutputParser.resolveSerial(devices, knownSerials = emptySet(), preferredInstance = "adb-43081FDAS000VS-QXjCrW"),
        )
    }

    @Test
    fun `two new devices and no session mean no answer rather than a guess`() {
        val devices =
            listOf(
                AdbDeviceLine("adb-one._adb-tls-connect._tcp", "device", emptyMap()),
                AdbDeviceLine("adb-two._adb-tls-connect._tcp", "device", emptyMap()),
            )

        assertNull(AdbOutputParser.resolveSerial(devices, knownSerials = emptySet(), preferredInstance = null))
        // ... while exactly one new device is an answer.
        assertEquals(
            "adb-two._adb-tls-connect._tcp",
            AdbOutputParser.resolveSerial(devices.drop(1), knownSerials = emptySet(), preferredInstance = null),
        )
    }
}
