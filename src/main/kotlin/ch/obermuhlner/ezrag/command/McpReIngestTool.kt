package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.ReIngestService
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * MCP tool that re-ingests stale (or all) documents into the vector store via [ReIngestService].
 * Opens a repository per-request via [StoreConfig] so the write lock is not held between calls.
 */
class McpReIngestTool(
    private val storeConfig: StoreConfig,
    private val urlFreshnessThresholdMs: Long = 24 * 3_600_000L,
    private val reIngestServiceFactory: (LuceneRepository, Int, Int, List<String>) -> ReIngestService = { repo, chunkSize, chunkOverlap, passwords ->
        ReIngestService(repo, chunkSize, chunkOverlap, passwords = passwords)
    }
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ReIngestToolResult(
        val staleFound: Int?,
        val filesReIngested: Int,
        val chunksCreated: Int,
        val filesSkipped: Int
    )

    @Tool(description = "Re-ingest documents in the vector store. By default re-ingests only stale documents (those whose source file has been modified since last ingest). Pass forceAll=true to re-ingest every document regardless of staleness. Supply passwords to unlock encrypted Office files.")
    fun reingest(
        @ToolParam(required = false, description = "If true, re-ingest all documents regardless of staleness. Default false (stale only).") forceAll: Boolean?,
        @ToolParam(required = false, description = "Chunk size in characters (default: 1000).") chunkSize: Int?,
        @ToolParam(required = false, description = "Chunk overlap in characters (default: 200).") chunkOverlap: Int?,
        @ToolParam(required = false, description = "Passwords to try when opening encrypted Office files. Each entry is tried in order until one succeeds.") passwords: List<String>? = null
    ): ReIngestToolResult {
        val cs = chunkSize ?: 1000
        val co = chunkOverlap ?: 200
        val force = forceAll ?: false
        val pwds = passwords ?: emptyList()
        LuceneRepository.openWithRetry(
            storeConfig.embeddingModel,
            storeConfig.storeDir,
            storeConfig.analyzerName,
            storeConfig.lockTimeoutSeconds,
        ).use { repository ->
            val service = reIngestServiceFactory(repository, cs, co, pwds)
            val result = service.reIngest(forceAll = force, urlFreshnessThresholdMs = urlFreshnessThresholdMs)
            return ReIngestToolResult(
                staleFound = result.staleFound,
                filesReIngested = result.filesReIngested,
                chunksCreated = result.chunksCreated,
                filesSkipped = result.filesSkipped
            )
        }
    }
}
