# Re-ingest Feature PRD

## Problem Statement

When source documents change after being ingested, the vector store and BM25 index become stale — they still contain chunks from the old version of the file. There is no CLI command to refresh stale documents. The user must manually delete and re-ingest each affected file, which is tedious and error-prone when many files have changed.

## Solution

A new `reingest` subcommand (and corresponding MCP tool) that identifies which ingested documents are stale and re-ingests them automatically. By default it targets only stale documents; the `--all` flag forces re-ingestion of every document in the store regardless of staleness.

## User Stories

1. As a CLI user, I want to run `reingest` with no arguments to refresh all stale documents, so that my store is up to date without manually tracking which files changed.
2. As a CLI user, I want to see how many stale documents were found before re-ingestion, so that I understand what the command is about to do.
3. As a CLI user, I want to run `reingest --all` to force re-ingestion of every document in the store, so that I can rebuild the index from scratch without deleting and re-ingesting manually.
4. As a CLI user, I want a warning when a document's source file no longer exists on disk, so that I know the store contains an orphaned entry.
5. As a CLI user, I want missing source files to be skipped (not deleted), so that the command is non-destructive and I can handle orphaned entries explicitly via `delete`.
6. As a CLI user, I want `reingest` to support `--chunk-size` and `--chunk-overlap` options, so that I can change chunking parameters when refreshing the store.
7. As a CLI user, I want the output to follow the same format as `ingest`, so that tooling and scripts that parse ingest output also work with reingest.
8. As a CLI user, I want a summary line showing files re-ingested, chunks created, and files skipped, so that I have a clear audit of what happened.
9. As a CLI user, I want `reingest` to work with `--quiet` to suppress per-file output and show only the summary, so that it integrates cleanly in scripts.
10. As a CLI user, I want `reingest` to respect the `--store-dir` option, so that I can target a specific store when multiple stores exist.
11. As an AI agent using ez-rag via MCP, I want to call a `reingest` tool to refresh stale documents, so that I can keep the knowledge base current without CLI access.
12. As an MCP tool user, I want the `reingest` tool to support a `forceAll` parameter, so that an agent can trigger a full re-ingest when needed.
13. As an MCP tool user, I want the `reingest` tool to return structured results (stale found, files re-ingested, chunks created, skipped), so that an agent can reason about what changed.
14. As a developer, I want `reingest` to keep the vector store and BM25 index in sync at all times, so that searches remain consistent across both backends.

## Implementation Decisions

### New `ReIngestService`

A dedicated service encapsulates re-ingestion logic, separate from `IngestService`. It is responsible for:

1. Loading the store and enumerating all ingested document sources via `VectorStoreRepository.getMetadata()`.
2. Filtering to stale-only (default) or all documents (when `--all` is passed).
3. For each candidate source: check whether the file exists on disk. If not, emit a warning via the `warningWriter` and skip it.
4. Deleting stale/all source entries from both the vector store and the BM25 index before re-ingesting.
5. Calling `IngestService.ingest()` with the valid file list. Because old entries were deleted, `IngestService` will treat them as new documents and ingest normally.
6. Returning a `ReIngestResult` containing: `staleFound` (only meaningful in stale-only mode), `filesReIngested`, `chunksCreated`, and `filesSkipped`.

The delete-then-re-ingest approach avoids changing `IngestService` and guarantees no duplicate chunks in the vector store.

### New `ReIngestResult` data class

```
ReIngestResult(
    staleFound: Int,       // documents identified as stale (stale-only mode); -1 in --all mode
    filesReIngested: Int,
    chunksCreated: Int,
    filesSkipped: Int      // source files not found on disk
)
```

### New `ReIngestCommand` (CLI)

Follows the exact constructor pattern of `IngestCommand`: constructor parameters for all dependencies (model, storeDir, writers, chunk params) with `@Autowired` Spring fallbacks. Supports:

- `--all` flag (Boolean, default false)
- `--chunk-size` / `--chunk-overlap` options
- `--quiet` / `-q` flag
- `--store-dir` option

Output in stale-only mode:
```
Stale documents: 3
Re-ingesting: /path/to/file.txt
...
3 files re-ingested, 47 chunks created, 0 skipped
```

Output in `--all` mode (omit the stale-count line):
```
Re-ingesting: /path/to/file.txt
...
5 files re-ingested, 82 chunks created, 0 skipped
```

Missing-file warning goes to `warningWriter` (stderr):
```
WARN: source file not found, skipping: /path/to/missing.txt
```

### New `McpReIngestTool` (MCP)

Mirrors `McpIngestTool` in structure. Exposes a single `reingest` tool method with parameters:

- `forceAll: Boolean?` — if true, re-ingest all documents; default false (stale only)
- `chunkSize: Int?` — optional override
- `chunkOverlap: Int?` — optional override

Returns a structured result object mirroring `ReIngestResult`.

### Registration changes

- `EzRagCommand` gains `ReIngestCommand::class` in its `subcommands` list.
- `McpServerCommand` registers `McpReIngestTool` alongside the existing MCP tools.

### Staleness definition

A document is considered stale when `VectorStoreRepository.getMetadata()` reports `StoreDocumentInfo.stale == true`, which means the file's current filesystem mtime differs from the mtime stored in the vector store. This is the same definition already used by `list` and `status`.

## Testing Decisions

Good tests assert observable, external behavior (command output, store state, return values) and do not inspect internal data structures or implementation details.

### Modules to test

- **`ReIngestService`** — unit tests with a real (temp-dir) store, following the pattern of `IngestIntegrationTest`. Key scenarios:
  - Re-ingesting a stale document replaces its chunks with updated ones.
  - Unchanged documents are not touched in stale-only mode.
  - `--all` mode re-ingests every document regardless of mtime.
  - Missing source file produces a warning and is skipped; store is unchanged for that entry.
  - `staleFound` count is correct in stale-only mode.

- **`ReIngestCommand`** — command-level tests following `IngestCommandTest` and `DeleteCommandTest` patterns (inject test PrintWriter, assert on printed output and return code). Key scenarios:
  - Stale document is re-ingested; summary line reflects correct counts.
  - Unchanged document is not re-ingested in default mode.
  - `--all` re-ingests all documents; stale-count line is absent.
  - Missing source file produces a warning on stderr; summary line accounts for it.
  - `--quiet` suppresses per-file lines, shows only summary.

- **`McpReIngestTool`** — tool-level tests following `McpIngestToolTest`. Key scenarios:
  - Returns correct `ReIngestResult` fields after re-ingesting stale documents.
  - `forceAll = true` triggers full re-ingest.
  - Returns an `error` field (not an exception) on failure.

## Out of Scope

- Path filtering (re-ingest only files matching a pattern or under a directory).
- Automatic deletion of orphaned store entries (entries whose source file no longer exists).
- Displaying stale document details before re-ingesting (a `--dry-run` flag).
- Scheduling or watching for file changes and auto-re-ingesting.

## Further Notes

- The existing `ingest` command already silently re-ingests a file when its mtime changes. However, it does not explicitly delete old vector store chunks first, which can leave duplicate chunks. `ReIngestService` avoids this by explicitly calling `repository.delete(source)` before re-adding chunks.
- Both the vector store and the BM25 index must always be updated together. `ReIngestService` must delete from both and rely on `IngestService` to re-index both.
- The `stale` flag in `StoreDocumentInfo` is computed dynamically by `VectorStoreRepository.getMetadata()` using a `filesystemProbe` lambda, which is injectable for testing.
