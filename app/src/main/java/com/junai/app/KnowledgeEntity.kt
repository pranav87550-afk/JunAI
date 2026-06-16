package com.junai.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey
    val question: String,
    val answer: String,
    val aliases: String = "",
    val category: String = "",
    val timesAsked: Int = 0,
    val confidence: Float = 1.0f
)
