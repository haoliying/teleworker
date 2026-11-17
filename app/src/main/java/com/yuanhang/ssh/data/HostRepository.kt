package com.yuanhang.ssh.data

import kotlinx.coroutines.flow.Flow

class HostRepository(private val dao: HostDao) {

    fun observeAll(): Flow<List<HostEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<HostEntity?> = dao.observeById(id)

    fun observeGroups(): Flow<List<String>> = dao.observeGroups()

    fun search(query: String): Flow<List<HostEntity>> =
        if (query.isBlank()) dao.observeAll() else dao.search("%$query%")

    suspend fun get(id: Long): HostEntity? = dao.findById(id)

    suspend fun save(host: HostEntity): Long =
        if (host.id == 0L) dao.insert(host) else {
            dao.update(host)
            host.id
        }

    suspend fun touch(id: Long) = dao.touch(id)

    suspend fun delete(id: Long) = dao.deleteById(id)
}
