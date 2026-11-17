package com.yuanhang.ssh.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals / unseals SSH secrets.
 *
 * Uses an AES-256-GCM key that lives inside the Android Keystore, so the raw key
 * material never enters the process heap. Each ciphertext carries its 12 byte IV
 * prefixed to the GCM tag, encoded as `<iv>:<payload>` in Base64.
 */
object Crypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "yuanhang_ssh_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** Returns null for blank input so that "no secret" stays null in the database. */
    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(body, 0, packed, iv.size, body.size)
        return android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP)
    }

    fun unseal(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        return try {
            val packed = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val body = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
