# Tasks: lucene-repository

## Task [01-lucene-repository-core]

Create `LuceneRepository` — the unified on-disk Lucene index that holds HNSW embedding vectors and BM25 text in the same Lucene document. No existing callers are changed in this task. The new module is fully covered by a unit test suite before any wiring takes place.

The factory method `LuceneRepository.open(embeddingModel, storeDir, analyzerName)` creates or opens the index at `storeDir/lucene/`, validates or writes the embedding dimension in `config.properties`, and builds the source→mtime in-memory cache from stored fields. `LuceneRepository.storeExists(storeDir)` checks whether the index has committed data without opening a writer. `LuceneRepository` implements `Closeable`; `close()` commits and closes the `IndexWriter`.

Each Lucene document stores: `KnnFloatVectorField("vector", …)` with COSINE similarity and M=16 for HNSW; `TextField("content", …)` analysed by the configured analyser for BM25; `StoredField`s for `id`, `source`, `mtime`, `chunk_index`, `heading_title`, `heading_level`, and `heading_path` (JSON-serialised `List<String>` via Jackson).

`semanticSearch` uses an HNSW KNN query with `numCandidates = topK * 4`. `bm25Search` uses `QueryParser` on the content field. Both return Spring AI `Document` objects with the score set in metadata.

The source→mtime cache is a `MutableMap<String, Long>` built on open, updated on `add()` and `delete()`, and consulted by `isAlreadyIngested()` for O(1) lookups.

### Implementation steps

- [x] Write failing tests covering all 10 PRD test scenarios before writing any implementation code (TDD)
- [x] Create `LuceneRepository` class in `ingestion/` with `companion object` factory `open()` and `storeExists()`
- [x] Implement `add(documents)`: write HNSW vector field + BM25 text field + stored metadata fields for each document; call `IndexWriter.commit()` after the batch; update the source→mtime cache
- [x] Implement `semanticSearch(query, topK)`: HNSW KNN query with COSINE, numCandidates=topK*4; return `List<Document>` with score in metadata
- [x] Implement `bm25Search(query, topK)`: BM25 `QueryParser` on content field; return `List<Document>` with score in metadata
- [x] Implement `delete(source)`: delete by source term; commit; update cache; return removed count
- [x] Implement `isAlreadyIngested(source, mtime)`: O(1) cache lookup
- [x] Implement `getChunksForFile(source)`: scan stored fields, sort by chunk_index, deserialise heading_path from JSON
- [x] Implement `getMetadata(filesystemProbe)`: aggregate chunkCount, documentCount, storeSizeBytes (size of lucene/ dir), lastIngestTime, staleDocumentCount
- [x] Implement dimension validation: write `embedding.dimension=N` to `config.properties` on first open; compare on subsequent opens and throw `IllegalStateException` on mismatch
- [x] Implement `close()` as `Closeable`: commit and close `IndexWriter`; close `Analyzer`
- [x] Make all tests pass

### Acceptance criteria

- [x] `semanticSearch` returns the most similar document after `add` (stub 4-dim embedding model + TempDir)
- [x] `bm25Search` returns the best keyword-matching document after `add`
- [x] `isAlreadyIngested` returns false before add, true after add, false after delete
- [x] `delete` returns the exact number of removed Lucene documents; a subsequent `getChunksForFile` for that source returns empty
- [x] `getChunksForFile` returns chunks sorted ascending by `chunk_index`
- [x] `getMetadata` reports correct `chunkCount`, `documentCount`, `storeSizeBytes > 0`, `lastIngestTime`, and `staleDocumentCount` (using a filesystem-probe stub)
- [x] `storeExists` returns false before the first `add`, true after
- [x] Index is readable after `close()` and re-`open()` — documents added in a first open are retrievable in a second open
- [x] `open()` throws `IllegalStateException` when the current embedding model's dimension does not match the dimension stored in `config.properties`
- [x] `heading_path` round-trips correctly: a `List<String>` stored via `add()`, then retrieved via `getChunksForFile()` after `close()` and re-`open()`, equals the original list

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] No uses of reflection (`getDeclaredField`, `isAccessible`, `@Suppress("UNCHECKED_CAST")`) in `LuceneRepository`
- [x] No compiler warnings in the new file (`./gradlew compileKotlin` clean)

---

## Task [02-ingest-delete-reingest-switched]

Switch the write/mutation path to `LuceneRepository`. After this task, `IngestService`, `ReIngestService`, `IngestCommand`, `ReIngestCommand`, `DeleteCommand`, and their MCP tool equivalents all talk exclusively to the unified Lucene index. `VectorStoreRepository` and `BM25Repository` still exist as source files but are no longer referenced by any service or command in this path.

`IngestService` replaces the dual `VectorStoreRepository.add()` + `BM25Repository.index()` call with a single `LuceneRepository.add()`; it also replaces the combined `isAlreadyIngested && isAlreadyIndexed` guard (which requires both to be true to skip) with the single `repository.isAlreadyIngested()` check — this is a deliberate semantic change: a file is skipped only when the unified index is current. Explicit `save()` and `bm25Repository.close()` calls are removed; the writer commits automatically inside `add()`.

`ReIngestService` replaces the dual `repository.delete() + bm25Repository.deleteBySource()` sequence with a single `repository.delete()`.

`DeleteCommand` replaces `VectorStoreRepository.load()` + `delete()` + `save()` with `LuceneRepository.open()` + `delete()`; no explicit save is needed. `IngestCommand` and `ReIngestCommand` replace `VectorStoreRepository.storeExists()` / `load()` with `LuceneRepository.storeExists()` / `open()`. `McpIngestTool` and `McpReIngestTool` delegate to the updated services and need no direct changes unless they construct repositories inline.

### Implementation steps

- [x] Update `IngestService`: replace `VectorStoreRepository` + `BM25Repository` construction with `LuceneRepository.open()`; replace dual skip check with `repository.isAlreadyIngested()`; replace dual `add`/`index` calls with `repository.add()`; remove `repository.save()` and `bm25Repository.close()`
- [x] Update `ReIngestService`: replace dual `repository.delete()` + `bm25Repository.deleteBySource()` with `repository.delete()`; remove `repository.save()` and `bm25Repository.close()`
- [x] Update `IngestCommand`: use `LuceneRepository.storeExists(storeDir)` for the "store already exists" check; use `LuceneRepository.open()` where `VectorStoreRepository` was constructed
- [x] Update `ReIngestCommand`: same pattern as IngestCommand
- [x] Update `DeleteCommand`: replace `VectorStoreRepository.load()` + `delete()` + `save()` with `LuceneRepository.open()` + `delete()`; wrap in `use {}` to ensure `close()` is called
- [x] Update test helpers in `IngestCommandTest`, `DeleteCommandTest`, `ReIngestCommandTest` that construct `VectorStoreRepository` or `BM25Repository` directly; replace with `LuceneRepository` equivalents
- [x] Run all tests and fix failures

### Acceptance criteria

- [x] `IngestService.ingest()` writes chunks to the Lucene index (both HNSW and BM25 fields); a subsequent `LuceneRepository.semanticSearch()` and `bm25Search()` on the same index returns those chunks
- [x] `IngestService.ingest()` skips files where `isAlreadyIngested()` returns true (same source + mtime already in the unified cache)
- [x] `ReIngestService.reIngest()` deletes stale chunks from the Lucene index and re-adds the re-read chunks via `IngestService`
- [x] `DeleteCommand` removes all chunks for the specified file from the Lucene index and outputs the removed count; no orphaned BM25 documents remain
- [x] `IngestCommandTest`, `DeleteCommandTest`, `ReIngestCommandTest` all pass

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] No import of `VectorStoreRepository` or `BM25Repository` in `IngestService.kt` or `ReIngestService.kt`
- [x] No `repository.save()` or `bm25Repository.close()` calls in `IngestService.kt` or `ReIngestService.kt`

---

## Task [03-observability-path-switched]

Switch the read/observability path to `LuceneRepository`, rename `StoreMetadata.storeFilePath` to `storeDirPath`, and delete `BM25IndexMetadata`. After this task, `ListCommand`, `ShowCommand`, `StatusCommand`, `McpDeleteTool`, `McpShowTool`, and `McpStatusTool` all read from the unified Lucene index. The "no store found" error messages are updated to reference the Lucene directory rather than `vector-store.json`.

`StatusCommand` removes the separate BM25 metadata block from both text and JSON output; all stats (`chunkCount`, `documentCount`, `storeSizeBytes`, `staleDocumentCount`, `lastIngestTime`) come from `LuceneRepository.getMetadata()`. In JSON output the key `"storeFilePath"` becomes `"storeDirPath"`.

`VectorStoreRepository` and `BM25Repository` still exist as source files but are no longer referenced by any command or tool in this path.

### Implementation steps

- [x] Rename `StoreMetadata.storeFilePath` to `storeDirPath` in the data class definition; fix all compile errors caused by the rename
- [x] Delete the `BM25IndexMetadata` data class
- [x] Update `LuceneRepository.getMetadata()` to populate `storeDirPath` with the `storeDir/lucene/` path string
- [x] Update `ListCommand`: replace `VectorStoreRepository` with `LuceneRepository.open()`; update the "no store found" message to reference the Lucene directory; wrap in `use {}`
- [x] Update `ShowCommand`: replace `VectorStoreRepository.load()` + `getChunksForFile()` with `LuceneRepository.open()` + `getChunksForFile()`; wrap in `use {}`
- [x] Update `StatusCommand`: replace `VectorStoreRepository` + `BM25Repository` with `LuceneRepository.open()`; remove the `bm25` block from JSON output; replace `metadata.storeFilePath` with `metadata.storeDirPath`; update the "no store found" message; wrap in `use {}`
- [x] Update `McpDeleteTool`: replace `VectorStoreRepository` with `LuceneRepository`
- [x] Update `McpShowTool`: replace `VectorStoreRepository` with `LuceneRepository`
- [x] Update `McpStatusTool`: replace `VectorStoreRepository` + `BM25Repository` with `LuceneRepository`; update any field reference from `storeFilePath` to `storeDirPath`
- [x] Update test helpers in `ListCommandTest`, `ShowCommandTest`, `StatusCommandTest`, `McpStatusToolTest`, `McpShowToolTest`, `McpDeleteToolTest` that reference `storeFilePath` or construct old repos
- [x] Run all tests and fix failures

### Acceptance criteria

- [x] `ez-rag list` (via `ListCommand`) lists all documents present in the Lucene index and shows correct chunk counts and stale flags
- [x] `ez-rag show <file>` (via `ShowCommand`) displays chunk metadata from the Lucene index; heading fields round-trip correctly
- [x] `ez-rag status` text output reports chunk count, document count, store size, stale count, and last ingest time from the unified index; contains no "BM25" section
- [x] `ez-rag status --output-format json` output contains `"storeDirPath"` (not `"storeFilePath"`) and no `"bm25"` key
- [x] `ListCommandTest`, `ShowCommandTest`, `StatusCommandTest`, `McpStatusToolTest`, `McpShowToolTest`, `McpDeleteToolTest` all pass

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `StoreMetadata.storeFilePath` field does not exist anywhere in `src/` (verified by grep)
- [x] `BM25IndexMetadata` class does not exist anywhere in `src/` (verified by grep)
- [x] No import of `VectorStoreRepository` or `BM25Repository` in `ListCommand.kt`, `ShowCommand.kt`, or `StatusCommand.kt`

---

## Task [04-search-path-and-cleanup]

Switch the search path to `LuceneRepository`, update `EvalEngine` and `McpServerCommand`, and delete all old-repo source and test files. After this task, the codebase contains no references to `VectorStoreRepository` or `BM25Repository`.

`EmbeddingSearchPipeline` replaces `VectorStoreRepository + EmbeddingModel` with a single `LuceneRepository`; calls `repository.semanticSearch(query, topK)`. `BM25SearchPipeline` replaces `BM25Repository` with `LuceneRepository`; calls `repository.bm25Search(query, topK)`. `HybridSearchPipeline` replaces `VectorStoreRepository + EmbeddingModel + BM25Repository` with a single `LuceneRepository`.

`SearchCommand` has four inline code paths that each construct a `VectorStoreRepository` or `BM25Repository`; all are replaced with `LuceneRepository.open(…)`. `QueryCommand` has at least one inline `VectorStoreRepository` construction; same replacement applies.

`McpServerCommand.mcpToolCallbackProvider()` directly instantiates `VectorStoreRepository`, `BM25Repository`, `EmbeddingSearchPipeline`, `BM25SearchPipeline`, and `HybridSearchPipeline`. It is the single largest concentration of old-repo usage; all references are replaced with `LuceneRepository`-backed equivalents.

`EvalEngine` constructs `VectorStoreRepository` on line 54 and passes it to `EmbeddingSearchPipeline` and `defaultSearch()`; both are updated to `LuceneRepository`.

`MCP tools` updated: `McpBm25SearchTool`, `McpEmbeddingSearchTool`, `McpSearchTool`, `McpQueryTool`.

After all callers are updated, `VectorStoreRepository.kt` and `BM25Repository.kt` are deleted, along with their test files `VectorStoreRepositoryTest.kt` and `BM25RepositoryTest.kt`. Any remaining test helpers in command tests that reference the old repos are migrated before deletion.

### Implementation steps

- [x] Update `EmbeddingSearchPipeline`: replace `VectorStoreRepository + EmbeddingModel` with `LuceneRepository`; replace `similaritySearch(SearchRequest)` with `repository.semanticSearch(query, topK)`
- [x] Update `BM25SearchPipeline`: replace `BM25Repository` with `LuceneRepository`; replace `bm25Repository.search()` with `repository.bm25Search()`
- [x] Update `HybridSearchPipeline`: replace three-parameter constructor (`VectorStoreRepository`, `EmbeddingModel`, `BM25Repository`) with single `LuceneRepository`; update search calls accordingly
- [x] Update `SearchCommand`: replace all four inline code paths that construct `VectorStoreRepository`/`BM25Repository` with `LuceneRepository.open(…)` wrapped in `use {}`
- [x] Update `QueryCommand`: replace inline `VectorStoreRepository` construction with `LuceneRepository.open(…)`
- [x] Update `McpServerCommand.mcpToolCallbackProvider()`: replace all `VectorStoreRepository`, `BM25Repository`, old pipeline constructions with `LuceneRepository`-backed equivalents
- [x] Update `McpBm25SearchTool`, `McpEmbeddingSearchTool`, `McpSearchTool`, `McpQueryTool`: replace old-repo dependencies with `LuceneRepository`
- [x] Update `EvalEngine`: replace `VectorStoreRepository` with `LuceneRepository` in `evaluate()` and `defaultSearch()`; update the `EmbeddingSearchPipeline` construction to use `LuceneRepository`
- [x] Migrate test helpers in `SearchCommandTest`, `BM25SearchPipelineTest`, `McpBm25SearchToolTest`, `McpEmbeddingSearchToolTest`, `McpSearchToolTest`, `McpQueryToolTest` that reference old repos
- [x] Delete `VectorStoreRepository.kt` and `BM25Repository.kt`
- [x] Delete `VectorStoreRepositoryTest.kt` and `BM25RepositoryTest.kt`
- [x] Run all tests and fix failures

### Acceptance criteria

- [x] `ez-rag search --mode embedding <query>` returns HNSW semantic results from the Lucene index (no `SimpleVectorStore` involved)
- [x] `ez-rag search --mode bm25 <query>` returns BM25 keyword results from the Lucene index (no `BM25Repository` involved)
- [x] `ez-rag search --mode hybrid <query>` fuses HNSW and BM25 results from a single `LuceneRepository` via RRF
- [x] `ez-rag query <question>` retrieves context from the unified Lucene index and generates a response
- [x] `SearchCommandTest`, `BM25SearchPipelineTest`, and all MCP search tool tests pass
- [x] `VectorStoreRepository.kt` and `BM25Repository.kt` do not exist in `src/`

### Quality gates

- [x] `./gradlew build` passes with no failures and no compiler errors
- [x] `grep -r "VectorStoreRepository\|BM25Repository" src/` returns no matches
- [x] `grep -r "vector-store.json\|SimpleVectorStore" src/` returns no matches (unused import removed from `IngestIntegrationTest.kt`)
- [x] `grep -r "BM25IndexMetadata\|storeFilePath" src/` returns no matches
