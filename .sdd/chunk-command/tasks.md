# Tasks: `chunk` sub-command — explicit chunk retrieval

## Task 01-chunk-cli

Deliver the `chunk` CLI sub-command end-to-end: from a new `LuceneRepository.getChunkRange` method through the `ChunkCommand` picocli subcommand to printed output. After this task, a developer can run `ez-rag chunk <filePath> <chunkIndex> [--window N] [--output text|json]` and receive chunk content from the store.

### Implementation steps

- [x] Add `getChunkRange(source, fromIndex, toIndex): List<DocumentChunkInfo>` to `LuceneRepository` — implement by calling `getChunksForFile(source)` and filtering to `chunkIndex in fromIndex..toIndex`; return sorted ascending by chunkIndex; do NOT write a second Lucene query
- [x] Write `LuceneRepository.getChunkRange` tests using a real `@TempDir` index: exact single match, window expansion returning multiple chunks, lower boundary clamp (fromIndex < 0 has no effect), upper boundary clamp (toIndex beyond last chunk returns only available chunks), unknown source returns empty list, `fromIndex > toIndex` returns empty list
- [x] Implement `ChunkCommand` picocli subcommand with positional params `filePath` (String) and `chunkIndex` (Int), options `--window` (Int, default 0), `--output text|json` (default text), `--store-dir`; resolve store dir via the same precedence chain as `ShowCommand`
- [x] Text output: one block per chunk — `Chunk <N>` header line, heading path/title line if present, then chunk text indented with two spaces
- [x] JSON output: `{ "file": "...", "chunks": [ { "chunkIndex": N, "text": "...", "headingTitle": "...", "headingLevel": N, "headingPath": [...] } ] }` with heading fields omitted when null; add top-level `"error"` string on failure
- [x] Exit code 1 + error message on stderr when file not in store (empty result from `getChunkRange` for the full range) or when the exact `chunkIndex` is absent from results (applies even with `--window`; the target chunk itself must be present)
- [x] Register `ChunkCommand::class` in `EzRagCommand.subcommands`
- [x] Write `ChunkCommand` unit tests: construct the command with a pre-populated `@TempDir` store and `PrintWriter(StringWriter)` output capture; assert on `StringWriter.toString()` and exit code

### Acceptance criteria

- [x] `ez-rag chunk <file> 2` prints the text of chunk 2 in text format and exits 0
- [x] `ez-rag chunk <file> 2 --window 1` prints chunks 1, 2, 3 (or clamped subset when at boundary) in ascending order and exits 0
- [x] `ez-rag chunk <file> 0 --window 1` returns only chunks 0 and 1 (no chunk at index -1), exits 0
- [x] `ez-rag chunk <file> 2 --output json` produces valid JSON with a top-level `"file"` string and a `"chunks"` array
- [x] JSON output includes `"headingTitle"`, `"headingLevel"`, `"headingPath"` fields when the chunk carries heading metadata; those fields are absent (not null) when heading metadata is not set
- [x] `ez-rag chunk nonexistent.txt 0` exits 1 and prints a message containing the file path
- [x] `ez-rag chunk <file> 999` exits 1 and prints a message indicating the chunk index was not found
- [x] `getChunkRange` with `fromIndex > toIndex` returns an empty list without throwing

### Quality gates

- [x] `./gradlew build` passes with zero compiler errors and zero new test failures
- [x] No new Kotlin compiler warnings introduced

---

## Task 02-chunk-mcp

Deliver the `McpChunkTool` MCP tool end-to-end: from `LuceneRepository.getChunkRange` (added in task 01) through the tool's `@Tool`-annotated method to a structured `ChunkToolResult` registered in the MCP server. After this task, an LLM connected via MCP can call the chunk tool to fetch chunk content by address.

### Implementation steps

- [x] Implement `McpChunkTool` with constructor taking `embeddingModel` and `storeDir`; add `@Tool`-annotated method `chunk(filePath, chunkIndex, window?)` returning `ChunkToolResult`
- [x] Define `ChunkToolResult(file: String, chunks: List<ChunkResult>, error: String? = null)` and `ChunkResult(chunkIndex: Int, text: String, headingTitle: String? = null, headingLevel: Int? = null, headingPath: List<String>? = null)` as inner data classes
- [x] Open `LuceneRepository` in a use-block, compute `fromIndex = max(0, chunkIndex - (window ?: 0))` and `toIndex = chunkIndex + (window ?: 0)`, call `getChunkRange`, map `DocumentChunkInfo` → `ChunkResult`
- [x] Return `error` field (never rethrow) when file not found (empty result) or when an unexpected exception occurs; `chunks` must be empty in error cases
- [x] `@Tool` description must mention that `chunkIndex` values come from prior `search`, `embedding-search`, or `bm25-search` tool results
- [x] Register `McpChunkTool` in `McpServerCommand.mcpToolCallbackProvider()` alongside existing tools
- [x] Write `McpChunkTool` unit tests using a real `@TempDir` index and a fake `EmbeddingModel` (same pattern as `McpShowToolTest`): ingest documents directly into a `LuceneRepository`, construct the tool with the same `tempDir`, assert on `ChunkToolResult` fields

### Acceptance criteria

- [x] `chunk(filePath, chunkIndex, null)` returns `ChunkToolResult` with the target chunk in `chunks` and `error == null`
- [x] `chunk(filePath, chunkIndex, 1)` returns up to 3 chunks in ascending chunkIndex order (clamped at file boundaries)
- [x] Each `ChunkResult` in the response carries `chunkIndex` and `text`; heading fields (`headingTitle`, `headingLevel`, `headingPath`) are present when the underlying chunk has heading metadata and null otherwise
- [x] `chunk("nonexistent.txt", 0, null)` returns `error` field set to a non-null message and `chunks` as an empty list
- [x] A `RuntimeException` thrown during repository access is caught; the method returns `error` set and `chunks` empty rather than propagating the exception
- [x] `McpChunkTool` appears in the tool array returned by `mcpToolCallbackProvider()`
- [x] The `@Tool` description string references chunk indices from search results

### Quality gates

- [x] `./gradlew build` passes with zero compiler errors and zero new test failures
