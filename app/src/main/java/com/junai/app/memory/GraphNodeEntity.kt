package com.junai.app.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * GraphNodeEntity — A single concept/entity in Jun's knowledge graph.
 *
 * Nodes are deduplicated by normalizedName (lowercase, trimmed) so the
 * same concept mentioned in different sentences resolves to one node.
 * Example nodes: "user", "python", "jun ai"
 */
@Entity(tableName = "graph_nodes")
data class GraphNodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,             // display name, e.g. "Python"
    val normalizedName: String,   // lowercase/trimmed key used for lookups, e.g. "python"
    val type: String = "CONCEPT", // USER, LANGUAGE, PROJECT, CONCEPT, FOOD, HOBBY...
    val timestamp: Long = System.currentTimeMillis()
)
