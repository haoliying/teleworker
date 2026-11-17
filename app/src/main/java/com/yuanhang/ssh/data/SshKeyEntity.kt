package com.yuanhang.ssh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A stored private key. The material itself is sealed at rest with the same
 * Keystore key used for passwords.
 */
@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    /** "RSA", "ED25519", "ECDSA" */
    val type: String = "RSA",
    /** AES-GCM sealed PEM / OpenSSH private key material. */
    val materialEnc: String,
    val createdAt: Long = System.currentTimeMillis()
)
