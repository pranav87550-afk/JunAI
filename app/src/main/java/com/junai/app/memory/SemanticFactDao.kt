package com.junai.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SemanticFactDao {

    @Insert
    suspend fun insert(fact: SemanticFactEntity): Long

    @Query("SELECT * FROM semantic_facts WHERE predicate = :predicate ORDER BY timestamp DESC")
    suspend fun getByPredicate(predicate: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts WHERE category = :category AND predicate = :predicate ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByCategoryAndPredicate(category: String, predicate: String): SemanticFactEntity?

    @Query("SELECT * FROM semantic_facts WHERE objectValue LIKE '%' || :keyword || '%'")
    suspend fun searchByObject(keyword: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<SemanticFactEntity>

    @Query("DELETE FROM semantic_facts WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM semantic_facts WHERE category = :category AND predicate = :predicate")
    suspend fun countByCategoryAndPredicate(category: String, predicate: String): Int
}
