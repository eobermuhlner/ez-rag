# PRD: `chunk` sub-command — explicit chunk retrieval

## Problem Statement

After performing a search, an LLM receives `ChunkMatch` results that each carry a `filePath` and `chunkIndex`. When a retrieved chunk references surrounding context (e.g. a code example that continues in the next chunk, or a section heading from the previous chunk), the LLM has no way to fetch those adjacent chunks without running another full search — which may not return the specific chunks by position. There is no targeted way to read a chunk by its exact address.

## Solution

Add a `chunk` sub-command (CLI) and a corresponding `McpChunkTool` (MCP) that retrieve one or more chunks by explicit address: a `(filePath, chunkIndex)` pair. An optional `--window N` parameter expands the retrieval to N chunks before and N chunks after the target, letting an LLM load surrounding context in a single call.

## User Stories

1. As an LLM using the MCP server, I want to fetch a specific chunk by file path and chunk index, so that I can read the exact content at a known position without re-running a search.
2. As an LLM using the MCP server, I want to fetch chunks surrounding a search result, so that I can read the full context around a match without additional round-trips.
3. As a developer, I want to run `ez-rag chunk <filePath> <chunkIndex>` from the CLI, so that I can inspect the content of a specific chunk during debugging.
4. As a developer, I want to use `--window 1` to also retrieve the chunk before and after the target, so that I can quickly see the surrounding context.
5. As a developer, I want `--output json` to produce machine-readable output from the CLI, so that I can pipe chunk results into other tools.
6. As an LLM using the MCP server, I want the result to include heading metadata (title, level, path) alongside the text, so that I can understand where in the document structure the chunk lives.
7. As an LLM using the MCP server, I want a structured error field in the result when a file or chunk index is not found, so that I can handle the failure gracefully without crashing my workflow.
8. As a developer, I want the CLI to exit with code 1 and print a clear error message when the file is not in the store or the chunk index is out of range, so that I get actionable feedback.
9. As a developer, I want `--window` to silently clamp at file boundaries (first and last chunk), so that I do not have to know the exact chunk count before calling the command.
10. As a developer, I want `--store-dir` to work on the `chunk` command the same way it does on `search` and `show`, so that I can point the command at a non-default store location.
11. As an LLM, I want the MCP chunk tool to appear in the tool list with a clear description, so that I can discover it without documentation.
12. As a developer, I want the `chunk` command to follow the same output format conventions (`text` / `json`) as `search` and `show`, so that my shell scripts can handle all commands uniformly.

## Implementation Decisions

### Module: `LuceneRepository.getChunkRange`

A new method on `LuceneRepository`:

```
fun getChunkRange(source: String, fromIndex: Int, toIndex: Int): List<DocumentChunkInfo>
```

- Queries the Lucene index by `FIELD_SOURCE` (equality) and filters results to `chunkIndex in fromIndex..toIndex`.
- Returns chunks sorted by `chunkIndex` ascending.
- Returns an empty list when no matching document exists for `source` — callers are responsible for distinguishing "file not in store" (empty list for all indices) from "valid file, empty range".
- Reuses existing stored fields (`FIELD_CONTENT`, `FIELD_CHUNK_INDEX`, `FIELD_HEADING_*`); no schema changes.
- Callers compute `fromIndex = max(0, chunkIndex - window)` and `toIndex = chunkIndex + window`; the method returns only what exists, naturally clamping at boundaries.

### Module: `ChunkCommand`

New CLI subcommand registered in `EzRagCommand.subcommands`:

- **Name**: `chunk`
- **Positional parameters**: `filePath` (index 0), `chunkIndex` (index 1, Int)
- **Options**: `--window N` (default 0), `--output text|json` (default `text`), `--store-dir`
- **Logic**: resolves store dir via the same precedence chain as `ShowCommand`; opens `LuceneRepository`; calls `getChunkRange(absoluteFilePath, chunkIndex - window, chunkIndex + window)`; returns exit code 1 with an error message if the result is empty (file not in store) or if the exact `chunkIndex` is absent from the results (index out of range).
- **Text output**: one block per chunk — prints `Chunk <N>` header, heading path/title if present, then the chunk text indented.
- **JSON output**: a JSON object `{ "file": "...", "chunks": [ { "chunkIndex": N, "text": "...", "headingTitle": "...", "headingLevel": N, "headingPath": [...] } ] }` (heading fields omitted when null), plus `"error"` field on failure.

### Module: `McpChunkTool`

New MCP tool class, instantiated and registered in `McpServerCommand.mcpToolCallbackProvider()` alongside existing tools:

- **Parameters**: `filePath: String`, `chunkIndex: Int`, `window: Int?` (default 0)
- **Return type**:
  ```
  data class ChunkToolResult(
      val file: String,
      val chunks: List<ChunkResult>,
      val error: String? = null
  )
  data class ChunkResult(
      val chunkIndex: Int,
      val text: String,
      val headingTitle: String? = null,
      val headingLevel: Int? = null,
      val headingPath: List<String>? = null
  )
  ```
- Opens `LuceneRepository`, calls `getChunkRange`, maps `DocumentChunkInfo` → `ChunkResult`.
- Returns `error` field (not an exception) when the file is not found or an unexpected error occurs; `chunks` is empty in error cases.
- Closes the repository after the call (use-block pattern, same as `McpShowTool`).

### Registration

- `ChunkCommand::class` added to `EzRagCommand.subcommands` list.
- `McpChunkTool` instantiated and added to the `tools` list in `McpServerCommand.mcpToolCallbackProvider()`.

## Testing Decisions

**What makes a good test**: tests assert on externally observable behaviour — CLI output text/JSON, exit codes, MCP tool return values — not on internal implementation details like which Lucene query class is used. Tests use real `LuceneRepository` instances backed by `@TempDir` when testing repository-level behaviour, and stub/override the repository layer when testing command/tool logic in isolation.

### `LuceneRepository.getChunkRange` (unit + integration)

- Prior art: `EmbeddingSearchPipelineTest` opens a real `LuceneRepository` on `@TempDir` and asserts on returned `ChunkMatch` content.
- Tests cover: returns correct chunks for exact match; returns surrounding chunks with `window > 0`; clamps at lower boundary (chunk 0); clamps at upper boundary (last chunk); returns empty list for unknown file; returns empty list when `fromIndex > toIndex`.

### `ChunkCommand` (unit)

- Prior art: `SearchCommandTest` — constructs `ChunkCommand` with a pre-populated `@TempDir` store and a `PrintWriter(StringWriter)`, then asserts on `StringWriter.toString()` and exit code.
- Tests cover: text output for single chunk; JSON output structure; `--window` fetches adjacent chunks; missing file returns exit code 1 with error message; out-of-range index returns exit code 1; boundary clamping does not error.

### `McpChunkTool` (unit)

- Prior art: `McpSearchToolTest` — stubs the pipeline, asserts on the returned result data class fields.
- Tests cover: single chunk retrieval; `window` parameter expansion; heading metadata propagated; file not found returns `error` field and empty `chunks`; exception from repository returns `error` field.

## Out of Scope

- Fetching chunks across multiple files in a single call.
- Filtering by heading path or heading level.
- A `--range` alternative syntax (e.g. `--from 2 --to 5`); the window semantics cover the primary use case.
- Streaming / pagination of large windows.
- Any changes to the ingestion pipeline or chunk storage schema.

## Further Notes

- The `--window` parameter uses symmetric expansion (N before, N after). If the primary use case evolves toward asymmetric access (e.g. "give me the next 3 chunks only"), a `--before` / `--after` split can be added as a follow-up without breaking the `--window` shorthand.
- The `chunk` command name is intentionally short. It sits alongside `search` and `show` as a retrieval primitive, not a higher-level operation.
- The MCP tool description should make clear that chunk indices come from prior `search`, `embedding-search`, or `bm25-search` tool results, so the LLM can connect the two tools in its reasoning.
