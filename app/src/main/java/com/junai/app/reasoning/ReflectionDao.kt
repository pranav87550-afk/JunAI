package com.junai.app.reasoning

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ReflectionDao {

    @Insert
    suspend fun insert(reflection: ReflectionEntity): Long

    @Query("SELECT * FROM reflection_logs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ReflectionEntity?

    @Query("SELECT * FROM reflection_logs WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): ReflectionEntity?

    @Query("SELECT * FROM reflection_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ReflectionEntity>
}
