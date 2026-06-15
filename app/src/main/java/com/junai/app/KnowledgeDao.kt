package com.junai.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KnowledgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(knowledge: KnowledgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(knowledgeList: List<KnowledgeEntity>)

    @Query("SELECT answer FROM knowledge WHERE question = :question LIMIT 1")
    suspend fun getAnswer(question: String): String?

    @Query("SELECT COUNT(*) FROM knowledge")
    suspend fun getCount(): Int

    @Query("DELETE FROM knowledge")
    suspend fun deleteAll()

    @Query("SELECT * FROM knowledge")
    suspend fun getAll(): List<KnowledgeEntity>

    @Query("DELETE FROM knowledge WHERE question = :question")
    suspend fun delete(question: String)
}
