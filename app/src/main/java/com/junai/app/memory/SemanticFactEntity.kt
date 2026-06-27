package com.junai.app.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SemanticFactEntity — A single subject-predicate-object relationship.
 *
 * Lets Jun answer questions about concepts instead of needing the exact
 * original sentence.
 * Example: "I like Python" -> subject=USER, predicate=LIKES, objectValue=Python, category=LANGUAGE
 * Later:   "What language do I enjoy?" -> resolved via category+predicate lookup -> Python
 */
@Entity(tableName = "semantic_facts")
data class SemanticFactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val subject: String = "USER",
    val predicate: String,            // LIKES, DISLIKES, PREFERS, USES, WORKS_ON, IS, HAS, WANTS
    val objectValue: String,          // the concept itself, e.g. "Python"
    val category: String = "GENERAL", // LANGUAGE, FOOD, COLOR, HOBBY, MOVIE_GENRE, GENERAL
    val confidence: Float = 0.8f,
    val sourceText: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
