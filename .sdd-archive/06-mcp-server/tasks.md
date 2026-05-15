# Tasks: 06-mcp-server

## Task [01-mcp-server-bootstrap]

Wire the Spring AI MCP server starter for stdio transport and make `McpServerCommand` start a long-running MCP server process. The command activates the MCP server, which reads JSON-RPC from stdin and writes responses to stdout. No tools are registered yet—the server correctly responds to the `initialize` handshake and `tools/list` returns an empty list. Logging is routed exclusively to stderr so stdout carries only MCP protocol traffic.

### Implementation steps

- [x] Add `spring-ai-mcp-server-spring-boot-starter` to `build.gradle.kts` dependencies
- [x] Configure stdio transport in `application.yml` (conditional on MCP server mode, e.g. via Spring profile or property activated by `McpServerCommand`)
- [x] Update `McpServerCommand.call()` to activate the MCP profile/configuration and block until process termination (the stdio server lifecycle keeps the process alive)
- [x] Configure Logback so all appenders write to stderr (not stdout) when running in MCP server mode; add a startup assertion that no root-level appender targets `System.out`

### Acceptance criteria

- [x] `McpServerCommand` bean is present in the Spring application context
- [x] Sending a valid JSON-RPC `initialize` request to stdin produces a well-formed JSON-RPC response on stdout
- [x] A `tools/list` request returns an empty `tools` array (no tools registered yet)
- [x] No bytes appear on stdout during server startup before the first JSON-RPC response
- [x] Log messages emitted during startup appear on stderr, not stdout
- [x] The process remains alive after `initialize` completes and does not exit

### Quality gates

- [x] Kotlin compiler reports zero warnings (`-Werror` flag passes)
- [x] `./gradlew test` passes with no new test failures

---

## Task [02-mcp-status-tool]

Add the `status` MCP tool. When a client calls this tool, it loads the vector store from disk and returns structured store metadata (store path, chunk count, document list). The vector store is loaded once at `McpServerCommand` startup and kept in memory for the server lifetime. The tool appears in `tools/list`.

### Implementation steps

- [x] Define `StoreStatus` data class mirroring `StoreMetadata` fields (or reuse `StoreMetadata` directly as the return type)
- [x] Inject `VectorStoreRepository` into `McpServerCommand` (or a dedicated `McpTools` bean) and call `load()` on startup
- [x] Implement a `@Tool`-annotated `status()` method that calls `repository.getMetadata()` and returns `StoreStatus`
- [x] Catch exceptions and return a structured error message rather than letting them propagate unhandled
- [x] Write a unit test with a mocked `VectorStoreRepository`

### Acceptance criteria

- [x] `tools/list` response contains a tool named `status`
- [x] Calling `status` when the store exists returns an object with `storePath`, `chunkCount`, and `documents` fields populated from `VectorStoreRepository.getMetadata()`
- [x] Calling `status` when no store file exists returns an error response with a human-readable message instead of an unhandled exception
- [x] Unit test: mocked `VectorStoreRepository.getMetadata()` result maps correctly to `StoreStatus` return value
- [x] Unit test: mocked `VectorStoreRepository` throwing an exception results in a structured MCP error response

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no new test failures

---

## Task [03-mcp-search-tool]

Add the `search` MCP tool. When called with `question`, optional `topK`, and optional `minScore`, it delegates to `EmbeddingSearchPipeline` and returns the matching chunks as `SearchResult`. The tool appears in `tools/list`.

### Implementation steps

- [x] Inject `EmbeddingSearchPipeline` into the MCP tools bean (construct it from the available `EmbeddingModel` and `VectorStoreRepository` beans)
- [x] Implement a `@Tool`-annotated `search(question: String, topK: Int?, minScore: Double?)` method that builds a `SearchQuery` with defaults (topK=5, minScore=0.0) and calls `EmbeddingSearchPipeline.search()`
- [x] Catch exceptions and return a structured error response
- [x] Write a unit test with a mocked `EmbeddingSearchPipeline`

### Acceptance criteria

- [x] `tools/list` response contains a tool named `search`
- [x] Calling `search` with only `question` uses default topK and minScore values forwarded to `EmbeddingSearchPipeline`
- [x] Calling `search` with explicit `topK` and `minScore` forwards those values to `EmbeddingSearchPipeline`
- [x] Calling `search` returns an object with a `chunks` field containing the items from `SearchResult.chunks`
- [x] Unit test: assert `EmbeddingSearchPipeline.search()` is called with the exact `SearchQuery` built from tool parameters
- [x] Unit test: pipeline throwing an exception results in a structured MCP error response

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no new test failures

---

## Task [04-mcp-query-tool]

Add the `query` MCP tool. When called with `question`, optional `topK`, optional `provider`, and optional `model`, it delegates to `RagPipeline` and returns `RagResult` (answer + sources). The tool appears in `tools/list`.

### Implementation steps

- [x] Inject `RagPipeline` into the MCP tools bean (construct from `VectorStoreRepository` and `ChatModel` beans)
- [x] Implement a `@Tool`-annotated `query(question: String, topK: Int?, provider: String?, model: String?)` method that builds a `RagQuery` with defaults and calls `RagPipeline.query()`
- [x] Catch exceptions and return a structured error response
- [x] Write a unit test with a mocked `RagPipeline`

### Acceptance criteria

- [x] `tools/list` response contains a tool named `query`
- [x] Calling `query` with only `question` uses default topK and no model override
- [x] Calling `query` with `topK` and `model` forwards those values to the `RagQuery`
- [x] Return value contains `answer` (String) and `sources` (list of source references with `filePath`, `chunkIndex`, `similarityScore`, `excerpt`)
- [x] Unit test: assert `RagPipeline.query()` is called with the exact `RagQuery` built from tool parameters
- [x] Unit test: pipeline throwing an exception results in a structured MCP error response

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no new test failures

---

## Task [05-mcp-ingest-service]

Extract a reusable `IngestService` from `IngestCommand` so that both the CLI command and the upcoming MCP ingest tool can delegate to it without duplicating business logic. `IngestCommand` becomes a thin wrapper. Define `IngestResult(filesIngested: Int, chunksCreated: Int, skipped: Int)`.

### Implementation steps

- [x] Define `IngestResult` data class in the `ingestion` or `command` package
- [x] Create `IngestService` that accepts `EmbeddingModel`, store path, `chunkSize`, `chunkOverlap` and exposes `ingest(files: List<File>): IngestResult` reusing `DocumentLoader`, `DocumentChunker`, `DirectoryWalker`, and `VectorStoreRepository`
- [x] Refactor `IngestCommand.call()` to delegate to `IngestService` and print the `IngestResult` to the output writer
- [x] Ensure all existing `IngestCommand` tests still pass without modification

### Acceptance criteria

- [x] `IngestService` exists as a standalone class with no dependency on picocli or `PrintWriter`
- [x] `IngestCommand` no longer contains the file-walking and chunking loop—it delegates to `IngestService`
- [x] `IngestService.ingest()` returns an `IngestResult` with correct counts: files ingested, chunks created, and files skipped because already up to date
- [x] All previously passing `IngestCommand` tests continue to pass without modification
- [x] A unit test for `IngestService` asserts `IngestResult` counts with mocked `VectorStoreRepository`

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no new test failures (no regressions)

---

## Task [06-mcp-ingest-tool]

Add the `ingest` MCP tool. When called with `path`, optional `chunkSize`, and optional `chunkOverlap`, it delegates to `IngestService` (from Task 05), persists the updated store immediately by calling `VectorStoreRepository.save()`, and returns `IngestResult`. The tool appears in `tools/list`.

### Implementation steps

- [x] Inject `IngestService` (or construct it) into the MCP tools bean using the same `EmbeddingModel` and store path as the other tools
- [x] Implement a `@Tool`-annotated `ingest(path: String, chunkSize: Int?, chunkOverlap: Int?)` method that resolves defaults, calls `IngestService.ingest()`, and returns `IngestResult`
- [x] Ensure `VectorStoreRepository.save()` is called after each successful ingest so data is persisted within the server process
- [x] Catch exceptions and return a structured error response
- [x] Write a unit test with a mocked `IngestService`

### Acceptance criteria

- [x] `tools/list` response contains a tool named `ingest`
- [x] Calling `ingest` with a valid `path` forwards path, chunkSize (default 1000), and chunkOverlap (default 200) to `IngestService`
- [x] After a successful `ingest` call, the vector store file on disk is updated (save is called)
- [x] Return value contains `filesIngested`, `chunksCreated`, and `skipped` fields from `IngestResult`
- [x] Unit test: assert `IngestService.ingest()` is called with the resolved parameters and save is called on `VectorStoreRepository`
- [x] Unit test: `IngestService` throwing an exception results in a structured MCP error response

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no new test failures

---

## Task [07-mcp-readme-docs]

Update the README with all information a user needs to add `ez-rag` as an MCP server: the config snippet for `.claude/mcp.json`, the list of available MCP tools with their input parameters and output shapes, and the available startup flags (`--provider`, `--embedding-provider`, `--store`).

### Implementation steps

- [x] Add a dedicated "MCP Server" section to `README.md`
- [x] Include the JSON config snippet for `.claude/mcp.json` with `command: "ez-rag"` and `args: ["mcp-server"]`
- [x] Document each of the four MCP tools: `status`, `search`, `query`, `ingest` with their parameters, defaults, and return shapes
- [x] Document `--provider`, `--embedding-provider`, `--store`, and `--verbose` flags applicable to `mcp-server`
- [x] Note the requirement that the vector store must be ingested before calling `status`, `search`, or `query`

### Acceptance criteria

- [x] README contains a `mcp-server` subcommand section with the `.claude/mcp.json` snippet
- [x] Each MCP tool (`status`, `search`, `query`, `ingest`) is described with its input parameters and return fields
- [x] Startup flags for `mcp-server` are documented
- [x] The note about pre-ingesting the store before querying is present

### Quality gates

- [x] `./gradlew test` passes (no regressions from doc-only changes)
