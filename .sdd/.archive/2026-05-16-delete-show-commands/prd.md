# PRD: Delete and Show Commands

## Problem Statement

Once documents are ingested into the vector store, there is no way to remove them short of deleting the entire store. Similarly, there is no way to inspect how a specific document was chunked — users cannot verify chunk boundaries, check metadata, or read raw chunk text without external tooling. This makes it hard to debug poor retrieval results, clean up stale or incorrect documents, or understand why a particular query returns unexpected content.

## Solution

Add two new CLI subcommands:

- **`delete`** — removes all chunks belonging to one or more ingested files from the vector store.
- **`show`** — displays per-chunk metadata (and optionally raw chunk text) for a single ingested file.

Both commands are also exposed as MCP tools so that AI agents can manage and inspect the store programmatically.

## User Stories

1. As a developer, I want to run `ez-rag delete <file>` so that I can remove a stale or incorrect document from the vector store without wiping the entire store.
2. As a developer, I want to delete multiple documents in a single command (`ez-rag delete file1.md file2.md`) so that I can clean up several files at once efficiently.
3. As a developer, I want `delete` to print each file it removes so that I can confirm what was actually deleted.
4. As a developer scripting `delete`, I want to pass `--quiet` to suppress per-file output so that my script output stays clean.
5. As a developer, I want `delete` to warn me (but still exit 0) when a file was not found in the store so that my cleanup scripts remain idempotent.
6. As a developer, I want file paths to be normalised to absolute paths before matching so that `delete ./docs/file.md` and `delete docs/file.md` both work regardless of how the file was originally ingested.
7. As a developer, I want to run `ez-rag show <file>` to see a list of all chunks for that file, including each chunk's index, character count, and modification time, so that I can understand how the document was chunked.
8. As a developer diagnosing a retrieval problem, I want to pass `--chunks` to `show` so that I can read the raw text of each chunk and spot bad splits or missing content.
9. As a developer, I want `show` to exit with a non-zero code and a clear error message when the file was never ingested so that I notice typos immediately.
10. As a developer integrating with other tools, I want to pass `--output json` to `show` so that I can pipe the structured chunk data to `jq` or other processors.
11. As an MCP client (e.g. an AI agent), I want a `delete` tool that removes a document from the store so that I can keep the knowledge base up to date without human intervention.
12. As an MCP client, I want a `show` tool that returns chunk metadata for a file so that I can inspect retrieval quality programmatically.
13. As a developer, I want the README to document `delete` and `show` with examples so that I can learn the commands without reading the source.

## Implementation Decisions

### Modules to build or modify

**`VectorStoreRepository` (modified — deep module)**
The core data-access module gains two new operations:
- `delete(absoluteFilePath: String): Int` — removes all chunks whose `source` metadata matches the given absolute path; returns the number of chunks removed. Uses `SimpleVectorStore.delete(ids)` after collecting matching IDs via reflection (same pattern as `getMetadata`). Also removes the matching entries from the `ingestedFiles` cache.
- `getChunksForFile(absoluteFilePath: String): List<DocumentChunkInfo>` — returns an ordered list of chunk descriptors for the file, including chunk index, character count, mtime, and raw text. Uses the same reflection-based store map access as `getMetadata`.

A new data class `DocumentChunkInfo` lives alongside existing store data classes:
```
data class DocumentChunkInfo(
    val chunkIndex: Int,
    val charCount: Int,
    val mtime: Long,
    val text: String
)
```

**`DeleteCommand` (new)**
Picocli subcommand `delete`. Accepts one or more positional file paths. Normalises each to an absolute path, delegates to `VectorStoreRepository.delete()`, and prints results. Flags: `--quiet`/`-q`.

**`ShowCommand` (new)**
Picocli subcommand `show`. Accepts exactly one positional file path. Normalises to absolute, calls `VectorStoreRepository.getChunksForFile()`. Exits non-zero if the file is not in the store. Flags: `--chunks`, `--output` (`text`/`json`).

**`McpDeleteTool` (new)**
Spring bean wrapping `DeleteCommand` logic, registered as an MCP tool named `delete`. Accepts a single file path string (agents delete one file at a time via MCP).

**`McpShowTool` (new)**
Spring bean wrapping `ShowCommand` logic, registered as an MCP tool named `show`. Accepts a file path and a boolean `includeChunks` parameter.

**`EzRagCommand` (modified)**
Registers `DeleteCommand` and `ShowCommand` as subcommands.

**`README.md` (modified)**
Documents `delete` and `show` with usage examples.

### Path normalisation
Both commands resolve the provided path to an absolute path (`Path.of(input).toAbsolutePath().normalize().toString()`) before matching against stored `source` metadata. The same normalisation is also applied at ingest time (stored paths become absolute) so that the keys are consistent. If existing stored paths are relative, the match falls back gracefully to exact-string comparison before warning.

### Output format — `show --output text` (default)
```
File: /absolute/path/to/file.md
Chunks: 3

Chunk 1 — 842 chars, mtime: 1716000000000
Chunk 2 — 1021 chars, mtime: 1716000000000
Chunk 3 — 634 chars, mtime: 1716000000000
```
With `--chunks`, each entry also prints the raw text indented under the metadata line.

### Output format — `show --output json`
```json
{
  "file": "/absolute/path/to/file.md",
  "chunks": [
    { "chunkIndex": 0, "charCount": 842, "mtime": 1716000000000, "text": "..." },
    ...
  ]
}
```
When `--chunks` is not passed, `"text"` is omitted from the JSON.

### Delete output
Default: one line per deleted file, e.g. `Deleted: /absolute/path/to/file.md (12 chunks)`.
With `--quiet`: no output on success.
Warning line for not-found files: `Warning: not found in store: /absolute/path/to/file.md`.

## Testing Decisions

**What makes a good test:** Tests verify observable CLI behaviour (stdout content, exit code, store state after the operation) rather than internal implementation details. Tests do not assert on reflection calls or private field access — only on what the command produces and what the store contains afterwards.

**`VectorStoreRepositoryTest` (extended)**
- `delete` removes all chunks for the target file and leaves other files untouched.
- `delete` returns the correct chunk count.
- `delete` on an unknown path returns 0 (no chunks removed).
- `getChunksForFile` returns chunks in chunk-index order with correct metadata.
- `getChunksForFile` on an unknown path returns an empty list.
- Prior art: existing `VectorStoreRepositoryTest` using `@TempDir`, fake `EmbeddingModel`, and direct repository method assertions.

**`DeleteCommandTest` (new)**
- Deletes an ingested file: store no longer contains those chunks.
- Deletes multiple files in one call.
- Prints deleted file name and chunk count by default.
- `--quiet` suppresses output on success.
- Unknown file prints warning, exits 0, stdout captured via `StringWriter`.
- Prior art: `IngestCommandTest` pattern — direct command instantiation with fake embedding model and temp directory.

**`ShowCommandTest` (new)**
- Shows per-chunk metadata for an ingested file (without `--chunks`): correct chunk count, char counts, mtime.
- `--chunks` flag includes raw text in output.
- `--output json` produces valid JSON with expected fields.
- Unknown file exits non-zero with error message.
- Prior art: `SearchCommandTest` / `QueryCommandTest` patterns.

**`McpDeleteTool` and `McpShowTool`** — covered by the same underlying repository and command tests; no separate MCP integration tests required beyond smoke-level wiring checks if they follow the pattern of existing MCP tools.

## Out of Scope

- Glob or wildcard patterns for `delete` (e.g. `delete docs/*.md`).
- Bulk `show` across multiple files.
- A `--dry-run` flag for `delete`.
- Re-ingesting after delete (users can run `ingest` again normally).
- Modifying chunk content or metadata in place.
- Normalising paths that are already stored as relative paths in existing stores (a one-time migration is not included).

## Further Notes

- The reflection-based access to `SimpleVectorStore`'s private `store` field is an established pattern in this codebase (already used in `getMetadata` and `populateIngestedFilesFromStore`). The same pattern applies to `delete` and `getChunksForFile`.
- `SimpleVectorStore` exposes a `delete(List<String>)` method by ID, which is the correct deletion API — no reflection needed for the actual removal, only for discovery of matching IDs.
- Path normalisation at ingest time is a prerequisite for reliable matching; if this is not already happening, it must be added to `IngestCommand`/`IngestService` as part of this feature.
