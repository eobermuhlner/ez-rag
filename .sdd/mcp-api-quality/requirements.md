# Requirements: MCP API Quality Improvements

## Problem Statement

The ez-rag MCP server exposes tools that agentic coding assistants (such as Claude Code) use to search and query a local knowledge base. The current tool API has several friction points that make it harder for machine callers to use reliably:

- Successful responses include an `"error": null` field that is noise for any JSON consumer.
- Field names are inconsistent across tools (`filePath` vs `path`, `similarityScore` vs `score`), forcing callers to handle two names for the same concept.
- Some required parameters have natural defaults and should be optional (`includeChunks` in `show`, `window` in `chunk`).
- The `search` tool description incorrectly claims that `minScore` is ignored in hybrid mode — it is in fact applied to the final RRF-fused scores.
- `search_bm25` does not expose a `minScore` parameter even though its scores are normalized to 0–1.
- There is no `list` MCP tool — the only way to enumerate documents with staleness status is via the CLI.
- Search results do not include `headingPath`, so agentic callers cannot understand a chunk's position in the document hierarchy without parsing raw Markdown.
- The `query` tool system prompt uses "context documents provided" framing, causing the LLM to produce answers like "based on the second document provided" instead of citing source paths.

## Solution

Improve the MCP API contract so that it is clean, consistent, and navigable by agentic callers without surprises:

- Omit `error` from all tool responses on success.
- Standardize on `path` and `score` as the canonical field names everywhere.
- Make `includeChunks` and `window` optional parameters with documented defaults.
- Correct the `search` tool description to accurately describe `minScore` behaviour.
- Add `minScore` to `search_bm25` for consistency with the other search tools.
- Add a `list` MCP tool that returns per-document inventory with staleness flags.
- Include `headingPath` in search results so callers understand a chunk's section context.
- Reword the `query` system prompt to use "knowledge base" framing with source-path citations.

## User Stories

1. As an agentic coding tool, I want search results to omit the `error` field on success, so that I can parse responses without filtering noise.
2. As an agentic coding tool, I want all tool responses to use `path` instead of `filePath`, so that I can reference document identifiers consistently regardless of which tool I called.
3. As an agentic coding tool, I want all score fields to be named `score`, so that I can compare relevance values across `search`, `search_bm25`, `search_embedding`, and `query` results without translating field names.
4. As an agentic coding tool, I want to call `show` without specifying `includeChunks`, so that I can inspect document metadata without being forced to make a decision I don't care about.
5. As an agentic coding tool, I want to call `chunk` without specifying `window`, so that I can retrieve a single chunk without providing an explicit zero.
6. As an agentic coding tool, I want the `search` tool description to accurately state that `minScore` filters the final hybrid results, so that I can rely on it to control result quality.
7. As an agentic coding tool, I want `search_bm25` to accept a `minScore` parameter, so that I can apply a quality threshold consistent with `search` and `search_embedding`.
8. As a developer, I want to understand that BM25 scores are normalized relative to the top result in the result set (top = 1.0), so that I can interpret `minScore` thresholds correctly for BM25 searches.
9. As an agentic coding tool, I want a `list` MCP tool that returns per-document path, chunk count, and staleness flag, so that I can decide whether to call `reingest` before searching.
10. As an agentic coding tool, I want search results to include `headingPath` for each chunk, so that I can understand a chunk's position in the document hierarchy without parsing Markdown.
11. As a human user of `query`, I want the LLM answer to say "according to the knowledge base" rather than "based on the second document provided", so that the answer reads naturally and cites source paths.
12. As a human user of `query`, I want the LLM to cite source paths when answering, so that I can trace answers back to specific documents.
13. As an agentic coding tool, I want `status` to remain focused on store health and configuration, so that I can call it for a lightweight health check without receiving a full document inventory.
14. As an agentic coding tool, I want `list` to be separate from `status`, so that I only pay the cost of staleness detection when I actually need a document inventory.
15. As an agentic coding tool, I want `headingPath` to be null for non-Markdown chunks, so that I know when heading context is simply not available rather than treating an empty list as meaningful.
16. As a developer using `--output-format json`, I want the CLI JSON output to use the same `path` and `score` field names as the MCP API, so that I have a consistent schema across both interfaces.

## User Acceptance Tests

1. Given a successful `search` call, when the JSON result is inspected, then no `error` key appears in the response.
2. Given a successful `show` call, when the JSON result is inspected, then no `error` key appears in the response.
3. Given a successful `list` call, when the JSON result is inspected, then no `error` key appears in the response.
4. Given a search result from any of `search`, `search_bm25`, or `search_embedding`, when the returned chunk fields are inspected, then the document identifier field is named `path` (not `filePath`).
5. Given a `query` result, when the source references are inspected, then the document identifier field is named `path` and the relevance field is named `score`.
6. Given a call to `show` with no `includeChunks` argument, when the tool responds, then it succeeds and returns document metadata (defaulting to not including chunks).
7. Given a call to `chunk` with no `window` argument, when the tool responds, then it succeeds and returns the single requested chunk.
8. Given a `search_bm25` call with `minScore: 0.8`, when results are returned, then all results have a score of at least 0.8.
9. Given a `search_bm25` call with no `minScore` argument, when results are returned, then all results are included (no threshold applied).
10. Given an indexed Markdown document, when `search` returns a chunk from that document, then the `headingPath` field contains the list of heading titles leading to that chunk's section.
11. Given an indexed PDF document, when `search` returns a chunk from that document, then the `headingPath` field is absent (null) in the response.
12. Given a knowledge base with documents, when `list` is called, then each entry includes `path`, `chunkCount`, and `stale` fields.
13. Given a document whose source file has been modified since last ingest, when `list` is called, then that document's `stale` field is `true`.
14. Given an empty knowledge base, when `list` is called, then an empty array is returned (no error).
15. Given a `query` response, when the LLM answer is read, then it cites source paths rather than saying "based on document 1" or "based on the provided context".
16. Given a `status` call, when the response is inspected, then it does not include a per-document inventory (the full document list is only in `list`).

## Definition of Done

- All user acceptance tests pass.
- All existing MCP tool tests updated to reflect renamed fields (`path`, `score`).
- New `list` tool has unit tests covering: normal inventory, stale document detection, and empty store.
- `McpBm25SearchTool` has unit tests for `minScore` forwarding and default-zero behaviour.
- `McpShowTool` has a unit test verifying `includeChunks` defaults to false when omitted.
- `McpChunkTool` has a unit test verifying `window` defaults to zero when omitted.
- `headingPath` propagation is covered by tests asserting it is populated for Markdown chunks and null for non-Markdown chunks.
- `RagPipeline` has a test asserting the new knowledge-base system prompt is used by default.
- CLI `--output-format json` output uses `path` and `score` field names consistently.
- No regression in existing functionality: all pre-existing tests pass.
- README updated to reflect changed JSON field names in CLI output examples.

## Out of Scope

- Batch `chunk` retrieval (multiple non-adjacent chunk indices in one call).
- Normalizing BM25 scores to an absolute scale (scores remain relative to the top result).
- Adding hybrid-specific nuance to the `minScore` description beyond the accuracy fix.
- Removing or restructuring the `query` tool.
- Renaming `storeDirPath` in the `status` result (it refers to a directory path, not a document path, and is unambiguous in context).

## Further Notes

- All `filePath` → `path` renames affect JSON serialization that the CLI `--output-format json` commands also emit. The CLI output format changes accordingly and README documentation for JSON output should be updated to match.
- `McpChunkTool.ChunkResult` already exposes `headingPath`. After this change, `ChunkMatch` (the search path) will also expose it, giving both search and chunk-fetch a consistent shape for heading context.
- The `list` tool's staleness detection uses real filesystem `mtime` probes. Tests must inject a filesystem probe override (the same pattern used in `ListCommand` tests) to avoid real filesystem interaction.

---

## Technical Annex
> Written against codebase as of: 2026-06-09

This section contains the architectural and automated testing decisions derived from the planning session. It is intended for architect and developer review.

### Architectural Decisions

#### Module overview

| Module | Action | Purpose |
|---|---|---|
| `ChunkMatch` (SearchModel.kt) | Modify | Rename `filePath` → `path`; add `headingPath: List<String>?` |
| `SourceReference` (RagModel.kt) | Modify | Rename `filePath` → `path`; rename `similarityScore` → `score` |
| `BM25SearchPipeline` | Modify | Populate `headingPath` from Lucene `heading_path` field |
| `EmbeddingSearchPipeline` | Modify | Populate `headingPath` from Lucene `heading_path` field |
| `RrfFusion` | No change | Keys by `(path, chunkIndex)`; new field carried through automatically |
| `McpBm25SearchTool` | Modify | Add `minScore: Double?` parameter; forward to `SearchQuery.minScore` |
| `McpShowTool` | Modify | Add `@ToolParam(required = false)` to `includeChunks`; default false |
| `McpChunkTool` | Modify | Add `@ToolParam(required = false)` to `window`; Kotlin `Int?` already defaults to null |
| All MCP result data classes | Modify | Add `@JsonInclude(JsonInclude.Include.NON_NULL)` to omit null `error` on success |
| `McpListTool` | New | Returns `List<DocumentInfo>` from `LuceneRepository.getMetadata()` |
| `McpServerCommand` | Modify | Register `McpListTool` alongside existing tools |
| `RagPipeline` | Modify | Replace `DEFAULT_RAG_SYSTEM_PROMPT` with knowledge-base framing |
| `OutputFormatter` | Modify | Update `chunk.filePath` → `chunk.path`; `source.filePath` → `source.path`; `source.similarityScore` → `source.score` |

#### Data model changes

**`ChunkMatch`** (current state: `filePath: String, chunkIndex: Int, score: Double, content: String`):
```kotlin
data class ChunkMatch(
    val path: String,           // renamed from filePath
    val chunkIndex: Int,
    val score: Double,
    val content: String,
    val headingPath: List<String>? = null   // new field
)
```

**`SourceReference`** (current state: `filePath: String, chunkIndex: Int, similarityScore: Double, excerpt: String`):
```kotlin
data class SourceReference(
    val path: String,           // renamed from filePath
    val chunkIndex: Int,
    val score: Double,          // renamed from similarityScore
    val excerpt: String
)
```

#### Omit `error` on success

All MCP result data classes carry `error: String? = null`. Configure Jackson to omit null fields by annotating each result class with `@JsonInclude(JsonInclude.Include.NON_NULL)`. This covers all existing tools without per-tool logic changes. Affected classes (search `McpSearchTool.SearchToolResult`, etc.) are local data classes in each `Mcp*Tool.kt` file.

#### `search` description fix

Remove "ignored for hybrid mode" (or equivalent) from the `minScore` `@ToolParam` description in `McpSearchTool`. Replace with: "`minScore` filters the final RRF-fused scores, which are normalized to 0–1."

#### `minScore` for `search_bm25`

Add `minScore: Double? = null` parameter to `McpBm25SearchTool.searchBm25()` annotated with `@ToolParam(required = false)`. Pass to `SearchQuery(minScore = minScore ?: 0.0)`. `BM25SearchPipeline` already applies `minScore` filtering — no pipeline changes needed. Parameter description must note that BM25 scores are normalized relative to the highest-scoring result in the current result set (top result = 1.0).

#### `headingPath` in search results

Both `BM25SearchPipeline.search()` and `EmbeddingSearchPipeline.search()` map Spring AI `Document` objects to `ChunkMatch`. Extend both mapping blocks to read `doc.metadata["heading_path"]` and cast it to `List<String>?`. If absent (null), `headingPath` remains null. `LuceneRepository` already stores `heading_path` as a Lucene stored field, and `DocumentChunkInfo.headingPath` confirms the field exists at the repository layer.

#### New `McpListTool`

```kotlin
@Component
class McpListTool(private val luceneRepository: LuceneRepository) {

    data class DocumentInfo(val path: String, val chunkCount: Int, val stale: Boolean)

    @Tool(description = "List all ingested documents with their chunk count and staleness status.")
    fun list(): List<DocumentInfo> {
        val metadata = luceneRepository.getMetadata()
        return metadata.documents.map { doc ->
            DocumentInfo(path = doc.path, chunkCount = doc.chunkCount, stale = doc.stale)
        }
    }
}
```

Result shape on success (no `error` field — covered by `@JsonInclude`):
```json
[
  { "path": "docs/guide.md", "chunkCount": 12, "stale": false },
  { "path": "docs/api.md",   "chunkCount": 8,  "stale": true  }
]
```

Staleness detection uses `LuceneRepository.getMetadata(filesystemProbe)` with the default real-filesystem probe. Tests inject a lambda override.

Register `McpListTool` as a Spring component and add it to the `buildList { }` in `McpServerCommand.mcpToolCallbackProvider()`.

#### `query` system prompt replacement

Replace `DEFAULT_RAG_SYSTEM_PROMPT` in `RagPipeline` with:

> You are a helpful assistant. Answer the user's question using ONLY content from the knowledge base provided below. For each claim, cite the source path. If the answer is not in the knowledge base, say so. The conversation history shows earlier exchanges; you may refer to them when answering follow-up questions.

The `<document source="...">` wrapper in `RagPipeline.query()` already uses `chunk.path` after the field rename, so no structural change is needed there.

#### `OutputFormatter` mechanical rename

All occurrences of:
- `chunk.filePath` → `chunk.path`
- `source.filePath` → `source.path`
- `source.similarityScore` → `source.score`

No behavioural change — purely following the model field renames.

### Automated Testing Decisions

**What makes a good test:** test the observable MCP contract — the shape and content of result objects — not internal wiring. Do not assert on Spring AI annotation values or Jackson configuration details. All tests use stub pipelines via anonymous subclasses (following the pattern in `McpSearchToolTest`, `McpBm25SearchToolTest`, etc.) to isolate each tool from the Lucene layer.

**Modules with tests:**

- `McpSearchTool`: update existing tests to assert `path` field name; `headingPath` present when stub returns it; `error` absent from successful results.
- `McpBm25SearchTool`: update existing tests; add tests for `minScore` parameter — verify it is forwarded to `SearchQuery.minScore` and defaults to 0.0 when omitted; `headingPath` absent when stub returns null.
- `McpEmbeddingSearchTool`: update existing tests for `path`; add `headingPath` presence/absence assertions.
- `McpShowTool`: update existing tests; add test that `show` succeeds when `includeChunks` is omitted (null → false).
- `McpChunkTool`: update existing tests for `path` field; add test that `window` defaults to 0 when omitted.
- `McpDeleteTool`: update existing tests for `path` field in result.
- `McpQueryTool`: update existing tests for `path` and `score` fields in `SourceReference`.
- `McpListTool` (new): test that it returns per-document `path`, `chunkCount`, and `stale` flag; test with a stale document (inject mtime probe); test against empty store. Inject `filesystemProbeOverride` lambda via the same pattern used in `ListCommand` tests.
- `RagPipeline`: update existing tests to assert the new system prompt text is used by default.

**Prior art:** all existing `McpXxxToolTest` files follow the same pattern — construct a stub pipeline via an anonymous subclass, call the tool method directly, assert on the result data class. New tests follow the same pattern.
