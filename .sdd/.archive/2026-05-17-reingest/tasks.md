# Tasks: Re-ingest Feature

## Task 01-reingest-stale-core

Add a `reingest` CLI subcommand that re-ingests all stale documents. A document is stale when the stored mtime differs from the current filesystem mtime. The command deletes old chunks from both the vector store and the BM25 index, then re-ingests the source file. Missing source files are warned about on stderr and skipped.

`ReIngestResult` shape (from PRD prototype, trimmed to the decision-rich parts):
```
ReIngestResult(
    staleFound: Int,      // count of docs flagged stale by getMetadata()
    filesReIngested: Int,
    chunksCreated: Int,
    filesSkipped: Int     // source files not found on disk
)
```

`staleFound` counts all documents for which `StoreDocumentInfo.stale == true`, including missing-file entries (which `getMetadata()` also marks stale). Missing files are detected by checking file existence after enumerating the stale set, warned, and excluded from the re-ingest list.

### Implementation steps

- [x] Write failing `ReIngestServiceTest`: re-ingesting a stale document replaces its chunks
- [x] Implement `ReIngestService.reIngest(forceAll=false)` to make the test pass
- [x] Write failing `ReIngestServiceTest`: unchanged document is not re-ingested
- [x] Write failing `ReIngestServiceTest`: missing source file warns on warningWriter and is counted as skipped
- [x] Write failing `ReIngestCommandTest`: output contains "Stale documents: N" line and summary line
- [x] Write failing `ReIngestCommandTest`: `--quiet` suppresses per-file lines but not summary
- [x] Write failing `ReIngestCommandTest`: `--store-dir` targets the specified store directory
- [x] Implement `ReIngestCommand` (with `--chunk-size`, `--chunk-overlap`, `--quiet/-q`, `--store-dir`) to make those tests pass
- [x] Register `ReIngestCommand` in `EzRagCommand` subcommands list

### Acceptance criteria

- [x] Re-ingesting a stale document removes all old chunks from the vector store and BM25 index, and adds new chunks reflecting the updated file content (`ReIngestServiceTest`)
- [x] After re-ingestion, the vector store and BM25 index are in sync: a search for content only in the new version returns a result; a search for content only in the old version returns nothing (`ReIngestServiceTest`)
- [x] An unchanged document (mtime matches stored mtime) is not re-ingested and does not appear in `filesReIngested` or `filesSkipped` (`ReIngestServiceTest`)
- [x] A source file that no longer exists on disk produces a line starting with `WARN:` on stderr, increments `filesSkipped`, and leaves the store entry untouched (`ReIngestServiceTest`)
- [x] `staleFound` equals the count of documents with `StoreDocumentInfo.stale == true` (including missing-file entries) (`ReIngestServiceTest`)
- [x] Command stdout contains `"Stale documents: N"` before the summary, where N matches `staleFound` (`ReIngestCommandTest`)
- [x] Summary line matches the format `"N files re-ingested, M chunks created, K skipped"` (`ReIngestCommandTest`)
- [x] `--quiet` suppresses per-file `"Re-ingesting:"` lines; summary line is still printed (`ReIngestCommandTest`)
- [x] `--store-dir <path>` causes the command to operate on the specified store, not the auto-resolved one (`ReIngestCommandTest`)
- [x] `ez-rag reingest --help` exits 0 and prints usage (verified via existing `SubcommandTest` pattern)

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass
- [x] New `ReIngestServiceTest` tests are written and green before the corresponding implementation code is added
- [x] New `ReIngestCommandTest` tests are written and green before the corresponding implementation code is added

---

## Task 02-reingest-force-all

Extend `ReIngestService` and `ReIngestCommand` with force-all mode. When `--all` is passed, every document in the store is re-ingested regardless of its mtime. The "Stale documents:" line is omitted from output. `staleFound` is `null` in the result to signal "not applicable".

`ReIngestResult` updated shape:
```
ReIngestResult(
    staleFound: Int?,     // null when forceAll=true (not applicable)
    filesReIngested: Int,
    chunksCreated: Int,
    filesSkipped: Int
)
```

Depends on: **01-reingest-stale-core**

### Implementation steps

- [x] Write failing `ReIngestServiceTest`: `forceAll=true` re-ingests all documents including unchanged ones
- [x] Write failing `ReIngestServiceTest`: `forceAll=true` sets `staleFound` to null
- [x] Extend `ReIngestService.reIngest()` to accept `forceAll: Boolean` parameter
- [x] Write failing `ReIngestCommandTest`: `reingest --all` re-ingests all documents
- [x] Write failing `ReIngestCommandTest`: `reingest --all` output does not contain `"Stale documents:"` line
- [x] Add `--all` flag to `ReIngestCommand` and wire to `forceAll`

### Acceptance criteria

- [x] `reingest --all` re-ingests every document in the store, including ones whose mtime has not changed (`ReIngestCommandTest` + `ReIngestServiceTest`)
- [x] `reingest` (without `--all`) still skips unchanged documents (`ReIngestCommandTest`)
- [x] `staleFound` is `null` in `ReIngestResult` when `forceAll=true` (`ReIngestServiceTest`)
- [x] Stdout does not contain `"Stale documents:"` when `--all` is used (`ReIngestCommandTest`)
- [x] Stdout contains `"Stale documents:"` when `--all` is not used (`ReIngestCommandTest`)

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass
- [x] New `ReIngestServiceTest` and `ReIngestCommandTest` tests are written and green before the corresponding implementation code is added

---

## Task 03-mcp-reingest-tool

Add an MCP tool that exposes re-ingestion to AI agents. `McpReIngestTool` wraps `ReIngestService` with a single `@Tool`-annotated method. Exceptions are caught and returned as an `error` field; the tool never throws. The tool is registered in `McpServerCommand`.

Depends on: **01-reingest-stale-core**, **02-reingest-force-all**

### Implementation steps

- [x] Write failing `McpReIngestToolTest`: tool returns correct counts after re-ingesting a stale document
- [x] Write failing `McpReIngestToolTest`: `forceAll=true` re-ingests all documents
- [x] Write failing `McpReIngestToolTest`: exception from service sets `error` field; result fields are zero
- [x] Implement `McpReIngestTool` with parameters `forceAll: Boolean?`, `chunkSize: Int?`, `chunkOverlap: Int?`
- [x] Register `McpReIngestTool` in `McpServerCommand` alongside existing MCP tools

### Acceptance criteria

- [x] Calling the tool on a store with one stale document returns `filesReIngested=1`, `chunksCreated>0`, `filesSkipped=0`, `error=null` (`McpReIngestToolTest`)
- [x] Calling the tool with `forceAll=true` re-ingests all documents regardless of staleness (`McpReIngestToolTest`)
- [x] When the underlying service throws, the result contains a non-null `error` string and all count fields are zero; no exception is propagated (`McpReIngestToolTest`)
- [x] `staleFound` in the tool result is `null` when `forceAll=true`, and a non-negative integer otherwise (`McpReIngestToolTest`)
- [x] The `reingest` tool name appears in the list of registered tools when `McpServerCommand` starts (verified via existing `McpServerCommandTest` pattern)

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass
- [x] New `McpReIngestToolTest` tests are written and green before the corresponding implementation code is added
