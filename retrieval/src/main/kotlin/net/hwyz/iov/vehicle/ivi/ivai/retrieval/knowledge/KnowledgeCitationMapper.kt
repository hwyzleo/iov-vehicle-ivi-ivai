package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk

/** Source citation metadata carried by a Knowledge answer (CR-005), used for UI
 * source display and audit. */
@Serializable
data class KnowledgeCitation(
    val documentId: String,
    val title: String,
    val sectionPath: List<String>,
    val version: String
)

/**
 * Maps retrieved chunks to source citations so the L2 reply can carry
 * documentId / section / version without exposing raw chunk text.
 */
object KnowledgeCitationMapper {

    fun citations(chunks: List<KnowledgeChunk>): List<KnowledgeCitation> =
        chunks.map { chunk ->
            KnowledgeCitation(
                documentId = chunk.documentId,
                title = chunk.title,
                sectionPath = chunk.sectionPath,
                version = chunk.documentVersion
            )
        }
}
