# Tasks: lucene-concurrency-safety

## Task 01-openWithRetry-and-dropAllDocuments-sync

Adds the core retry mechanism to `LuceneRepository`. When the write lock is held by another process, `openWithRetry()` retries every 100 ms up to a configurable timeout rather than surfacing an opaque Lucene error. `dropAllDocuments()` gains the same `@Synchronized` annotation that `add()` and `delete()` already have, preventing data races when it is called concurrently on the same repository instance.

### Implementation steps

- [x] Write a failing `LuceneRepositoryTest` test: open a real `LuceneRepository` on a `@TempDir` (hold it unclosed), then call `openWithRetry` with `timeoutSeconds=0` on the same directory and assert it throws `IllegalStateException` with the store path in the message
- [x] Verify the test fails (`./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.LuceneRepositoryTest"`)
- [x] Add `openWithRetry(embeddingModel, storeDir, analyzerName, timeoutSeconds)` to the `LuceneRepository` companion object: catch `LockObtainFailedException`; when `timeoutSeconds=0` fail immediately; otherwise retry every 100 ms until elapsed; rethrow as `IllegalStateException` with message "Could not acquire write lock on store '<storeDir>' after <N>s — another ez-rag process may be running"
- [x] Add `@Timeout(1, unit = TimeUnit.SECONDS)` to the `timeoutSeconds=0` test so slow CI machines do not silently violate the 200 ms bound
- [x] Write additional tests: positive timeout succeeds once the blocking instance is closed within the timeout; error message contains both store path and timeout duration
- [x] Add `@Synchronized` to `dropAllDocuments()` in `LuceneRepository`
- [x] Extend concurrent `LuceneRepositoryTest` tests (model after existing tests at lines 509–655): two threads calling `dropAllDocuments()` and `add()` concurrently on the **same** `LuceneRepository` instance do not corrupt the index; verify expected document count after all threads complete

### Acceptance criteria

- [x] `openWithRetry(timeoutSeconds=0)` throws `IllegalStateException` within 200 ms when another `LuceneRepository` holds the write lock on the same directory
- [x] The exception message contains the store path and the elapsed wait time in seconds
- [x] `openWithRetry(timeoutSeconds=5)` returns a usable `LuceneRepository` once the competing instance is closed within 5 seconds
- [x] `dropAllDocuments()` concurrent with `add()` and `delete()` on the same instance does not corrupt the document count (new concurrent test passes)
- [x] All pre-existing concurrent tests in `LuceneRepositoryTest` still pass

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.LuceneRepositoryTest"` passes with no failures


---

## Task 02-lock-timeout-flag

Adds a global `--lock-timeout` flag to `EzRagCommand` that all subcommands inherit via `ScopeType.INHERIT`. This is a prerequisite for tasks 03 and 04, which pass the value to `openWithRetry()`.

### Implementation steps

- [x] Write a failing test: parse `ez-rag --lock-timeout 10 ingest` via `CommandLine` and assert the inherited `lockTimeout` value on the subcommand is 10; also assert the default is 30 when the flag is omitted
- [x] Verify the test fails
- [x] Add `--lock-timeout` option to `EzRagCommand` with `ScopeType.INHERIT`, type `Int`, default `30`, description "Seconds to retry acquiring the write lock (0 = fail immediately, default: 30)."
- [x] Run `./gradlew test` to confirm no existing test is broken by the new flag

### Acceptance criteria

- [x] `ez-rag --help` output includes `--lock-timeout` with description and default value of 30
- [x] A subcommand can read the inherited `lockTimeout` value when `--lock-timeout` is passed on the root command
- [x] The default value is 30 when the flag is not supplied

### Quality gates

- [x] `./gradlew test` passes with no failures


---

## Task 03-mcp-server-per-request-lifecycle

The MCP server no longer holds a shared `LuceneRepository` write lock for its entire lifetime. Instead, `McpServerCommand` validates the store directory at startup (without acquiring the write lock) and builds a `StoreConfig` carrying all parameters needed to open a repository. Each MCP tool call opens its own repository via `openWithRetry()` and closes it before returning. `McpSearchTool` creates `HybridSearchPipeline` inside the `use` block.

### Implementation steps

- [x] Write a failing `McpServerCommandTest` test: construct `McpServerCommand` pointing at a non-existent directory, invoke `call()` directly, assert it returns a non-zero exit code and the error output contains the missing path
- [x] Verify the test fails
- [x] Create `StoreConfig` data class with fields `embeddingModel: EmbeddingModel`, `storeDir: Path`, `analyzerName: String`, `lockTimeoutSeconds: Int`
- [x] Add startup validation in `McpServerCommand.call()` using `LuceneRepository.storeExists(storeDir)`; print a clear error and return non-zero before the blocking latch when the store is absent
- [x] Refactor `McpServerCommand.mcpToolCallbackProvider()` to build a `StoreConfig(embeddingModel, storeDir, analyzer, lockTimeout)` and pass it to all five tools — do NOT call `LuceneRepository.open()` here
- [x] Update `McpIngestTool` constructor to accept `StoreConfig`; in the handler method open a repository inside `LuceneRepository.openWithRetry(...).use { repo -> ... }`; update `McpIngestToolTest` — when injecting an `IngestService` factory, ensure the factory uses the `repo` opened inside the `use` block, not a captured outer instance
- [x] Update `McpSearchTool` constructor to accept `StoreConfig`; create `HybridSearchPipeline(repo)` inside the `use` block; update `McpSearchToolTest`
- [x] Update `McpListTool`, `McpReIngestTool`, `McpChunkTool` constructors to accept `StoreConfig`; open per-request; update their tests
- [x] Update `McpServerCommandTest` to assert `mcpToolCallbackProvider()` does not call `LuceneRepository.open()` (i.e., no shared repository is created at provider build time)
- [x] Update `README.md` to document `--lock-timeout` in the CLI reference section

### Acceptance criteria

- [x] `McpServerCommand.call()` returns non-zero and prints a message containing the store path when the store directory does not exist
- [x] `McpServerCommand.mcpToolCallbackProvider()` does not call `LuceneRepository.open()` (verified by test or code inspection)
- [x] All five MCP tools take `StoreConfig` in their constructor (no direct `LuceneRepository` parameter)
- [x] `McpIngestTool`, `McpReIngestTool`, `McpListTool`, `McpSearchTool`, and `McpChunkTool` each produce the same results as before the refactoring (existing behavioral tests pass)
- [x] `README.md` documents `--lock-timeout`

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpServerCommandTest"` passes
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpIngestToolTest"` passes
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpSearchToolTest"` passes
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpListToolTest"` passes
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpReIngestToolTest"` passes
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.McpChunkToolTest"` passes
- [x] `./gradlew test` passes (no regressions)


---

## Task 04-shell-per-request-lifecycle

Each `ShellCommand` REPL query — including the main RAG query, `/status`, `/search`, `/search-bm25`, and `/search-embedding` — opens its own `LuceneRepository` via `openWithRetry()` and closes it before returning control to the prompt. The shell never holds the write lock between queries, allowing concurrent CLI ingest commands to run while the shell is idle.

### Implementation steps

- [x] Write a failing `ShellCommandTest` test: create a shell backed by a real `@TempDir` store (pre-seeded via `LuceneRepository.open().use { ... }`), run one RAG query through the shell REPL, then immediately open a second `LuceneRepository` on the same directory after the query returns and assert it succeeds (proving the write lock was released); this test must pass an `EmbeddingModel` stub via `storeDirOverride` + `springEmbeddingModel` injection path, not via a pre-built pipeline, to exercise the per-request open path
- [x] Verify the test fails
- [x] Update `ShellCommand.call()` to capture `embeddingModel`, `storeDir`, and `analyzerName` at startup without opening a repository; remove the single `LuceneRepository.open()` call at the start of the `else` branch
- [x] Update the RAG query branch to open `LuceneRepository.openWithRetry(...)` inside a `use` block, build pipelines from it, execute the query, and close — all per iteration
- [x] Update `handleSlashCommand` signature to remove `repository: LuceneRepository?` and instead accept the store config parameters or a factory lambda; each slash command branch that accesses the index opens its own repository per call
- [x] Update existing `ShellCommandTest` tests that inject pre-built pipelines to verify they still pass (the pre-built-pipeline path is a legitimate test-only shortcut and should remain)
- [x] Verify that `/status`, `/search`, `/search-bm25`, `/search-embedding` still produce correct output in tests

### Acceptance criteria

- [x] Shell validates store existence at startup via `LuceneRepository.storeExists(storeDir)` and exits with a clear message if the store is missing
- [x] After any REPL query completes, a second process can immediately open the same store (the write lock is released)
- [x] `/status`, `/search`, `/search-bm25`, `/search-embedding` slash commands work correctly and release the write lock after each invocation
- [x] All pre-existing `ShellCommandTest` tests pass

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.ShellCommandTest"` passes
- [x] `./gradlew test` passes (no regressions)
