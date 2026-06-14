package ch.obermuhlner.ezrag.command

import org.springframework.ai.embedding.EmbeddingModel
import java.nio.file.Path

/**
 * Carries all parameters needed by MCP tools to open a repository per-request via
 * [ch.obermuhlner.ezrag.ingestion.LuceneRepository.openWithRetry].
 */
data class StoreConfig(
    val embeddingModel: EmbeddingModel,
    val storeDir: Path,
    val analyzerName: String,
    val lockTimeoutSeconds: Int,
)
