package net.hwyz.iov.vehicle.ivi.ivai.retrieval

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition

/**
 * Query payloads, candidates and shared models for Tool/Intent RAG and
 * Knowledge RAG (IVI-IVAI-DSN-CR-005).
 */

/** Query passed to a [ToolRetriever]. */
data class ToolRetrievalQuery(
    val text: String,
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val language: String = "zh-CN",
    val excludeToolIds: Set<String> = emptySet()
)

/** Query passed to a [KnowledgeRetriever]. */
data class KnowledgeRetrievalQuery(
    val text: String,
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val language: String = "zh-CN"
)

/** Either a tool or a knowledge retrieval request, carried by a tier decision. */
sealed interface RetrievalQuery {
    data class Tools(val query: ToolRetrievalQuery) : RetrievalQuery
    data class Knowledge(val query: KnowledgeRetrievalQuery) : RetrievalQuery
}

/**
 * Compact, serialization-friendly summary of a tool used for L1 prompt assembly.
 */
@Serializable
data class ToolDefinitionSummary(
    val toolId: String,
    val functionId: String? = null,
    val name: String,
    val description: String,
    val positiveExamples: List<String> = emptyList(),
    val negativeExamples: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val parameterSchema: String = ""
) {
    companion object {
        /** Builds the retrieval/prompt summary from a tool definition (CR-005). */
        fun from(tool: ToolDefinition): ToolDefinitionSummary = ToolDefinitionSummary(
            toolId = tool.toolId,
            functionId = tool.functionId,
            name = tool.name,
            description = tool.description,
            positiveExamples = tool.positiveExamples,
            negativeExamples = tool.negativeExamples,
            synonyms = tool.deterministicRules.flatMap { rule ->
                rule.exactPhrases + rule.synonymPatterns
            }.distinct(),
            parameterSchema = tool.parameterSchema
        )
    }
}

/** A candidate tool recalled by a [ToolRetriever]. */
data class ToolCandidate(
    val toolId: String,
    val score: Double,
    val matchedFields: List<String>,
    val definition: ToolDefinitionSummary
)

/**
 * A knowledge chunk recalled by a [KnowledgeRetriever] (CR-005). Slicing must
 * preserve title / warning / step / table context and never split a safety
 * warning from its corresponding operation steps.
 */
@Serializable
data class KnowledgeChunk(
    val chunkId: String,
    val documentId: String,
    val title: String,
    val sectionPath: List<String>,
    val content: String,
    val vehicleModels: List<String> = listOf("*"),
    val softwareRange: String? = null,
    val language: String = "zh-CN",
    val documentVersion: String = "1.0",
    val score: Double = 0.0
)
