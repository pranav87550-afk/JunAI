package com.junai.app.memory

import android.content.Context
import com.junai.app.AppDatabase

/**
 * KnowledgeGraphRepository — Connects GraphRelationExtractor (write path)
 * with KnowledgeGraphDao (storage), and provides traversal so multiple
 * separate facts chain into one connected path.
 *
 * Example:
 *  captureRelation("I like Python")              -> user -LIKES-> python
 *  captureRelation("Python is used for Jun AI")   -> python -USED_FOR-> jun ai
 *  traceChain("user")  -> ["user -> likes -> python -> used for -> jun ai"]
 */
class KnowledgeGraphRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).knowledgeGraphDao()

    /** Extracts a relation from text and stores it as nodes+edge, if a pattern matched. */
    suspend fun captureRelation(text: String): GraphEdgeEntity? {
        val extracted = GraphRelationExtractor.extract(text) ?: return null

        val fromNode = findOrCreateNode(extracted.subject, extracted.subjectType)
        val toNode = findOrCreateNode(extracted.objectValue, extracted.objectType)

        // Avoid duplicate edges for the same fact
        val existing = dao.getEdge(fromNode.id, extracted.relation, toNode.id)
        if (existing != null) return existing

        val edge = GraphEdgeEntity(
            fromNodeId = fromNode.id,
            relation = extracted.relation,
            toNodeId = toNode.id,
            confidence = extracted.confidence,
            sourceText = text
        )
        val id = dao.insertEdge(edge)
        return edge.copy(id = id.toInt())
    }

    private suspend fun findOrCreateNode(name: String, type: String): GraphNodeEntity {
        val normalized = name.lowercase().trim()
        val existing = dao.getNodeByName(normalized)
        if (existing != null) return existing

        val node = GraphNodeEntity(name = name, normalizedName = normalized, type = type)
        val id = dao.insertNode(node)
        return node.copy(id = id.toInt())
    }

    /**
     * Walks the graph from a starting node, following outgoing edges up to
     * maxHops deep. Returns human-readable chains, e.g.
     * "user -> likes -> python -> used for -> jun ai"
     */
    suspend fun traceChain(startName: String, maxHops: Int = 3): List<String> {
        val startNode = dao.getNodeByName(startName.lowercase().trim()) ?: return emptyList()
        val chains = mutableListOf<String>()
        walk(startNode, listOf(startNode.name), maxHops, chains)
        return chains
    }

    private suspend fun walk(node: GraphNodeEntity, pathSoFar: List<String>, hopsLeft: Int, results: MutableList<String>) {
        val edges = dao.getOutgoingEdges(node.id)
        if (edges.isEmpty() || hopsLeft == 0) {
            if (pathSoFar.size > 1) results.add(pathSoFar.joinToString(" -> "))
            return
        }
        for (edge in edges) {
            val nextNode = dao.getNodeById(edge.toNodeId) ?: continue
            val relationLabel = edge.relation.lowercase().replace("_", " ")
            walk(nextNode, pathSoFar + relationLabel + nextNode.name, hopsLeft - 1, results)
        }
    }

    /** All direct connections (in + out) for a concept — used to answer "how are X and Y related". */
    suspend fun getRelatedConcepts(name: String): List<GraphEdgeEntity> {
        val node = dao.getNodeByName(name.lowercase().trim()) ?: return emptyList()
        return dao.getOutgoingEdges(node.id) + dao.getIncomingEdges(node.id)
    }

    suspend fun getAllNodes(): List<GraphNodeEntity> = dao.getAllNodes()
    suspend fun getAllEdges(): List<GraphEdgeEntity> = dao.getAllEdges()
}
