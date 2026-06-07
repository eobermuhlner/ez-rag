# PRD: LuceneRepository — Unified Lucene-backed Document Store

## Problem Statement

As the number of ingested documents grows, ez-rag degrades in two ways. First, `SimpleVectorStore` loads the entire vector store — all document embeddings — into RAM on startup. This makes startup time and memory usage proportional to the collection size, which becomes unacceptable for large knowledge bases. Second, ez-rag maintains two separate indexes for the same documents: a JSON file for semantic (embedding) search and a Lucene directory for BM25 keyword search. These must be kept in sync manually, adding complexity across `IngestService`, `ReIngestService`, and every command that touches the store.

## Solution

Replace `SimpleVectorStore` and `BM25Repository` with a single `LuceneRepository` that stores both the float embedding vector (for HNSW approximate nearest-neighbour search) and the text content (for BM25) in the same Lucene document. Lucene queries the index on disk without loading all vectors into memory, and there is only one index to manage. All callers are updated to use `LuceneRepository`; `VectorStoreRepository` and `BM25Repository` are deleted.

## User Stories

1. As a developer, I want the store to query the Lucene index on disk so that startup time and memory usage do not grow proportionally with the number of ingested documents.
2. As a developer, I want a single index for both semantic and BM25 search so that there is no risk of the two indexes falling out of sync.
3. As a CLI user, I want the `ingest` command to populate the new unified index so that I can perform both embedding and BM25 searches after a single ingest run.
4. As a CLI user, I want the `reingest` command to delete and re-add document chunks from the unified index so that stale documents are refreshed correctly.
5. As a CLI user, I want the `delete` command to remove all chunks for a given file from the unified index so that deleted documents are fully removed.
6. As a CLI user, I want the `list` command to list all ingested documents from the unified index so that I can see the current state of the store.
7. As a CLI user, I want the `show` command to display chunk content for a given file from the unified index so that I can inspect what was ingested.
8. As a CLI user, I want the `status` command to report unified store statistics (chunk count, document count, store size, stale document count, last ingest time) so that I have a single, accurate view of the store.
9. As a CLI user, I want the `search` command with `--mode embedding` to run HNSW semantic search on the unified index so that I get relevant results without loading all vectors into RAM.
10. As a CLI user, I want the `search` command with `--mode bm25` to run BM25 keyword search on the unified index so that I get keyword-matched results.
11. As a CLI user, I want the `search` command with `--mode hybrid` to fuse HNSW and BM25 results from the unified index via Reciprocal Rank Fusion so that I get the best of both retrieval strategies.
12. As a CLI user, I want the `query` command to use the unified index for retrieval so that RAG responses draw on the correct document set.
13. As a CLI user running an MCP server, I want the `delete`, `show`, and `status` MCP tools to operate on the unified index so that all server-side operations are consistent.
14. As a developer switching embedding models, I want the store to detect a dimension mismatch at startup and fail with a clear error message so that I do not silently produce wrong search results.
15. As a developer, I want the unified index to use COSINE similarity for HNSW so that it works correctly across all supported embedding providers (ONNX, Ollama, OpenAI) regardless of vector magnitude.
16. As a developer, I want the BM25 analyzer (standard vs english) to remain configurable in the unified store so that multilingual and English-optimised search quality is preserved.
17. As a developer, I want the source→mtime ingestion cache to be built from stored fields in the Lucene index so that there is no separate `meta.json` sidecar file to maintain.
18. As a developer, I want `isAlreadyIngested` to use the in-memory source→mtime cache so that incremental ingest skips already-current documents with O(1) lookups.
19. As a developer writing tests, I want `LuceneRepository` to be testable with a stub 4-dimensional `EmbeddingModel` and a temporary directory so that tests are fast and hermetic.
20. As a developer, I want the index to validate the embedding dimension on open and store it in a `config.properties` file inside the index directory so that re-ingest is required when switching models.

## Implementation Decisions

### New module: `LuceneRepository`

The central new module. It is a deep module: a rich implementation (HNSW indexing, BM25 indexing, on-disk persistence, source→mtime caching, metadata queries) behind a small, stable API.

**Construction**: via a static `companion object` factory method `open(embeddingModel, storeDir, analyzerName)`. The primary constructor is cheap (assigns fields); `open()` does the I/O: creates/opens the `IndexWriter`, validates or writes the embedding dimension in `config.properties`, and builds the source→mtime cache by scanning stored fields. This separates the I/O cost from object creation and keeps construction testable.

**Existence check**: a separate static `companion object` method `storeExists(storeDir)` uses `DirectoryReader.indexExists()` to check whether the index contains any committed data, without opening a writer. Callers use this before calling `open()` to bail out early when no store exists yet.

**Storage layout**: the unified Lucene index lives at `storeDir/lucene/`. Each Lucene document holds:
- `KnnFloatVectorField("vector", ...)` — HNSW index, COSINE similarity, M=16
- `TextField("content", ...)` — BM25 index, analysed by the configured analyzer
- `StoredField`s for all metadata: `id`, `source`, `mtime`, `chunk_index`, `heading_title`, `heading_level`, `heading_path` (JSON-serialised list via Jackson)

**HNSW parameters**: M=16 (Lucene default) at index time; `numCandidates = topK * 4` at query time.

**`IndexWriter` lifecycle**: a persistent `IndexWriter` is held open for the lifetime of the object. `commit()` is called after each `add()` batch and each `delete()` call. `LuceneRepository` implements `Closeable`; `close()` commits and closes the writer.

**Public API**:
- `companion.open(embeddingModel, storeDir, analyzerName): LuceneRepository`
- `companion.storeExists(storeDir: Path): Boolean`
- `add(documents: List<Document>)` — write chunks, commit, update cache
- `delete(source: String): Int` — delete by source term, commit, update cache, return removed count
- `semanticSearch(query: String, topK: Int): List<Document>` — HNSW KNN, returns Spring AI `Document` with score set
- `bm25Search(query: String, topK: Int): List<Document>` — BM25, returns Spring AI `Document` with score set
- `isAlreadyIngested(source: String, mtime: Long): Boolean` — O(1) cache check
- `getMetadata(filesystemProbe): StoreMetadata` — doc/chunk counts, per-document stale flags
- `getChunksForFile(source: String): List<DocumentChunkInfo>` — stored chunk texts, sorted by chunk index
- `close()` — commit and close writer

### Modified module: `StoreMetadata`

`storeFilePath: String` is renamed to `storeDirPath: String` to reflect that the store is now a directory, not a single file. `BM25IndexMetadata` data class is deleted — its information (chunk count, index size) is covered by `StoreMetadata` fields. `storeSizeBytes` now reflects the size of the `lucene/` directory.

### Modified module: `EmbeddingSearchPipeline`

Dependency changes from `VectorStoreRepository + EmbeddingModel` to `LuceneRepository`. Calls `repository.semanticSearch(query, topK)` in place of `repository.getStore().similaritySearch(SearchRequest)`. The reflection-free return type (`List<Document>` with score) is iterated the same way.

### Modified module: `BM25SearchPipeline`

Dependency changes from `BM25Repository` to `LuceneRepository`. Calls `repository.bm25Search(query, topK)`.

### Modified module: `HybridSearchPipeline`

Dependency changes from `VectorStoreRepository + EmbeddingModel + BM25Repository` to a single `LuceneRepository`. Calls `repository.semanticSearch(...)` and `repository.bm25Search(...)` internally. `RrfFusion` is unchanged.

### Modified module: `IngestService`

Replaces dual `VectorStoreRepository.add()`/`BM25Repository.index()` calls with a single `LuceneRepository.add()`. Replaces dual `isAlreadyIngested`/`isAlreadyIndexed` checks with a single `repository.isAlreadyIngested()`. Removes explicit `save()` calls (the writer commits automatically).

### Modified module: `ReIngestService`

Replaces dual `repository.delete()` + `bm25Repository.deleteBySource()` with a single `repository.delete()`. Removes explicit `save()` calls.

### Modified commands and MCP tools

All commands that currently instantiate `VectorStoreRepository` (and optionally `BM25Repository`) are updated to call `LuceneRepository.open(...)`. Commands that check `repository.storeExists()` use `LuceneRepository.storeExists(storeDir)` instead. `StatusCommand` removes the separate BM25 metadata section; all stats come from `StoreMetadata`.

### Deleted modules

`VectorStoreRepository`, `BM25Repository` — deleted entirely, including all reflection-based internals. `meta.json` sidecar is no longer written or read.

### Dimension validation

On `open()`, if the `lucene/` directory is new (no `config.properties`), the current model's dimension is written to `config.properties` as `embedding.dimension=N`. If the file exists, the stored dimension is compared to the current model's dimension. A mismatch throws `IllegalStateException` with a message directing the user to re-ingest.

## Testing Decisions

**What makes a good test**: tests assert observable external behaviour — what comes out of the public API given a known sequence of calls. Tests do not assert implementation details such as Lucene field names, internal data structures, or commit timing. Each test scenario drives the public API from setup to assertion.

**Test infrastructure**: no Spring context. A 4-dimensional stub `EmbeddingModel` (consistent with existing `VectorStoreRepositoryTest`) and `@TempDir` for a real on-disk Lucene index. Real disk is required because persistence and the open/close lifecycle are first-class observable behaviours of `LuceneRepository`.

**Modules to test**: `LuceneRepository` is the primary test target. It is the deep module that replaces two existing tested modules (`VectorStoreRepositoryTest`, `BM25RepositoryTest`); those test files are deleted.

**Prior art**: `VectorStoreRepositoryTest` and `BM25RepositoryTest` — same `@TempDir` + stub model pattern, same test granularity (one behaviour per test method).

**Test scenarios to cover**:
- `semanticSearch` returns the most similar document after `add`
- `bm25Search` returns the best keyword match after `add`
- `isAlreadyIngested` returns false before add and true after
- `delete` removes chunks and returns the correct count; `isAlreadyIngested` returns false afterwards
- `getChunksForFile` returns chunks sorted by `chunk_index`
- `getMetadata` reports correct `chunkCount`, `documentCount`, `storeSizeBytes`, `lastIngestTime`, and `staleDocumentCount`
- `storeExists` returns false before any `add`, true after
- Persisted index is readable after close and re-open (open/close lifecycle)
- Dimension mismatch on re-open throws `IllegalStateException`
- `heading_path` round-trips correctly through JSON serialisation

## Out of Scope

- Migration of existing `vector-store.json` data to the new Lucene index — users must re-ingest after switching to this version.
- Exposing HNSW parameters (M, numCandidates multiplier) as user-configurable settings.
- Thread-safety or concurrent write access — the store remains single-process, single-writer as today.
- Deleting `vector-store.json` from disk — the application is not yet deployed; cleanup is not needed.
- Adding new metadata fields beyond the ones already tracked (`id`, `source`, `mtime`, `chunk_index`, `heading_title`, `heading_level`, `heading_path`).

## Further Notes

- Lucene version in use: 9.12.1 (already in `build.gradle.kts` for BM25).
- `heading_path` is a `List<String>` in the domain model but Lucene has no native list field; it is JSON-serialised to a `StoredField` string using Jackson's `ObjectMapper`, which is already on the classpath via Spring Boot.
- `RrfFusion` is stateless and unchanged by this feature — it fuses two `List<ChunkMatch>` by rank, not by raw score, so no score normalisation is needed between HNSW and BM25 results.
- The `EvalEngine` (used for offline evaluation benchmarks) directly instantiates `VectorStoreRepository` and is updated alongside the other callers.
