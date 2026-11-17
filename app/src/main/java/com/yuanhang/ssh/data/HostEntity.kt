package com.yuanhang.ssh.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A remembered SSH server.
 *
 * Secrets ([passwordEnc], [passphraseEnc]) are never stored in clear text – they are
 * sealed with an Android Keystore backed AES-GCM key (see [com.yuanhang.ssh.util.Crypto]).
 */
/**
 * What kind of connection a remembered host represents.
 *
 * A host entry is created for exactly one protocol, so the form only asks for the fields
 * that protocol actually needs – an SFTP-only entry never shows a startup command, and a
 * VNC entry never asks for a username or an SSH key.
 */
enum class HostType(
    val label: String,
    val defaultPort: Int,
    /** True when the protocol rides on SSH and therefore needs an account + credentials. */
    val usesSshAuth: Boolean
) {
    SSH("SSH 终端", 22, true),
    SFTP("SFTP 文件", 22, true),
    VNC("VNC 桌面", 5900, false),
    RDP("RDP 桌面", 3389, false);

    companion object {
        fun of(name: String?): HostType =
            entries.find { it.name == name } ?: SSH
    }
}

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** One of [HostType]; decides which form is shown and which screen opens on connect. */
    val type: String = HostType.SSH.name,

    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,

    /** "PASSWORD" or "KEY" */
    val authType: String = AuthType.PASSWORD.name,

    /** AES-GCM sealed password, only present for [AuthType.PASSWORD]. */
    val passwordEnc: String? = null,

    /** Reference into the `ssh_keys` table, only meaningful for [AuthType.KEY]. */
    val keyId: Long? = null,

    /** AES-GCM sealed key passphrase (may be null for keys without one). */
    val passphraseEnc: String? = null,

    /** Initial directory opened by SFTP after connecting. */
    val remotePath: String = "",

    /** Command pushed to the shell right after login. */
    val startupCommand: String = "",

    /** Free-form grouping used to cluster the host list. */
    val group: String = "",

    val note: String = "",
    val favorite: Boolean = false,

    val fontSize: Int = 13,
    val charset: String = "UTF-8",
    val keepAliveSeconds: Int = 30,

    /** AES-GCM sealed VNC password (only for [HostType.VNC]). */
    val vncPasswordEnc: String? = null,

    /**
     * Old "also expose a desktop on this SSH host" switches.
     *
     * Superseded by [type]: a desktop is now its own host entry. Kept so the existing
     * table schema stays valid – Room would reject a schema that lost columns.
     */
    val vncEnabled: Boolean = false,
    val vncRemoteHost: String = "",
    val vncRemotePort: Int = 5900,

    /** AES-GCM sealed RDP password (only for [HostType.RDP]). */
    val rdpPasswordEnc: String? = null,

    val rdpEnabled: Boolean = false,
    val rdpRemoteHost: String = "",
    val rdpRemotePort: Int = 3389,

    /** Windows domain to log on to; empty means the machine's local accounts. */
    val rdpDomain: String = "",

    /** Requested desktop size. The server may hand back a different one. */
    val rdpWidth: Int = 1280,
    val rdpHeight: Int = 800,

    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
)

enum class AuthType { PASSWORD, KEY }
