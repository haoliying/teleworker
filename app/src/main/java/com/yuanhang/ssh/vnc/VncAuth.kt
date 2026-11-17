package com.yuanhang.ssh.vnc

import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC's classic "DES challenge-response" authentication.
 *
 * The password is truncated to 8 bytes and every byte has its bit order reversed
 * (this is the historical quirk that makes DES implementations interoperable with
 * the original AT&T reference code). The 16 byte challenge is then DES-encrypted
 * in ECB mode as two independent 8 byte blocks.
 */
object VncAuth {

    fun encryptChallenge(challenge: ByteArray, password: String): ByteArray {
        val key = buildKey(password)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val response = ByteArray(16)
        cipher.doFinal(challenge, 0, 8, response, 0)
        cipher.doFinal(challenge, 8, 8, response, 8)
        return response
    }

    private fun buildKey(password: String): Key {
        val raw = ByteArray(8)
        val bytes = password.toByteArray(Charsets.ISO_8859_1)
        for (i in raw.indices) {
            raw[i] = if (i < bytes.size) reverseBits(bytes[i]) else 0
        }
        return SecretKeySpec(raw, "DES")
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        repeat(8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }
}
