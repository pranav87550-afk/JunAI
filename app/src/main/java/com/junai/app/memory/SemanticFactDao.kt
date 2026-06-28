package com.junai.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SemanticFactDao {

    @Insert
    suspend fun insert(fact: SemanticFactEntity): Long

    @Update
    suspend fun update(fact: SemanticFactEntity)

    @Query("SELECT * FROM semantic_facts WHERE predicate = :predicate ORDER BY timestamp DESC")
    suspend fun getByPredicate(predicate: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts WHERE category = :category AND predicate = :predicate ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByCategoryAndPredicate(category: String, predicate: String): SemanticFactEntity?

    // NEW (Phase 14) — exact-match lookup used to dedup + reinforce instead
    // of inserting a duplicate row when the same fact is stated again.
    @Query("SELECT * FROM semantic_facts WHERE predicate = :predicate AND objectValue = :objectValue AND category = :category LIMIT 1")
    suspend fun getExactFact(predicate: String, objectValue: String, category: String): SemanticFactEntity?

    @Query("SELECT * FROM semantic_facts WHERE objectValue LIKE '%' || :keyword || '%'")
    suspend fun searchByObject(keyword: String): List<SemanticFactEntity>

    @Query("SELECT * FROM semantic_facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<SemanticFactEntity>

    @Query("DELETE FROM semantic_facts WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM semantic_facts WHERE category = :category AND predicate = :predicate")
    suspend fun countByCategoryAndPredicate(category: String, predicate: String): Int
}
