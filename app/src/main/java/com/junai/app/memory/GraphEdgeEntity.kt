package com.junai.app.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * GraphEdgeEntity — A directed, labeled connection between two graph nodes.
 *
 * Example: fromNodeId=USER, relation="LIKES", toNodeId=PYTHON
 * Then:    fromNodeId=PYTHON, relation="USED_FOR", toNodeId=JUN_AI
 * Chained together this gives: User -> likes -> Python -> used for -> Jun AI
 */
@Entity(tableName = "graph_edges")
data class GraphEdgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val fromNodeId: Int,
    val relation: String,        // LIKES, DISLIKES, USES, USED_FOR, WORKS_ON, PART_OF, IS_A, HAS
    val toNodeId: Int,
    val confidence: Float = 0.8f,
    val sourceText: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
