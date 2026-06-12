# Tasks: fix-write-locks

## Task [01-ingest-service-repository-injection]

Fix the ingest write-lock end-to-end: `IngestService` accepts an injected `LuceneRepository` instead of opening one internally. Every caller — CLI command, MCP tool, MCP server wiring — is updated atomically so the project compiles after this task. `ReIngestService` gets a temporary bridge (line 97) that opens its own repo and passes it to `IngestService`; this bridge is the minimum compilable change and will be cleaned up in Task 02.

### Implementation steps

- [x] Write a failing test in `IngestServiceTest` that constructs `IngestService` by opening a `LuceneRepository` and passing it in; confirm the test fails because the constructor still takes `embeddingModel + storeDir`.
- [x] Change `IngestService` constructor to `(repository: LuceneRepository, chunkSize, chunkOverlap, warningWriter, urlFetcher, tempDirProvider)`; remove `embeddingModel`, `storeDir`, `analyzerName` parameters; remove `val repository = LuceneRepository.open(...)` and `repository.use { }` from `ingest()`.
- [x] Update `McpIngestTool` constructor to `(repository: LuceneRepository, urlFetcher, ingestServiceFactory)`; update the default factory to `{ cs, co, fetcher -> IngestService(repository, cs, co, urlFetcher = fetcher) }`.
- [x] Update `McpServerCommand`: change line 67 from `McpIngestTool(embeddingModel, storeDir)` to `McpIngestTool(luceneRepository)`.
- [x] Update `IngestCommand.doCall()`: resolve `analyzerName` from `configService?.resolve()?.analyzer ?: "standard"`; wrap service creation in `LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo -> IngestService(repo, ...) }`.
- [x] Update `ReIngestService` line 97 (temporary bridge — `embeddingModel`/`storeDir`/`analyzerName` still exist as fields here): replace `IngestService(embeddingModel, storeDir, ...)` with `LuceneRepository.open(embeddingModel, storeDir, analyzerName).use { ingestRepo -> IngestService(ingestRepo, chunkSize, chunkOverlap, warningWriter, urlFetcher = urlFetcher).ingest(sourcesToReIngest) }`.
- [x] Update test helpers that use the old `IngestService(embeddingModel, storeDir, ...)` constructor: `McpIngestToolTest.makeTool()` anonymous subclass, `McpReIngestToolTest.ingestFile()`, `ReIngestServiceTest.ingestFile()`.
- [x] Run `./gradlew test` and confirm all tests pass.

### Acceptance criteria

- [x] `IngestServiceTest` passes when `IngestService` is constructed with an open `LuceneRepository` (no `embeddingModel` or `storeDir` parameters).
- [x] `McpIngestToolTest` passes with `McpIngestTool(repository, ...)` constructor — both factory-override tests and the disk-persistence/URL tests.
- [x] `IngestCommandTest` passes unchanged — the `ingest` CLI command still prints the summary line and produces a searchable Lucene index.
- [x] `./gradlew build` succeeds with no compilation errors (all callers of `IngestService` compile against the new constructor).
- [x] `IngestService.kt` contains no call to `LuceneRepository.open(...)` (verified by grep).
- [x] `IngestService.kt` declares no `EmbeddingModel` or `Path` constructor parameters (verified by inspection).

### Quality gates

- [x] `./gradlew test` exits 0 (all non-integration, non-eval tests pass).
- [x] `grep -r "IngestService(embeddingModel\|IngestService(model" src/main` returns no matches (ReIngestService calls are expected until Task 02).

---

## Task [02-reingest-service-repository-injection]

Fix the reingest write-lock end-to-end: `ReIngestService` accepts an injected `LuceneRepository`, removing the `stubEmbeddingModel()` workaround and both internal `open()` calls. The CLI command takes ownership of dimension-reset and repository lifecycle. The MCP tool and server wiring are updated. The temporary bridge introduced in Task 01 is removed.

_Depends on: Task 01_

### Implementation steps

- [x] Write a failing test in `ReIngestServiceTest` that constructs `ReIngestService` by opening a `LuceneRepository` and passing it in; confirm it fails because the constructor still takes `embeddingModel + storeDir`.
- [x] Change `ReIngestService` constructor to `(repository: LuceneRepository, chunkSize, chunkOverlap, warningWriter, urlFetcher)`; remove `embeddingModel`, `storeDir`, `analyzerName`; remove `stubEmbeddingModel()`; remove both `LuceneRepository.open(...)` calls and `use { }` blocks; remove the `LuceneRepository.resetStoredDimension(storeDir)` call; use the injected `repository` throughout. The `IngestService` call at the former line 97 bridge becomes `IngestService(repository, chunkSize, chunkOverlap, warningWriter, urlFetcher = urlFetcher)`.
- [x] Update `McpReIngestTool` constructor to `(repository: LuceneRepository, reIngestServiceFactory)`; update the default factory to `{ cs, co -> ReIngestService(repository, cs, co) }`.
- [x] Update `McpServerCommand`: change line 68 from `McpReIngestTool(embeddingModel, storeDir)` to `McpReIngestTool(luceneRepository)`.
- [x] Update `ReIngestCommand.call()`: resolve `analyzerName` from config; when `forceAllOption` is true call `LuceneRepository.resetStoredDimension(resolvedStoreDir)` before opening; wrap in `LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo -> ReIngestService(repo, ...) }`.
- [x] Update `McpReIngestToolTest.makeTool()` to use `McpReIngestTool(repository = openedRepo, ...)` and update any anonymous `ReIngestService` subclass construction inside.
- [x] Update `ReIngestServiceTest.createReIngestService()` to open a `LuceneRepository` and pass it in.
- [x] Run `./gradlew test` and confirm all tests pass.

### Acceptance criteria

- [x] `ReIngestServiceTest` passes when `ReIngestService` is constructed with an open `LuceneRepository`.
- [x] `McpReIngestToolTest` passes with `McpReIngestTool(repository, ...)` constructor.
- [x] `ReIngestCommandTest` passes — `--all` flag triggers dimension reset before the repository is opened; re-ingestion produces the correct summary line.
- [x] `./gradlew build` succeeds with no compilation errors.
- [x] `ReIngestService.kt` contains no `stubEmbeddingModel()` method (verified by grep).
- [x] `ReIngestService.kt` contains no call to `LuceneRepository.open(...)` (verified by grep).

### Quality gates

- [x] `./gradlew test` exits 0.
- [ ] ~~`grep -r "stubEmbeddingModel\|ReIngestService(embeddingModel\|ReIngestService(model" src/main` returns no matches.~~ *(skipped: `stubEmbeddingModel` still appears in unrelated `StatusCommand.kt` and `ListCommand.kt` — those are out of scope for this task; `ReIngestService` itself has no stub or old constructor)*
- [x] `grep -r "LuceneRepository.open" src/main/kotlin/ch/obermuhlner/ezrag/ingestion/ReIngestService.kt` returns no matches.

---

## Task [03-repository-write-thread-safety]

Make `LuceneRepository.add()` and `delete()` safe for concurrent MCP tool calls. Both methods update the in-memory `sourceMtimeCache` outside of Lucene's own synchronisation boundary; `@Synchronized` serialises those updates and prevents interleaved commits from corrupting the cache.

### Implementation steps

- [x] Write a failing concurrent test in `LuceneRepositoryTest`: open one `LuceneRepository`, spawn two threads each calling `add()` with a distinct batch; assert no exception and that the total chunk count equals the sum of both batch sizes. Confirm the test is flaky or fails without synchronisation (may require a stress run or a `@RepeatedTest`).
- [x] Add `@Synchronized` to `LuceneRepository.add()`.
- [x] Add `@Synchronized` to `LuceneRepository.delete()`.
- [x] Run `./gradlew test` and confirm all tests pass including the new concurrent test.

### Acceptance criteria

- [x] A unit test opens a single `LuceneRepository` instance, calls `add()` concurrently from two threads with non-overlapping batches, and asserts the final document count equals the combined batch size without throwing any exception.
- [x] `LuceneRepository.add()` carries the `@Synchronized` annotation (verified by source inspection).
- [x] `LuceneRepository.delete()` carries the `@Synchronized` annotation (verified by source inspection).
- [x] All pre-existing `LuceneRepositoryTest` tests still pass.

### Quality gates

- [x] `./gradlew test` exits 0.
- [x] `grep "@Synchronized" src/main/kotlin/ch/obermuhlner/ezrag/ingestion/LuceneRepository.kt` returns exactly two matches (one for `add`, one for `delete`).

---

## Task [04-write-lock-regression-tests]

Prove end-to-end that the write-lock bug is fixed: add tests to `McpIngestToolTest` and `McpReIngestToolTest` that exercise the tools the same way `McpServerCommand` does — a real shared `LuceneRepository` is opened first, the tool is constructed with it, and the tool call is made against a real on-disk index. These tests would have failed against the pre-fix code and must pass now.

_Depends on: Tasks 01, 02_

### Implementation steps

- [x] In `McpIngestToolTest`, add a test that opens a `LuceneRepository` (shared), constructs `McpIngestTool(repository)` with no factory override, writes a temp file, calls `ingest` with that file path, and asserts `filesIngested=1` and `chunksCreated>=1` in the returned JSON.
- [x] In the same test, verify the ingested document appears in the index by calling `repository.getChunksForFile(path)` and asserting the list is non-empty.
- [x] In `McpReIngestToolTest`, add a test that opens a `LuceneRepository` (shared), pre-ingests a temp file via `IngestService(repository, ...)`, modifies the file on disk (change content), constructs `McpReIngestTool(repository)` with no factory override, calls `reingest`, and asserts `filesReIngested=1`.
- [x] Run `./gradlew test` and confirm both new tests pass.

### Acceptance criteria

- [x] `McpIngestToolTest` contains a test that constructs `McpIngestTool` with a real `LuceneRepository` (no factory override) and asserts `filesIngested=1` after calling `ingest` on a real file.
- [x] That same test confirms the chunk is retrievable from the shared repository after the call.
- [x] `McpReIngestToolTest` contains a test that constructs `McpReIngestTool` with a real `LuceneRepository` (no factory override) and asserts `filesReIngested=1` after modifying the previously ingested file.
- [x] Neither test throws any exception (specifically no `write.lock` or `LockObtainFailedException`).

### Quality gates

- [x] `./gradlew test` exits 0.
- [x] `grep -n "McpIngestTool(repository\|McpIngestTool(repo" src/test/kotlin/ch/obermuhlner/ezrag/command/McpIngestToolTest.kt` returns at least one match that does not also contain `ingestServiceFactory`.
- [x] `grep -n "McpReIngestTool(repository\|McpReIngestTool(repo" src/test/kotlin/ch/obermuhlner/ezrag/command/McpReIngestToolTest.kt` returns at least one match that does not also contain `reIngestServiceFactory`.

---

## Task [05-concurrent-delete-thread-safety]

Extend the concurrent thread-safety coverage in `LuceneRepositoryTest` to verify that `@Synchronized` on `delete()` is correct. Task 03 added a concurrent `add()` test; this task adds concurrent `delete()` and concurrent `add()`+`delete()` tests to confirm the synchronisation holds when deletes are involved.

_Depends on: Task 03_

### Implementation steps

- [x] In `LuceneRepositoryTest`, add a test that pre-ingests two distinct sources, then spawns two threads that each call `delete()` on a different source simultaneously; assert no exception and that both sources are absent from the index afterwards.
- [x] Add a second test that pre-ingests one source, then spawns two threads simultaneously — one calls `add()` with a new batch for a new source, the other calls `delete()` on the pre-ingested source; assert no exception and that the final index contains exactly the added batch and not the deleted source.
- [x] Run `./gradlew test` and confirm both new tests pass.

### Acceptance criteria

- [x] A unit test opens a single `LuceneRepository`, calls `delete()` concurrently from two threads on different sources, and asserts no exception is thrown and both sources are absent after completion.
- [x] A unit test opens a single `LuceneRepository`, calls `add()` and `delete()` concurrently (on distinct sources), and asserts no exception is thrown and the final index reflects both operations correctly.
- [x] All pre-existing `LuceneRepositoryTest` tests continue to pass.

### Quality gates

- [x] `./gradlew test` exits 0.
- [x] The two new tests are placed in the `// --- concurrent add thread safety ---` section of `LuceneRepositoryTest` (or a clearly labelled equivalent section).
