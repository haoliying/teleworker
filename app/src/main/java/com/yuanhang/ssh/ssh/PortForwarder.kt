package com.yuanhang.ssh.ssh

import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Allocates local listeners that tunnel through the SSH connection to a remote
 * endpoint. This is what makes the VNC client able to reach `localhost:<port>`
 * even though the server only exposes VNC on its loopback interface.
 */
class PortForwarder(private val ssh: Session) {

    private val assignedPorts = mutableSetOf<Int>()

    /**
     * Binds [localPort] (or an ephemeral port when 0) and forwards every connection
     * to [remoteHost]:[remotePort] through SSH. Returns the actually bound local port.
     */
    suspend fun open(
        remoteHost: String,
        remotePort: Int,
        localPort: Int = 0
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val bound = if (localPort == 0) {
                ssh.setPortForwardingL(0, remoteHost, remotePort)
            } else {
                ssh.setPortForwardingL(localPort, remoteHost, remotePort)
                localPort
            }
            assignedPorts.add(bound)
            Result.success(bound)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun close(port: Int) = withContext(Dispatchers.IO) {
        try {
            ssh.delPortForwardingL(port)
            assignedPorts.remove(port)
        } catch (t: Throwable) {
            // already gone
        }
    }

    suspend fun closeAll() = withContext(Dispatchers.IO) {
        assignedPorts.toList().forEach { port ->
            try { ssh.delPortForwardingL(port) } catch (t: Throwable) { /* ignore */ }
        }
        assignedPorts.clear()
    }
}
