package com.junai.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey
    val question: String,
    val answer: String
)
