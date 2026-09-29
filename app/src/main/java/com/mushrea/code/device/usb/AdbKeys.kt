package com.mushrea.code.device.usb

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The host RSA identity we present to the other phone (its "Allow USB debugging?" dialog shows
 * this key's fingerprint). Software keys in app-private storage — the same trust model adb
 * itself uses on a workstation. Regenerated only if the file is missing or unreadable.
 */
object AdbKeys {
    private const val KEY_FILE = "usb/adbkey"
    private const val KEY_BITS = 2048

    fun loadOrCreate(context: Context): KeyPair {
        val file = File(context.filesDir, KEY_FILE)
        runCatching { return restored(file) }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_BITS)
        val pair = generator.generateKeyPair()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        }
        return pair
    }

    private fun restored(file: File): KeyPair {
        val privateBytes = Base64.getDecoder().decode(file.readText().trim())
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val crt = privateKey as java.security.interfaces.RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
        return KeyPair(publicKey, privateKey)
    }
}
