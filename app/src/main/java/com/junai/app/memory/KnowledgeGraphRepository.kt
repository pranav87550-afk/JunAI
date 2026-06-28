package com.junai.app.memory

import android.content.Context
import com.junai.app.AppDatabase
import com.junai.app.learning.LearningEngineV2

/**
 * KnowledgeGraphRepository — Connects GraphRelationExtractor (write path)
 * with KnowledgeGraphDao (storage), plus traversal/answer methods used by
 * ChatIntentHandler's graph-question intercept.
 *
 * Phase 14 (NEW): captureRelation() now REINFORCES an existing edge's
 * confidence (via LearningEngineV2, same curve as semantic facts) when the
 * same relation is stated again, instead of just silently no-op'ing.
 */
class KnowledgeGraphRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).knowledgeGraphDao()

    /** Extracts a relation from text and stores it as nodes+edge, if a pattern matched. */
    suspend fun captureRelation(text: String): GraphEdgeEntity? {
        val extracted = GraphRelationExtractor.extract(text) ?: return null

        val fromNode = findOrCreateNode(extracted.subject, extracted.subjectType)
        val toNode = findOrCreateNode(extracted.objectValue, extracted.objectType)

        val existing = dao.getEdge(fromNode.id, extracted.relation, toNode.id)
        if (existing != null) {
            val reinforced = existing.copy(confidence = LearningEngineV2.reinforce(existing.confidence))
            dao.updateEdge(reinforced)
            return reinforced
        }

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

    /** Walks outgoing edges up to maxHops deep. Returns chains like "user -> likes -> python". */
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

    suspend fun getRelatedConcepts(name: String): List<GraphEdgeEntity> {
        val node = dao.getNodeByName(name.lowercase().trim()) ?: return emptyList()
        return dao.getOutgoingEdges(node.id) + dao.getIncomingEdges(node.id)
    }

    suspend fun getAllNodes(): List<GraphNodeEntity> = dao.getAllNodes()
    suspend fun getAllEdges(): List<GraphEdgeEntity> = dao.getAllEdges()

    suspend fun answerUsedFor(nodeName: String): String? {
        val node = dao.getNodeByName(nodeName.lowercase().trim()) ?: return null
        val edges = dao.getOutgoingByRelation(node.id, "USED_FOR")
        if (edges.isEmpty()) return null
        val targets = edges.mapNotNull { dao.getNodeById(it.toNodeId)?.name }
        if (targets.isEmpty()) return null
        return "${node.name} is used for ${targets.joinToString(", ")} \uD83D\uDD17"
    }

    suspend fun answerHowRelated(nameA: String, nameB: String): String? {
        val chainsA = traceChain(nameA, maxHops = 4)
        chainsA.firstOrNull { it.contains(nameB.lowercase()) }?.let {
            return it.replace("->", "→") + " \uD83D\uDD17"
        }
        val chainsB = traceChain(nameB, maxHops = 4)
        chainsB.firstOrNull { it.contains(nameA.lowercase()) }?.let {
            return it.replace("->", "→") + " \uD83D\uDD17"
        }
        return null
    }

    suspend fun answerAboutConcept(nodeName: String): String? {
        val node = dao.getNodeByName(nodeName.lowercase().trim()) ?: return null
        val related = getRelatedConcepts(nodeName)
        if (related.isEmpty()) return null

        val lines = related.mapNotNull { edge ->
            val isOutgoing = edge.fromNodeId == node.id
            val otherId = if (isOutgoing) edge.toNodeId else edge.fromNodeId
            val otherNode = dao.getNodeById(otherId) ?: return@mapNotNull null
            val relLabel = edge.relation.lowercase().replace("_", " ")
            if (isOutgoing) "${node.name} $relLabel ${otherNode.name}"
            else "${otherNode.name} $relLabel ${node.name}"
        }
        return if (lines.isEmpty()) null else lines.distinct().joinToString(". ") + " \uD83D\uDD17"
    }
}
