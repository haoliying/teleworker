package com.yuanhang.ssh.session

import android.content.Context
import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.SshKeyEntity
import com.yuanhang.ssh.rdp.RdpClient
import com.yuanhang.ssh.service.StartupService
import com.yuanhang.ssh.ssh.SftpSession
import com.yuanhang.ssh.ssh.ShellSession
import com.yuanhang.ssh.ssh.SshConnector
import com.yuanhang.ssh.vnc.VncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every live connection so that rotating the phone or navigating between
 * screens never tears a session down.
 */
object SessionManager {

    private lateinit var appContext: Context
    private val connector: SshConnector by lazy { SshConnector(appContext) }

    private val shellSessions = ConcurrentHashMap<String, ShellSession>()
    private val sftpSessions = ConcurrentHashMap<String, SftpSession>()
    private val vncClients = ConcurrentHashMap<String, VncClient>()
    private val rdpClients = ConcurrentHashMap<String, RdpClient>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val shellCount: Int get() = shellSessions.size

    // -------------------------------------------------------------- shell

    suspend fun openShell(
        host: HostEntity,
        key: SshKeyEntity?,
        rows: Int = 32,
        cols: Int = 100
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = connector.connect(host, key)
            val session = ShellSession(
                sessionId = shellKey(host.id),
                displayName = host.name,
                ssh = ssh,
                rows = rows,
                cols = cols
            )
            session.start()
            shellSessions[shellKey(host.id)] = session
            afterChange()
            session
        }
    }

    fun getShell(hostId: Long): ShellSession? = shellSessions[shellKey(hostId)]

    fun hasShell(hostId: Long): Boolean = shellSessions.containsKey(shellKey(hostId))

    // --------------------------------------------------------------- sftp

    suspend fun openSftp(
        host: HostEntity,
        key: SshKeyEntity?
    ): Result<SftpSession> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = connector.connect(host, key)
            val session = SftpSession(sftpKey(host.id), host.name, ssh)
            session.open()
            sftpSessions[sftpKey(host.id)] = session
            afterChange()
            session
        }
    }

    fun getSftp(hostId: Long): SftpSession? = sftpSessions[sftpKey(hostId)]

    /** Terminal SFTP for one host. */
    fun closeSftp(hostId: Long) {
        sftpSessions.remove(sftpKey(hostId))?.close()
        afterChange()
    }

    // --------------------------------------------------------------- vnc

    /**
     * Open a VNC session.
     *
     * This is a plain, direct connection: the phone dials the desktop server itself, with
     * no SSH session and no port forward in between. An empty configured address falls
     * back to the host's own address, which is the usual case.
     */
    suspend fun openVnc(
        host: HostEntity,
        password: String?,
        listener: VncClient.Listener
    ): Result<VncClient> = withContext(Dispatchers.IO) {
        runCatching {
            val client = VncClient(
                host = host.host,
                port = host.port.takeIf { it > 0 } ?: 5900,
                password = password,
                listener = listener
            )
            vncClients[vncKey(host.id)] = client
            client.connect()
            afterChange()
            client
        }
    }

    fun getVnc(hostId: Long): VncClient? = vncClients[vncKey(hostId)]

    fun closeVnc(hostId: Long) {
        vncClients.remove(vncKey(hostId))?.disconnect()
        afterChange()
    }

    // ----------------------------------------------------------------- rdp

    /**
     * Open a Remote Desktop session, dialled directly.
     *
     * No SSH tunnel is involved, so the Windows machine has to be reachable from the phone
     * (and has to accept non-NLA logons, since only TLS security is implemented).
     */
    suspend fun openRdp(
        host: HostEntity,
        domain: String,
        username: String,
        password: String,
        listener: RdpClient.Listener
    ): Result<RdpClient> = withContext(Dispatchers.IO) {
        runCatching {
            val client = RdpClient(
                host = host.host,
                port = host.port.takeIf { it > 0 } ?: 3389,
                username = username,
                password = password,
                domain = domain,
                desktopWidth = host.rdpWidth.takeIf { it > 0 } ?: 1280,
                desktopHeight = host.rdpHeight.takeIf { it > 0 } ?: 800
            )
            client.listener = listener
            rdpClients[rdpKey(host.id)] = client
            client.connect()
            afterChange()
            client
        }
    }

    fun getRdp(hostId: Long): RdpClient? = rdpClients[rdpKey(hostId)]

    fun closeRdp(hostId: Long) {
        rdpClients.remove(rdpKey(hostId))?.disconnect()
        afterChange()
    }

    // ------------------------------------------------------------- lifecycle

    private fun shellKey(hostId: Long) = "shell:$hostId"
    private fun sftpKey(hostId: Long) = "sftp:$hostId"
    private fun vncKey(hostId: Long) = "vnc:$hostId"
    private fun rdpKey(hostId: Long) = "rdp:$hostId"

    private fun activeCount(): Int =
        shellSessions.size + sftpSessions.size + vncClients.size + rdpClients.size

    private fun afterChange() {
        val count = activeCount()
        if (count == 0) {
            StartupService.stop(appContext)
        } else {
            StartupService.start(appContext, count)
        }
    }

    /** Drop everything associated with a host (used when it is deleted). */
    fun closeAll(hostId: Long) {
        shellSessions.remove(shellKey(hostId))?.close()
        closeSftp(hostId)
        closeVnc(hostId)
        closeRdp(hostId)
        afterChange()
    }

    fun closeShell(hostId: Long) {
        shellSessions.remove(shellKey(hostId))?.close()
        afterChange()
    }

    fun closeAll() {
        shellSessions.values.forEach { it.close() }
        sftpSessions.values.forEach { it.close() }
        vncClients.values.forEach { it.disconnect() }
        rdpClients.values.forEach { it.disconnect() }
        shellSessions.clear()
        sftpSessions.clear()
        vncClients.clear()
        rdpClients.clear()
        afterChange()
    }
}
