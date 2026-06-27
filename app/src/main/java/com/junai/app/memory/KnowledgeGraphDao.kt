package com.junai.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface KnowledgeGraphDao {

    // ── Nodes ──
    @Insert
    suspend fun insertNode(node: GraphNodeEntity): Long

    @Query("SELECT * FROM graph_nodes WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getNodeByName(normalizedName: String): GraphNodeEntity?

    @Query("SELECT * FROM graph_nodes WHERE id = :id")
    suspend fun getNodeById(id: Int): GraphNodeEntity?

    @Query("SELECT * FROM graph_nodes ORDER BY timestamp DESC")
    suspend fun getAllNodes(): List<GraphNodeEntity>

    // ── Edges ──
    @Insert
    suspend fun insertEdge(edge: GraphEdgeEntity): Long

    @Query("SELECT * FROM graph_edges WHERE fromNodeId = :nodeId AND relation = :relation AND toNodeId = :toNodeId LIMIT 1")
    suspend fun getEdge(nodeId: Int, relation: String, toNodeId: Int): GraphEdgeEntity?

    @Query("SELECT * FROM graph_edges WHERE fromNodeId = :nodeId ORDER BY timestamp DESC")
    suspend fun getOutgoingEdges(nodeId: Int): List<GraphEdgeEntity>

    @Query("SELECT * FROM graph_edges WHERE toNodeId = :nodeId ORDER BY timestamp DESC")
    suspend fun getIncomingEdges(nodeId: Int): List<GraphEdgeEntity>

    @Query("SELECT * FROM graph_edges WHERE fromNodeId = :nodeId AND relation = :relation ORDER BY timestamp DESC")
    suspend fun getOutgoingByRelation(nodeId: Int, relation: String): List<GraphEdgeEntity>

    @Query("SELECT * FROM graph_edges ORDER BY timestamp DESC")
    suspend fun getAllEdges(): List<GraphEdgeEntity>

    @Query("DELETE FROM graph_edges WHERE id = :id")
    suspend fun deleteEdge(id: Int)
}
