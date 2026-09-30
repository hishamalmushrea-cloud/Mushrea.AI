package com.mushrea.code.device.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zipWith(
        name: String,
        entries: Map<String, String>,
    ): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entryName, text) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun otaZip(name: String = "rom.zip"): File =
        zipWith(
            name,
            mapOf(
                "META-INF/com/android/metadata" to
                    "ota-type=AB\npre-device=sky\npost-build=Xiaomi/sky/sky:14/UKQ1/OS2.0.201.0.VMWINXM:user/release-keys\n",
                "payload.bin" to "CrAU-not-really-a-payload",
            ),
        )

    @Test
    fun `a sky ROM matches a sky device`() {
        val result = PayloadGuard().inspect(otaZip(), "sky")
        assertEquals(PayloadGuard.KIND_ZIP, result.kind)
        assertEquals("sky", result.romProduct)
        assertEquals("META-INF/com/android/metadata pre-device", result.romProductSource)
        assertEquals("match", result.verdict)
        assertFalse(result.blocking)
        assertEquals("verified", result.integrity)
        assertEquals("OS2.0.201.0.VMWINXM", result.buildId)
        assertEquals("IN", result.regionCode)
    }

    @Test
    fun `a flourite ROM is refused on a sky device`() {
        val rom =
            zipWith(
                "downloaded.zip",
                mapOf(
                    "META-INF/com/android/metadata" to
                        "ota-type=AB\npre-device=flourite\npost-build=Xiaomi/flourite/flourite:14/UKQ1/OS2.0.101.0.VMWMIXM:user/release-keys\n",
                ),
            )
        val result = PayloadGuard().inspect(rom, "sky")
        assertEquals("flourite", result.romProduct)
        assertEquals("mismatch", result.verdict)
        assertTrue(result.blocking)
        assertTrue(result.notes.first().contains("cannot prove the file is genuine"))
    }

    @Test
    fun `a device-less archive is unverified rather than silently accepted`() {
        val rom = zipWith("plain.zip", mapOf("system/build.prop" to "ro.product.device=sky"))
        val result = PayloadGuard().inspect(rom, "sky")
        assertNull(result.romProduct)
        assertEquals("unverified", result.verdict)
        assertFalse(result.blocking)
    }

    @Test
    fun `an invalid device product also leaves the verdict unverified`() {
        val result = PayloadGuard().inspect(otaZip(), "   ")
        assertEquals("unverified", result.verdict)
        assertFalse(result.blocking)
    }

    @Test
    fun `a truncated zip fails integrity and blocks`() {
        val full = otaZip().readBytes()
        val truncated = File(temp.root, "half.zip")
        truncated.writeBytes(full.copyOf(full.size / 3))
        val result = PayloadGuard().inspect(truncated, "sky")
        assertEquals("failed", result.integrity)
        assertTrue(result.blocking)
        assertTrue(result.checks.any { it.name == "zip-eocd" && !it.ok })
    }

    @Test
    fun `a missing file blocks with a stated reason`() {
        val result = PayloadGuard().inspect(File(temp.root, "gone.zip"), "sky")
        assertEquals("missing", result.kind)
        assertTrue(result.blocking)
        assertTrue(result.checks.any { !it.ok })
    }

    @Test
    fun `a fastboot tgz is recognised by its top-level folder`() {
        val tgz = File(temp.root, "downloaded-package.tgz")
        writeTarGz(
            tgz,
            listOf("sky_global_images_OS2.0.201.0.VMWINXM_14.0/flash_all.sh" to "#!/bin/sh\n"),
        )
        val result = PayloadGuard().inspect(tgz, "sky")
        assertEquals(PayloadGuard.KIND_TGZ, result.kind)
        assertEquals("sky", result.romProduct)
        assertEquals("match", result.verdict)
        assertFalse(result.blocking)
        assertEquals("verified", result.integrity)
    }

    @Test
    fun `a flourite tgz is refused even when the file name is neutral`() {
        val tgz = File(temp.root, "downloaded-package.tgz")
        writeTarGz(
            tgz,
            listOf("flourite_eea_images_OS2.0.101.0.VMWEUXM_14.0/flash_all.sh" to "#!/bin/sh\n"),
        )
        val result = PayloadGuard().inspect(tgz, "sky")
        assertEquals("flourite", result.romProduct)
        assertEquals("mismatch", result.verdict)
        assertTrue(result.blocking)
    }

    @Test
    fun `a raw image is neither silently accepted nor blocked`() {
        val image = File(temp.root, "boot.img")
        image.writeBytes(ByteArray(64) { 0x11 })
        val result = PayloadGuard().inspect(image, "sky")
        assertEquals(PayloadGuard.KIND_IMAGE, result.kind)
        assertEquals("not-applicable", result.integrity)
        assertEquals("unverified", result.verdict)
        assertFalse(result.blocking)
    }

    @Test
    fun `region codes are read from the Xiaomi build id`() {
        val guard = PayloadGuard()
        assertEquals("India" to "IN", guard.regionOf("OS2.0.201.0.VMWINXM"))
        assertEquals("Global" to "MI", guard.regionOf("OS2.0.201.0.VMWMIXM"))
        assertEquals("China" to "CN", guard.regionOf("OS2.0.201.0.VMWCNXM"))
        assertTrue(guard.regionOf("OS2.0.201.0.VMWEUXM")?.first?.contains("EU") == true)
        assertNull(guard.regionOf(null))
        assertNull(guard.regionOf("not-a-build-id"))
        assertEquals("OS2.0.201.0.VMWINXM", guard.buildIdFrom("Xiaomi/sky/sky:14/UKQ1/OS2.0.201.0.VMWINXM:user/release-keys"))
        assertNull(guard.buildIdFrom("nothing here"))
    }

    /** Minimal ustar writer: the guard only reads the name and the size field. */
    private fun writeTarGz(
        file: File,
        entries: List<Pair<String, String>>,
    ) {
        val raw = ByteArrayOutputStream()
        entries.forEach { (name, body) ->
            val bytes = body.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 99))
            "0000644\u0000".toByteArray().copyInto(header, 100)
            "0001750\u0000".toByteArray().copyInto(header, 108)
            "0001750\u0000".toByteArray().copyInto(header, 116)
            String.format("%011o", bytes.size).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            "        ".toByteArray().copyInto(header, 148)
            header[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            raw.write(header)
            raw.write(bytes)
            val padding = (512 - (bytes.size % 512)) % 512
            if (padding > 0) raw.write(ByteArray(padding))
        }
        raw.write(ByteArray(1024))
        GZIPOutputStream(file.outputStream()).use { it.write(raw.toByteArray()) }
    }
}
