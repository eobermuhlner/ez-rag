# Tasks: BM25 Search and Hybrid Search

## Task [01-store-directory-layout]

Migrate the store path abstraction from pointing directly at `vector-store.json` to pointing at a
store directory. This is a prerequisite for BM25: the Lucene index will live in `<storeDir>/lucene/`
alongside `vector-store.json`. All existing ingest, search, query, status, shell, and MCP workflows
must continue to work unchanged after this refactoring.

### Implementation steps

- [ ] Change `VectorStoreRepository` constructor to accept a store directory `Path`; derive `<storeDir>/vector-store.json` internally for all load/save operations
- [ ] Update `EzRagConfig.storePath` default from `.ez-rag/vector-store.json` to `.ez-rag`
- [ ] Update every command (`SearchCommand`, `QueryCommand`, `IngestCommand`, `StatusCommand`, `ShellCommand`, MCP tools) to pass a directory path to `VectorStoreRepository` and remove any hardcoded `vector-store.json` suffix
- [ ] Update all affected tests to use the directory form of the path

### Acceptance criteria

- [ ] `VectorStoreRepositoryTest`: creates a repo with a `@TempDir` directory path, adds documents, saves, creates a new repo instance pointing to the same directory, loads, and asserts all documents are present
- [ ] `VectorStoreRepositoryTest`: `storeExists()` returns `false` when the directory contains no `vector-store.json` and `true` after the first `save()`
- [ ] `ConfigServiceTest`: `resolve()` with no overrides returns an `EzRagConfig` where `storePath == ".ez-rag"`
- [ ] `SearchCommandTest`: when constructed with a store directory path pointing to a populated store, `call()` returns exit code `0`
- [ ] `./gradlew test` passes with no failures

### Quality gates

- [ ] No Kotlin compiler warnings (`-Werror` is already enforced)
- [ ] No literal string `.ez-rag/vector-store.json` remains anywhere in `src/main/`

---

## Task [02-config-searchmode-and-analyzer]

Expose `search-mode` and `analyzer` as first-class configuration fields in `EzRagConfig`, resolved
through the full CLI > environment variable > config-file > default precedence chain. Commands read
mode and analyzer from the resolved config rather than hardcoding them.

### Implementation steps

- [ ] Add `searchMode: String = "hybrid"` and `analyzer: String = "standard"` to `EzRagConfig`
- [ ] Add nullable `searchMode: String?` and `analyzer: String?` to `CliFlags`
- [ ] Update `ConfigService.resolve()` to wire `searchMode` through `cliFlags.searchMode ?: envVars["SEARCH_MODE"] ?: file.searchMode` and `analyzer` through `cliFlags.analyzer ?: envVars["ANALYZER"] ?: file.analyzer`
- [ ] Update `ConfigFileReader` to parse `search-mode` (and the camelCase alias `searchMode`) and `analyzer` from the YAML config file
- [ ] Verify that every command that will route on search mode reads it from the resolved config (placeholder routing is fine here — full routing comes in later tasks)

### Acceptance criteria

- [ ] `ConfigServiceTest`: `searchMode` resolves to the CLI flag value when the flag is set; to `SEARCH_MODE` env var when only the env var is set; to the config-file value when only the file sets it; to `"hybrid"` when no source specifies it
- [ ] `ConfigServiceTest`: the same four-level precedence holds for `analyzer`; default is `"standard"`
- [ ] `ConfigFileReaderTest` (extend existing test): YAML entry `search-mode: bm25` is parsed and produces `EzRagConfig.searchMode == "bm25"`
- [ ] `ConfigFileReaderTest`: YAML entry `analyzer: english` is parsed and produces `EzRagConfig.analyzer == "english"`
- [ ] `./gradlew test` passes

### Quality gates

- [ ] No Kotlin compiler warnings
- [ ] No command or pipeline class contains a literal string `"hybrid"` or `"standard"` for mode or analyzer selection — all values come from the resolved `EzRagConfig`

---

## Task [03-bm25-indexing-and-keyword-search]

Deliver end-to-end BM25 keyword search: ingesting documents also writes a Lucene index, and the user
can run `ez-rag search --mode bm25 "query"` or `ez-rag query --mode bm25 "query"` to retrieve
keyword-matched chunks. Re-ingesting a changed file deletes old Lucene documents and re-indexes
atomically. The `analyzer` from resolved config governs both indexing and querying.

### Implementation steps

- [ ] Add `org.apache.lucene:lucene-core` and `org.apache.lucene:lucene-analysis-common` (version 9.x) to `build.gradle.kts`
- [ ] Implement `BM25Repository(storeDir: Path, analyzerName: String)` with operations: `index(documents)`, `deleteBySource(sourcePath)`, `search(query, topK)`, `isAlreadyIndexed(sourcePath, mtime)`, `getMetadata()`, `close()`; store the Lucene index at `<storeDir>/lucene/` and the mtime sidecar at `<storeDir>/lucene/meta.json`
- [ ] Add `mode: String` field to `SearchQuery` (default `"embedding"` for backward compatibility) and `SearchResult`
- [ ] Implement `BM25SearchPipeline` as a thin wrapper around `BM25Repository`
- [ ] Wire `BM25Repository` into `IngestService`: call `BM25Repository.isAlreadyIndexed` alongside `VectorStoreRepository.isAlreadyIngested` to skip already-indexed files; on ingest call `deleteBySource` then `index` to keep BM25 in sync with the vector store
- [ ] Add `--mode` option to `SearchCommand` and `QueryCommand`; read the default from the resolved `EzRagConfig.searchMode`; route to `BM25SearchPipeline` when mode is `"bm25"`, keep existing embedding pipeline for `"embedding"`
- [ ] `--min-score` is applied only when `mode == "embedding"`; silently ignored for other modes
- [ ] Include the `mode` field in JSON output from `OutputFormatter`

### Acceptance criteria

- [ ] `BM25RepositoryTest`: index two documents each containing a unique term; search for the first term returns only the first document; search for the second term returns only the second document
- [ ] `BM25RepositoryTest`: index a document, then call `deleteBySource` and re-index with updated content; searching for the old term returns no results; searching for the new term returns the document
- [ ] `BM25RepositoryTest`: `isAlreadyIndexed(source, mtime)` returns `true` for a (source, mtime) pair present in `meta.json` and `false` for an unknown source or a changed mtime
- [ ] `BM25RepositoryTest`: `getMetadata()` after indexing three chunks returns `documentCount == 3` and `indexSizeBytes > 0`
- [ ] `SearchCommandTest`: constructed with a `BM25SearchPipeline` stub that returns one `ChunkMatch`, passing `--mode bm25` causes `call()` to return exit code `0` and output contains the stub chunk's content
- [ ] JSON output of `ez-rag search --mode bm25 --output json` contains a top-level `"mode": "bm25"` field
- [ ] `./gradlew test` passes

### Quality gates

- [ ] No Kotlin compiler warnings
- [ ] `BM25Repository` creates all Lucene files exclusively inside `<storeDir>/lucene/`; no Lucene files are written to any other path

---

## Task [04-rrf-fusion-and-hybrid-default]

Implement Reciprocal Rank Fusion (RRF) that combines BM25 and embedding result lists, and make
`hybrid` the effective default search mode for `ez-rag search`, `ez-rag query`, and the shell's
`/search` command. The config default `searchMode == "hybrid"` (established in task 02) now routes
to a real pipeline.

### Implementation steps

- [ ] Implement `RrfFusion.fuse(bm25Results: List<ChunkMatch>, embeddingResults: List<ChunkMatch>, k: Int = 60, topK: Int): List<ChunkMatch>`: assign RRF score `1/(k + rank_bm25) + 1/(k + rank_embedding)` using worst rank for chunks present in only one list; deduplicate by `(filePath, chunkIndex)`; sort descending; return top `topK`; carry the RRF score in `ChunkMatch.score`
- [ ] Implement `HybridSearchPipeline(repository: VectorStoreRepository, embeddingModel: EmbeddingModel, bm25Repository: BM25Repository)`: fetch `topK * 2` candidates from each source, fuse via `RrfFusion`, return top `topK`
- [ ] Update `SearchCommand` and `QueryCommand` to route to `HybridSearchPipeline` when the resolved mode is `"hybrid"`
- [ ] Update `ShellCommand`: construct both `EmbeddingSearchPipeline` and `HybridSearchPipeline`; make the existing `/search` handler use `HybridSearchPipeline`

### Acceptance criteria

- [ ] `RrfFusionTest`: a chunk ranked 1st in both lists receives a strictly higher RRF score than a chunk ranked 1st in only one list (with the other list's worst rank applied)
- [ ] `RrfFusionTest`: a chunk appearing in both input lists appears exactly once in the output
- [ ] `RrfFusionTest`: when the BM25 list is empty, the output contains only chunks from the embedding list, each scored with a worst-rank BM25 penalty
- [ ] `RrfFusionTest`: when the embedding list is empty, the result is symmetric
- [ ] `RrfFusionTest`: output list length never exceeds `topK` regardless of input sizes
- [ ] `HybridSearchPipelineTest`: with a fake `BM25Repository` and fake `VectorStoreRepository` (following the pattern in `EmbeddingSearchPipelineTest`), the chunk ranked 1st by both fakes appears as the top result
- [ ] `SearchCommandTest`: invoking `call()` with no `--mode` flag (config default `"hybrid"`) routes to the hybrid pipeline, not the embedding-only pipeline
- [ ] `./gradlew test` passes

### Quality gates

- [ ] No Kotlin compiler warnings
- [ ] `RrfFusion` has no instance or companion-object mutable state (pure function or stateless object)

---

## Task [05-shell-and-mcp-search-mode-commands]

Extend the interactive shell with `/search-bm25` and `/search-embedding` slash commands, and add
`search_bm25` and `search_embedding` MCP tools so that both shell users and AI agents can explicitly
select a search mode without leaving their current interface.

### Implementation steps

- [ ] `ShellCommand`: add `/search-bm25 <query>` handler that uses `BM25SearchPipeline`; add `/search-embedding <query>` handler that uses `EmbeddingSearchPipeline`; update `/help` output to list both new commands with one-line descriptions
- [ ] Implement `McpBm25SearchTool` mirroring the structure of `McpSearchTool` but backed by `BM25SearchPipeline`; tool name must be `search_bm25`
- [ ] Implement `McpEmbeddingSearchTool` mirroring `McpSearchTool` but backed by `EmbeddingSearchPipeline`; tool name must be `search_embedding`
- [ ] Update `McpSearchTool` to use `HybridSearchPipeline` (it already defaults to hybrid via config; ensure it constructs the correct pipeline)
- [ ] Wire `McpBm25SearchTool` and `McpEmbeddingSearchTool` into `McpServerCommand`

### Acceptance criteria

- [ ] `ShellCommandTest`: input `/search-bm25 <term>` with a `BM25SearchPipeline` stub that returns one chunk outputs that chunk's content and exits with code `0` on the next `/exit`
- [ ] `ShellCommandTest`: input `/search-embedding <term>` with an `EmbeddingSearchPipeline` stub returns that stub's chunk content
- [ ] `ShellCommandTest`: `/help` output contains the strings `search-bm25` and `search-embedding`
- [ ] `McpBm25SearchToolTest`: the tool's declared name equals `"search_bm25"` and an invocation with a test query returns the BM25 stub's results
- [ ] `McpEmbeddingSearchToolTest`: the tool's declared name equals `"search_embedding"` and an invocation returns the embedding stub's results
- [ ] `McpServerCommandTest`: the list of registered tool names includes both `"search_bm25"` and `"search_embedding"`
- [ ] `./gradlew test` passes

### Quality gates

- [ ] No Kotlin compiler warnings
- [ ] `McpBm25SearchTool` and `McpEmbeddingSearchTool` hold no shared mutable state with each other or with `McpSearchTool`

---

## Task [06-status-bm25-metadata]

Extend `ez-rag status` to display the BM25 index document count and index size (in both text and JSON
output), giving users a single command to verify that both the vector store and the Lucene index are
populated after ingest.

### Implementation steps

- [ ] Update `StatusCommand` to accept (or construct) a `BM25Repository` for the current store directory and call `getMetadata()`
- [ ] Add a BM25 section to the text output: `BM25 documents: N  index size: M bytes` (or equivalent readable format)
- [ ] Add a `bm25` object to the JSON output: `{"documentCount": N, "indexSizeBytes": M}`
- [ ] Handle the case where the Lucene index directory does not yet exist: `getMetadata()` must return `documentCount == 0` and `indexSizeBytes == 0` without throwing, and the status command must still exit with code `0`

### Acceptance criteria

- [ ] `StatusCommandTest`: after ingesting one document (stubbed with one chunk), text output contains the line `BM25 documents: 1`
- [ ] `StatusCommandTest`: same scenario, JSON output contains `"bm25": {"documentCount": 1, "indexSizeBytes": <positive integer>}`
- [ ] `StatusCommandTest`: when no Lucene index directory exists, `call()` returns exit code `0` and text output contains `BM25 documents: 0`
- [ ] `./gradlew test` passes

### Quality gates

- [ ] No Kotlin compiler warnings
- [ ] `StatusCommand` does not catch-and-swallow exceptions from `BM25Repository` that indicate programmer error (e.g. wrong path type); only absent-index `IOException` is handled gracefully
