package com.yuanhang.ssh.repository

import com.yuanhang.ssh.data.HostEntity
import com.yuanhang.ssh.data.SshKeyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Convenience pairing of a remembered host with the key it references,
 * used wherever the UI needs both at once.
 */
data class ResolvedHost(
    val host: HostEntity,
    val key: SshKeyEntity?
)

class CombinedHostRepository(
    private val hosts: com.yuanhang.ssh.data.HostRepository,
    private val keys: com.yuanhang.ssh.data.SshKeyRepository
) {
    fun observeAll(): Flow<List<ResolvedHost>> =
        combine(hosts.observeAll(), keys.observeAll()) { hostList, keyList ->
            val byId = keyList.associateBy { it.id }
            hostList.map { ResolvedHost(it, it.keyId?.let { id -> byId[id] }) }
        }

    suspend fun resolve(id: Long): ResolvedHost? {
        val host = hosts.get(id) ?: return null
        val key = host.keyId?.let { keys.get(it) }
        return ResolvedHost(host, key)
    }
}
