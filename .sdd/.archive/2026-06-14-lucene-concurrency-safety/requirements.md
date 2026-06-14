# Lucene Concurrency Safety

## Problem Statement

When more than one ez-rag process targets the same store directory simultaneously — for example, an MCP server already running while a user also runs a CLI `ingest` command, or two CLI processes started in parallel — Lucene's single-writer constraint causes the second process to crash with an opaque lock error. Users receive no actionable guidance, and write operations on MCP tools may fail silently or with confusing messages. The MCP server also holds the Lucene write lock for its entire lifetime even between tool calls, unnecessarily blocking all other writers.

## Solution

All execution modes (CLI commands, MCP tool calls, and the interactive shell) switch to opening the Lucene store on a per-request basis and closing it immediately after each operation completes. A configurable retry-with-timeout mechanism is introduced so that transient lock contention resolves automatically rather than failing immediately. When the timeout expires, a clear, actionable error message tells the user what to do. A global `--lock-timeout` flag controls the retry window across all subcommands.

## User Stories

1. As a developer running an MCP server, I want CLI ingest commands to retry briefly when the write lock is busy, so that short-lived contention resolves without manual intervention.
2. As a developer, I want a clear error message when the write lock cannot be acquired within the timeout, so that I know another ez-rag process is holding the store.
3. As a developer, I want to set `--lock-timeout 0` to disable retries and fail immediately, so that scripts can detect contention and handle it themselves.
4. As a developer, I want to configure the lock timeout once at the command level via `--lock-timeout`, so that I do not have to repeat it for every subcommand.
5. As a developer running multiple CLI ingest commands in parallel, I want them to queue naturally via the lock timeout rather than crash, so that batch scripts can run without coordination logic.
6. As an operator, I want the MCP server to validate that the store directory exists at startup, so that misconfiguration is caught immediately rather than on the first tool call.
7. As an operator, I want the MCP server to release the write lock between tool calls, so that CLI commands and other processes can write to the store while the server is idle.
8. As a developer using the interactive shell, I want each query to open and close the store independently, so that the shell does not hold the write lock for its entire session.
9. As a developer, I want write operations inside `reingest --all` to be serialised the same way as `ingest` and `delete`, so that concurrent calls cannot corrupt the index or its in-memory cache.
10. As a developer, I want the retry interval to be short (100 ms), so that the lock is acquired as soon as it becomes available without noticeable delay.
11. As a developer using the MCP `ingest` tool, I want concurrent ingest and search calls to the same MCP server to work correctly, so that I can search while a background ingest is in progress.
12. As a developer, I want a `--lock-timeout` of 30 seconds by default, so that typical ingest operations (embedding model calls included) have enough time to complete before the waiter gives up.

## User Acceptance Tests

1. Given an MCP server is running against a store, when a CLI `ingest` command targets the same store, then the CLI command succeeds (possibly after a brief wait) and the document is visible on the next search.
2. Given an MCP server is running against a store, when a CLI `ingest` command targets the same store and `--lock-timeout 0` is set, then the CLI command fails immediately with a message that names the store path and states that the write lock is held.
3. Given two `ez-rag ingest` CLI commands are started simultaneously against the same store, when both complete, then all documents from both commands are present in the store.
4. Given the MCP server is started against a non-existent store directory, when the server starts, then it exits with a clear error describing the missing store before accepting any tool calls.
5. Given the MCP server is idle (no tool calls in progress), when a CLI `ingest` command runs against the same store, then the CLI command acquires the write lock and completes successfully.
6. Given a `reingest --all` operation is in progress via the MCP server, when a second MCP `ingest` call arrives concurrently, then the second call waits and both complete without index corruption.
7. Given the interactive shell is open against a store, when a CLI `ingest` command targets the same store between shell queries, then the ingest succeeds and the next shell query returns the newly ingested content.
8. Given `--lock-timeout 5` is set on the root command, when a lock cannot be acquired within 5 seconds, then the operation fails with a message stating the timeout in seconds and the store path.
9. Given `--lock-timeout 0` is set, when the write lock is already held, then the operation fails immediately (within one polling cycle, i.e., under 200 ms) with a clear error.
10. Given a valid store exists, when any MCP tool call completes, then the write lock is released and another process can immediately open the store.

## Definition of Done

- All user acceptance tests pass.
- The `--lock-timeout` flag is documented in `--help` output and in `README.md`.
- No regression in existing CLI commands, MCP tool tests, or shell command tests.
- The write lock is never held across idle periods between MCP tool calls.
- All automated tests in scope pass without the `integration` or `eval` tags.
- `dropAllDocuments()` is synchronised consistently with `add()` and `delete()`.

## Out of Scope

- Preventing two long-running MCP server processes from targeting the same store simultaneously (they will contend via the lock timeout like any other process pair).
- A read/write split that allows concurrent readers without the write lock (not needed at the expected index scale of under a few hundred documents).
- Distributed locking across machines or networked file systems.
- Connection pooling or persistent repository instances for performance optimisation.

## Further Notes

- Lucene's `LockObtainFailedException` is the exception to catch in the retry loop; it is thrown immediately when the write lock cannot be acquired.
- At the expected index scale (< 100 documents, max a few hundred), the overhead of opening and closing the repository on every request is negligible.
- The MCP server's startup validation should use `LuceneRepository.storeExists()`, which does not acquire the write lock.

---

## Technical Annex
> Written against codebase as of: 2026-06-13

### Architectural Decisions

#### 1. `LuceneRepository.openWithRetry()` — new companion function

Add alongside the existing `open()` in `LuceneRepository.kt`:

```kotlin
companion object {
    fun openWithRetry(
        embeddingModel: EmbeddingModel,
        storeDir: Path,
        analyzerName: String,
        timeoutSeconds: Int,
    ): LuceneRepository {
        // timeoutSeconds == 0 → single attempt, fail immediately
        // otherwise retry every 100 ms until timeoutSeconds elapsed
        // catch LockObtainFailedException, rethrow as IllegalStateException
        // with message: "Could not acquire write lock on store '<storeDir>' after <N>s — another ez-rag process may be running"
    }
}
```

Polling interval: 100 ms fixed. Does NOT use exponential backoff.

#### 2. `LuceneRepository.dropAllDocuments()` — add `@Synchronized`

Current: no synchronisation (line 294 in `LuceneRepository.kt`).
Fix: add `@Synchronized` annotation to match `add()` (line 165) and `delete()` (line 277).

#### 3. `EzRagCommand` — new global `--lock-timeout` flag

Add to `EzRagCommand.kt`:

```kotlin
@Option(
    names = ["--lock-timeout"],
    description = ["Seconds to retry acquiring the write lock (0 = fail immediately, default: 30)."],
    scope = ScopeType.INHERIT
)
var lockTimeout: Int = 30
```

All subcommands inherit this value via `ScopeType.INHERIT` (same pattern as `--verbose` and `--stack-trace`).

#### 4. `StoreConfig` — new data class

New file (or companion to `LuceneRepository`):

```kotlin
data class StoreConfig(
    val embeddingModel: EmbeddingModel,
    val storeDir: Path,
    val analyzerName: String,
    val lockTimeoutSeconds: Int,
)
```

Carries all parameters needed by MCP tools to open a repository per-request.

#### 5. MCP Tools — constructor change from `LuceneRepository` to `StoreConfig`

All five tools in `src/main/kotlin/ch/obermuhlner/ezrag/command/`:

| Tool | Current constructor param | New constructor param |
|------|--------------------------|----------------------|
| `McpListTool` | `repository: LuceneRepository` | `storeConfig: StoreConfig` |
| `McpSearchTool` | `hybridSearchPipeline: HybridSearchPipeline` | `storeConfig: StoreConfig` |
| `McpIngestTool` | `repository: LuceneRepository` | `storeConfig: StoreConfig` |
| `McpReIngestTool` | `repository: LuceneRepository` | `storeConfig: StoreConfig` |
| `McpChunkTool` | `repository: LuceneRepository` | `storeConfig: StoreConfig` |

Each tool's handler method opens the repository per-request:

```kotlin
fun someToolMethod(...): Result {
    LuceneRepository.openWithRetry(
        storeConfig.embeddingModel,
        storeConfig.storeDir,
        storeConfig.analyzerName,
        storeConfig.lockTimeoutSeconds,
    ).use { repo ->
        // existing logic using repo
    }
}
```

`McpSearchTool` creates `HybridSearchPipeline(repo)` inside the `use` block (was previously passed as constructor parameter).

#### 6. `McpServerCommand` — remove shared repository, add startup validation

`mcpToolCallbackProvider()` in `McpServerCommand.kt` currently:
- Opens `LuceneRepository.open(embeddingModel, storeDir, analyzer)` at line 61 and passes it to all tools.

After this change:
- Builds a `StoreConfig` from `embeddingModel`, `storeDir`, `analyzer`, and `lockTimeout` (from inherited `EzRagCommand.lockTimeout`).
- Validates store existence using `LuceneRepository.storeExists(storeDir)` — no write lock acquired.
- Passes `StoreConfig` to all 5 tools.
- Does NOT call `LuceneRepository.open()` at startup.

`call()` in `McpServerCommand.kt` should fail fast with a helpful message if the store does not exist, before entering the blocking latch.

#### 7. `ShellCommand` — per-request repository

`ShellCommand.kt` currently opens `LuceneRepository.open(embeddingModel, storeDir, "standard")` once at line 91 and holds it for the session.

After this change: store config parameters are captured at startup; each REPL loop iteration that requires index access opens and closes the repository via `openWithRetry` inside a `use` block.

The shell still validates store existence at startup via `LuceneRepository.storeExists(storeDir)` before entering the REPL loop (this check is already present at line 69).

### Automated Testing Decisions

**What makes a good test here:** test observable outcomes (lock acquired or not, error message content, index state after concurrent operations) rather than internal retry-loop mechanics. Do not assert on poll count or timing beyond coarse bounds.

**Tests to write or update:**

| Test class | Type | What to cover |
|------------|------|---------------|
| `LuceneRepositoryTest` | Unit | `openWithRetry` with `timeoutSeconds=0` fails immediately when lock is held; `openWithRetry` with positive timeout succeeds once lock is released; error message contains store path and timeout value; `dropAllDocuments()` is safe under concurrent calls (extend existing concurrent tests at lines 509–655) |
| `McpIngestToolTest` | Unit | Constructor now takes `StoreConfig`; tool opens and closes repository per call; existing ingest behaviour unchanged |
| `McpReIngestToolTest` | Unit | Constructor now takes `StoreConfig`; per-request lifecycle; existing reingest behaviour unchanged |
| `McpListToolTest` | Unit | Constructor now takes `StoreConfig`; per-request lifecycle |
| `McpSearchToolTest` | Unit | Constructor now takes `StoreConfig`; pipeline created per-request |
| `McpChunkToolTest` | Unit | Constructor now takes `StoreConfig`; per-request lifecycle |
| `McpServerCommandTest` | Unit | Startup validation fails with clear error when store does not exist; `StoreConfig` is passed to tools (not a shared repository) |
| `ShellCommandTest` | Unit | Each REPL query opens/closes its own repository; shell does not hold write lock between queries |

**Prior art:** Existing concurrent tests in `LuceneRepositoryTest.kt` (lines 509–655) are the model for any new concurrency tests. Existing `McpIngestToolTest.kt` and similar files show the constructor-injection pattern for unit testing tools in isolation.
