package com.yuanhang.ssh.data

import kotlinx.coroutines.flow.Flow

class SshKeyRepository(private val dao: SshKeyDao) {

    fun observeAll(): Flow<List<SshKeyEntity>> = dao.observeAll()

    suspend fun get(id: Long): SshKeyEntity? = dao.findById(id)

    suspend fun save(key: SshKeyEntity): Long =
        if (key.id == 0L) dao.insert(key) else {
            dao.update(key)
            key.id
        }

    suspend fun delete(id: Long) = dao.deleteById(id)
}
