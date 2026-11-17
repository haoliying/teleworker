package com.yuanhang.ssh.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY favorite DESC, lastUsedAt DESC, name ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun findById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observeById(id: Long): Flow<HostEntity?>

    @Query("SELECT DISTINCT `group` FROM hosts WHERE `group` <> '' ORDER BY `group` ASC")
    fun observeGroups(): Flow<List<String>>

    @Query(
        """
        SELECT * FROM hosts
        WHERE name LIKE :q OR host LIKE :q OR username LIKE :q OR note LIKE :q
        ORDER BY favorite DESC, lastUsedAt DESC, name ASC
        """
    )
    fun search(q: String): Flow<List<HostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HostEntity): Long

    @Update
    suspend fun update(entity: HostEntity)

    @Delete
    suspend fun delete(entity: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE hosts SET lastUsedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
}
