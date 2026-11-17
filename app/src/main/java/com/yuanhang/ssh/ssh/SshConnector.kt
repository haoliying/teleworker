package com.yuanhang.ssh.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.SshKeyEntity
import com.yuanhang.ssh.util.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Establishes authenticated JSch sessions for a remembered [HostEntity].
 *
 * Host keys follow a trust-on-first-use policy persisted in the app private
 * `known_hosts` file, mirroring OpenSSH behaviour.
 */
class SshConnector(private val context: Context) {

    private val knownHostsFile: File
        get() = File(context.filesDir, "known_hosts").also {
            if (!it.exists()) it.createNewFile()
        }

    private fun freshJSch(): JSch {
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        return jsch
    }

    suspend fun connect(host: HostEntity, key: SshKeyEntity? = null): Session =
        withContext(Dispatchers.IO) {
            val jsch = freshJSch()

            key?.let { k ->
                val pem = Crypto.unseal(k.materialEnc)
                val passphrase = Crypto.unseal(host.passphraseEnc)
                if (!pem.isNullOrBlank()) {
                    jsch.addIdentity(
                        k.alias,
                        pem.toByteArray(Charsets.UTF_8),
                        null as ByteArray?,
                        passphrase?.toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val session = jsch.getSession(host.username, host.host, host.port)

            val password = Crypto.unseal(host.passwordEnc)
            session.userInfo = object : UserInfo {
                override fun getPassphrase(): String = passphraseOf()
                override fun getPassword(): String = password ?: ""
                override fun promptPassword(message: String): Boolean = password != null
                override fun promptPassphrase(message: String): Boolean = true
                override fun promptYesNo(message: String): Boolean = true
                override fun showMessage(message: String) = Unit

                private fun passphraseOf(): String = Crypto.unseal(host.passphraseEnc) ?: ""
            }

            // Modern cipher / kex preferences – many hardened servers refuse the
            // legacy defaults outright.
            session.setConfig("PreferredAuthentications", preferredAuth(host))
            session.setConfig("StrictHostKeyChecking", "ask")
            session.setConfig("HashKnownHosts", "yes")
            session.setConfig("kex", preferredKex)
            session.setConfig("server_host_key", preferredHostKey)
            session.setConfig("cipher.s2c", preferredCiphers)
            session.setConfig("cipher.c2s", preferredCiphers)
            session.setConfig("mac.s2c", preferredMacs)
            session.setConfig("mac.c2s", preferredMacs)
            session.setConfig("compression.s2c", "none")
            session.setConfig("compression.c2s", "none")
            session.timeout = CONNECT_TIMEOUT_MS
            session.setServerAliveInterval(maxOf(1000, host.keepAliveSeconds * 1000))
            session.setServerAliveCountMax(3)

            session.connect(CONNECT_TIMEOUT_MS)
            session
        }

    private fun preferredAuth(host: HostEntity): String =
        if (host.authType == com.yuanhang.ssh.data.AuthType.KEY.name) {
            "publickey,keyboard-interactive,password"
        } else {
            "password,keyboard-interactive,publickey"
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 25_000

        const val preferredKex =
            "curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group16-sha512," +
                "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256,ext-info-c"

        const val preferredHostKey =
            "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ecdsa-sha2-nistp256,ssh-rsa"

        const val preferredCiphers =
            "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com," +
                "aes256-ctr,aes192-ctr,aes128-ctr"

        const val preferredMacs =
            "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com," +
                "umac-128-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
    }

    class SshException(message: String, cause: Throwable? = null) : IOException(message, cause)
}
