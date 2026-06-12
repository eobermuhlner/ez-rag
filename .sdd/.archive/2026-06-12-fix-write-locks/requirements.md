## Problem Statement

When the MCP server is running, every call to the `ingest` or `reingest` MCP tools fails with a Lucene `write.lock` error. The MCP server opens the Lucene index at startup and holds the write lock for its entire lifetime. The `ingest` and `reingest` tools independently attempt to open the same index again on each call, which Lucene refuses because only one writer is allowed at a time. Users who rely on the MCP server to keep their knowledge base up to date cannot use these tools at all.

## Solution

Make resource ownership explicit: the caller that opens a `LuceneRepository` is the one that closes it. The ingest and re-ingest services accept an already-open repository instead of opening one themselves. In the MCP server the single shared repository is passed into the write tools, so all tools share the same writer and the duplicate-open hazard is eliminated. The CLI commands open their own repository for the duration of their single call and close it on exit, preserving existing behaviour. Concurrent MCP write calls are protected by synchronising the repository's write methods.

## User Stories

1. As an MCP client user, I want the `ingest` tool to successfully ingest a file into the knowledge base while the MCP server is running, so that I can keep my knowledge base up to date without restarting the server.
2. As an MCP client user, I want the `reingest` tool to successfully re-ingest stale documents while the MCP server is running, so that my knowledge base reflects the latest content of changed files.
3. As an MCP client user, I want the `reingest --all` equivalent to switch the embedding model and re-ingest all documents without encountering a lock error, so that I can upgrade my embedding model without downtime.
4. As an MCP client user, I want multiple concurrent `ingest` or `reingest` calls to complete without corrupting the index, so that I can trust the server when running parallel ingestion jobs.
5. As a CLI user, I want the `ingest` command to continue working exactly as before, so that my existing scripts are not broken.
6. As a CLI user, I want the `reingest` command to continue working exactly as before, including the `--all` flag for model switching, so that my existing scripts are not broken.
7. As a developer, I want the ingest service to receive a repository rather than opening one internally, so that ownership of the resource is clear from the constructor signature.
8. As a developer, I want the re-ingest service to receive a repository rather than opening one internally, so that the stub-model workaround is no longer needed and the code is easier to reason about.
9. As a developer, I want model-switching concerns (resetting the stored embedding dimension) to live in the CLI command layer, not inside the service, so that the service has a single responsibility.
10. As a developer, I want the repository's write methods to be thread-safe, so that concurrent MCP tool calls cannot interleave commits and corrupt the index.

## User Acceptance Tests

1. Given the MCP server is running and a document has not been ingested before, when the `ingest` tool is called with the path to a text file, then the call succeeds and the document appears in subsequent `list` results.
2. Given the MCP server is running and a previously ingested file has been modified, when the `reingest` tool is called, then the call succeeds and the re-ingested document's chunk count is updated.
3. Given the MCP server is running, when the `ingest` tool is called twice in rapid succession with the same file, then both calls return a result (the second one reports the file as skipped rather than erroring).
4. Given the `ingest` CLI command is run with a valid file path, when the command completes, then the summary line is printed and the document is searchable.
5. Given the `reingest` CLI command is run with the `--all` flag after the embedding model has changed, when the command completes, then all documents have been re-ingested with the new model dimensions and a summary line is printed.
6. Given the MCP server is running, when the `search` tool is used immediately after an `ingest` call that added new content, then the newly ingested content is findable via search.

## Definition of Done

- All user acceptance tests pass.
- The `ingest` and `reingest` MCP tools no longer fail with a write-lock error when the server is running.
- The `ingest` and `reingest` CLI commands produce identical output to before this change.
- No regression in `list`, `search`, or `chunk` MCP tools.
- All automated tests (unit and integration, excluding `eval`) pass.
- No test constructs a `LuceneRepository` duplicate on the same directory within a single test run.

## Out of Scope

- Changes to the MCP transport layer, server startup, or tool registration.
- Changes to `list`, `search`, or `chunk` tools (these already use the shared repository correctly).
- Introducing a connection pool or multi-writer architecture.
- Persistent server-side queueing of write requests.
- Changes to the embedding model selection or configuration.

## Further Notes

Lucene enforces a single-writer-at-a-time contract via an on-disk `write.lock` file. The fix does not work around this contract — it respects it by ensuring at most one `IndexWriter` instance is ever open on a given directory at a time.

The `@Synchronized` change on `add()` and `delete()` is a correctness hedge: the JVM's `IndexWriter` is itself thread-safe for concurrent adds and deletes, but the surrounding metadata cache updates in `LuceneRepository` are not. Synchronisation prevents races on the in-memory `sourceMtimeCache`.

---

## Technical Annex
> Written against codebase as of: 2026-06-12

### Architectural Decisions

#### AD-1: `IngestService` accepts an injected `LuceneRepository`

Current constructor (to be replaced):
```kotlin
IngestService(embeddingModel, storeDir, chunkSize, chunkOverlap, warningWriter, analyzerName, urlFetcher, tempDirProvider)
```
New constructor:
```kotlin
IngestService(repository, chunkSize, chunkOverlap, warningWriter, urlFetcher, tempDirProvider)
```
- `embeddingModel`, `storeDir`, and `analyzerName` are removed — they were only used to call `LuceneRepository.open()` internally.
- The `val repository = LuceneRepository.open(...)` line and the `repository.use { }` block inside `ingest()` are removed. The service uses the injected `repository` directly.
- Caller owns the repository lifecycle.

#### AD-2: `ReIngestService` mirrors AD-1; CLI owns dimension reset

Current constructor (to be replaced):
```kotlin
ReIngestService(embeddingModel, storeDir, chunkSize, chunkOverlap, warningWriter, analyzerName, urlFetcher)
```
New constructor:
```kotlin
ReIngestService(repository, chunkSize, chunkOverlap, warningWriter, urlFetcher)
```
- Both `LuceneRepository.open(...)` calls and their `use { }` wrappers inside `reIngest()` are removed.
- The private `stubEmbeddingModel()` method is deleted (it was only needed to bypass dimension validation in the internal `open()` call during the read/delete phase).
- The `LuceneRepository.resetStoredDimension(storeDir)` call moves to `ReIngestCommand` (before `LuceneRepository.open()`), for the `forceAll` model-switching scenario.
- The `IngestService(embeddingModel, storeDir, ...)` instantiation inside `reIngest()` is replaced with `IngestService(repository, ...)` — the same injected repository is reused for the write phase.

Rationale: model-switching is a CLI-level concern. The stub-model trick is no longer needed when the caller provides an already-open repository.

#### AD-3: `McpIngestTool` constructor takes `repository: LuceneRepository`

Current constructor (to be replaced):
```kotlin
McpIngestTool(embeddingModel: EmbeddingModel, storeDir: Path, urlFetcher, ingestServiceFactory)
```
New constructor:
```kotlin
McpIngestTool(repository: LuceneRepository, urlFetcher, ingestServiceFactory)
```
Default factory (the factory type `(Int, Int, UrlFetcher) -> IngestService` is unchanged):
```kotlin
private val ingestServiceFactory: (Int, Int, UrlFetcher) -> IngestService = { cs, co, fetcher ->
    IngestService(repository, cs, co, urlFetcher = fetcher)
}
```

#### AD-4: `McpReIngestTool` constructor takes `repository: LuceneRepository`

Current constructor (to be replaced):
```kotlin
McpReIngestTool(embeddingModel: EmbeddingModel, storeDir: Path, reIngestServiceFactory)
```
New constructor:
```kotlin
McpReIngestTool(repository: LuceneRepository, reIngestServiceFactory)
```
Default factory (type `(Int, Int) -> ReIngestService` is unchanged):
```kotlin
private val reIngestServiceFactory: (Int, Int) -> ReIngestService = { cs, co ->
    ReIngestService(repository, cs, co)
}
```

#### AD-5: `McpServerCommand` passes shared `luceneRepository` to write tools

Current wiring in `mcpToolCallbackProvider()` (lines 67–68):
```kotlin
val ingestTool = McpIngestTool(embeddingModel, storeDir)
val reIngestTool = McpReIngestTool(embeddingModel, storeDir)
```
New wiring:
```kotlin
val ingestTool = McpIngestTool(luceneRepository)
val reIngestTool = McpReIngestTool(luceneRepository)
```
The existing `luceneRepository` opened at line 61 is reused.

#### AD-6: `IngestCommand` opens a `LuceneRepository` and passes it to `IngestService`

In `doCall()`, replace:
```kotlin
val service = IngestService(model, resolvedStoreDir, resolvedChunkSize, resolvedChunkOverlap, warningWriter, urlFetcher = urlFetcher)
```
with:
```kotlin
LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo ->
    val service = IngestService(repo, resolvedChunkSize, resolvedChunkOverlap, warningWriter, urlFetcher = urlFetcher)
    // … attach callbacks, call service.ingest(sources) …
}
```
The `analyzerName` value is resolved the same way it is in `McpServerCommand` (from `configService` or defaulting to `"standard"`).

#### AD-7: `ReIngestCommand` resets dimension when `forceAll=true`, then opens `LuceneRepository`

In `call()`, replace:
```kotlin
val service = ReIngestService(model, resolvedStoreDir, resolvedChunkSize, resolvedChunkOverlap, warningWriter)
```
with:
```kotlin
if (forceAllOption) {
    LuceneRepository.resetStoredDimension(resolvedStoreDir)
}
LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo ->
    val service = ReIngestService(repo, resolvedChunkSize, resolvedChunkOverlap, warningWriter)
    // … attach callbacks, call service.reIngest(forceAll = forceAllOption) …
}
```

#### AD-8: `LuceneRepository.add()` and `delete()` are `@Synchronized`

Both methods acquire the monitor of the `LuceneRepository` instance to prevent interleaved commits from concurrent MCP calls. This also serialises updates to the in-memory `sourceMtimeCache`.

### Automated Testing Decisions

**What makes a good test here:** tests assert externally observable behaviour — what `ingest()` returns, what the Lucene index contains, what the MCP tool result fields contain — not which internal methods were called or whether `open()` was invoked.

**Modules with automated unit tests:**

- **`IngestService`** (`IngestServiceTest`) — currently instantiated with `(fakeEmbeddingModel, tempDir, ...)`. Must be updated to pass an open `LuceneRepository`. Tests remain structurally identical; only the setup changes. Prior art: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/IngestServiceTest.kt`.

- **`ReIngestService`** (`ReIngestServiceTest`) — same pattern. Prior art: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/ReIngestServiceTest.kt`.

- **`McpIngestTool`** (`McpIngestToolTest`) — currently uses `McpIngestTool(fakeEmbeddingModel, storeDir, ingestServiceFactory = ...)`. Tests that use the factory override need no structural change (factory type signature is unchanged); only the tool constructor call changes to pass a mock/stub `LuceneRepository`. Tests that construct the full tool without a factory (disk-persistence and URL tests) must open a `LuceneRepository` themselves and pass it in. Prior art: `src/test/kotlin/ch/obermuhlner/ezrag/command/McpIngestToolTest.kt`.

- **`McpReIngestTool`** (`McpReIngestToolTest`) — same pattern as `McpIngestTool`. Prior art: `src/test/kotlin/ch/obermuhlner/ezrag/command/McpReIngestToolTest.kt`.

- **`LuceneRepository`** (`LuceneRepositoryTest`) — already has a concurrent `add()` test. Two new concurrent tests must be added for AD-8:
  - `concurrent delete calls from two threads complete without exception` — two threads each calling `repository.delete()` on different sources simultaneously.
  - `concurrent add and delete calls from two threads complete without exception` — one thread calling `add()` while another calls `delete()` simultaneously.

**Required new test (TDD regression for the write-lock bug):**

Add to `McpIngestToolTest` a test that does **not** use the factory override and instead opens a real `LuceneRepository`, constructs `McpIngestTool(repository)` exactly as `McpServerCommand` does, and calls `ingest` with a real file. This test must:
1. Be written first and confirmed to fail (or be confirmed that it would fail) against the old code.
2. Pass after the fix.
3. Assert that the ingested document count is 1 and the index contains the expected chunk.

The corresponding regression test for `McpReIngestTool` follows the same pattern: open a shared repository, pre-ingest a file, modify it on disk, construct `McpReIngestTool(repository)`, call `reingest`, and assert the chunk is updated.

**Test type:** all tests in scope are unit tests using `@TempDir` for on-disk Lucene index isolation. No new integration tests are required because the write-lock hazard is a structural / wiring problem fully covered by unit tests that exercise the real `LuceneRepository.open()`.

**No new test classes** are required — modifications to the four existing test classes listed above plus `LuceneRepositoryTest`.

**Known gaps (out of scope for this fix):**
- No end-to-end test exercises the full MCP server Spring context with ingest and search called sequentially against a live shared repository.
- No test covers a CLI command running against a store directory while an MCP server instance holds it open.
These are `@Tag("integration")` concerns and belong in a separate feature.
