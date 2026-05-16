# PRD: BM25 Search and Hybrid Search

## Problem Statement

Embedding-based (vector) search excels at semantic similarity but struggles with exact keyword matches, rare terms, and proper nouns. A user searching for a specific function name, error code, or technical term may get poor results because the embedding model generalises the query away from the exact token. Users need a search mode that complements semantic search with term-frequency matching so that both conceptual and keyword queries return high-quality results.

## Solution

Add BM25 (term-frequency/inverse-document-frequency) search powered by Apache Lucene alongside the existing embedding search. Introduce a hybrid search mode (the new default) that combines both ranking signals using Reciprocal Rank Fusion (RRF), delivering better results than either approach alone. Users can opt into pure BM25 or pure embedding search via a `--mode` flag.

## User Stories

1. As a developer using `ez-rag search`, I want hybrid search to be the default so that I get better results without changing my workflow.
2. As a developer using `ez-rag search`, I want to pass `--mode embedding` to get the original embedding-only behaviour when I need it.
3. As a developer using `ez-rag search`, I want to pass `--mode bm25` to do keyword-only search when I know the exact term I am looking for.
4. As a developer using `ez-rag query`, I want the same `--mode` flag available so that the retrieval step in RAG can also be switched between hybrid, embedding, and BM25.
5. As a developer using the interactive shell, I want `/search` to use hybrid search by default so that my interactive queries benefit from the improved ranking.
6. As a developer using the interactive shell, I want `/search-bm25 <query>` to run a keyword-only search without leaving the shell.
7. As a developer using the interactive shell, I want `/search-embedding <query>` to run embedding-only search without leaving the shell.
8. As an MCP client (e.g. an AI agent), I want a `search` tool that uses hybrid search by default so that tool-driven queries are high quality.
9. As an MCP client, I want a `search_bm25` tool to request keyword-only retrieval explicitly.
10. As an MCP client, I want a `search_embedding` tool to request embedding-only retrieval explicitly.
11. As a developer ingesting documents, I want the BM25 index to be built automatically during ingest so that no extra step is required.
12. As a developer re-ingesting a changed file, I want the BM25 index to be updated atomically with the vector store so that both indexes stay in sync.
13. As a developer, I want the Lucene index to live inside the same store directory as the vector store so that I can copy or delete a store as a single unit.
14. As a developer using `--store`, I want the flag to point to the store directory rather than the JSON file so that it controls all indexes in one place.
15. As a developer, I want to set a default search mode in `~/.ez-rag/config.yml` so that my preferred mode applies across all commands without specifying the flag every time.
16. As a developer, I want to choose the Lucene text analyser (`standard` or `english`) via `--analyzer` or `~/.ez-rag/config.yml` so that I can enable stemming for better recall on English text.
17. As a developer, I want the analyser choice to be consistent between index time and query time without having to specify it twice.
18. As a developer running `ez-rag status`, I want to see the BM25 index document count and index size alongside the vector store metadata so that I can verify both indexes are populated.
19. As a developer, I want `--min-score` to still work for embedding and be silently ignored for BM25 and hybrid modes so that existing scripts are not broken.
20. As a developer reading JSON output from `ez-rag search`, I want a top-level `mode` field so that I can see which retrieval strategy produced the results.
21. As a developer, I want the existing config precedence (CLI flag > environment variable > `~/.ez-rag/config.yml` > default) to apply to the new `--mode` and `--analyzer` options.
22. As a developer, I want `search-mode` and `analyzer` to be readable from environment variables (`SEARCH_MODE`, `ANALYZER`) consistent with existing env-var support.

## Implementation Decisions

### Store directory layout

The `--store` flag and the `store-path` config key now point to a **directory** (default: `.ez-rag`). Inside that directory:
- `vector-store.json` — Spring AI SimpleVectorStore (unchanged format)
- `lucene/` — Apache Lucene index directory for BM25

`EzRagConfig.storePath` default changes from `.ez-rag/vector-store.json` to `.ez-rag`. All commands that previously constructed a `Path` to the JSON file now construct paths relative to the store directory.

### New module: `BM25Repository`

Encapsulates all Lucene operations. Constructed with a `Path` to the store directory and an analyser name string. Exposes:

- `index(documents: List<Document>)` — writes chunks to the Lucene index; each document becomes a Lucene document with a `content` field (indexed + stored) and a `source` field (indexed + stored, not tokenised)
- `deleteBySource(sourcePath: String)` — deletes all Lucene documents with a matching `source` term before re-indexing a changed file
- `search(query: String, topK: Int): List<BM25ChunkMatch>` — runs a BM25 query and returns ranked results with Lucene scores
- `isAlreadyIndexed(sourcePath: String, mtime: Long): Boolean` — mirrors `VectorStoreRepository.isAlreadyIngested`; reads a small metadata sidecar (`lucene/meta.json`) to track (source, mtime) pairs
- `getMetadata(): BM25IndexMetadata` — returns document count and index size in bytes
- `close()` — closes the Lucene IndexWriter

The Lucene index is opened in `NIOFSDirectory` mode. Lucene 9.x is used directly (not via Spring AI).

### New module: `RrfFusion`

A stateless object (or top-level function) with a single entry point:

```
fuse(
  bm25Results: List<ChunkMatch>,
  embeddingResults: List<ChunkMatch>,
  k: Int = 60,
  topK: Int
): List<ChunkMatch>
```

Each input list is a ranked list of `ChunkMatch` objects. The function assigns each chunk an RRF score of `1/(k + rank_bm25) + 1/(k + rank_embedding)` (using the worst rank for chunks that appear in only one list), deduplicates by `(filePath, chunkIndex)`, sorts by RRF score descending, and returns the top `topK` results. The returned `ChunkMatch.score` carries the RRF score.

### New module: `HybridSearchPipeline`

Extends or wraps `EmbeddingSearchPipeline`. Constructed with a `VectorStoreRepository`, an `EmbeddingModel`, and a `BM25Repository`. Implements `search(query: SearchQuery): SearchResult`:

1. Fetch `query.topK * 2` candidates from embedding search (ignores `minScore`)
2. Fetch `query.topK * 2` candidates from BM25 search
3. Fuse via `RrfFusion.fuse(...)` and return top `query.topK` results

### Modified module: `VectorStoreRepository`

- Constructor takes a store **directory** `Path` rather than the JSON file path
- Derives `<storeDir>/vector-store.json` internally
- `load()` and `save()` continue to work with `vector-store.json` inside the directory

### Config additions (`EzRagConfig`, `CliFlags`, `ConfigService`, `ConfigFileReader`)

Two new fields added to `EzRagConfig`:

| Field | Type | Default | Config key | Env var |
|---|---|---|---|---|
| `searchMode` | `String` | `"hybrid"` | `search-mode` | `SEARCH_MODE` |
| `analyzer` | `String` | `"standard"` | `analyzer` | `ANALYZER` |

`ConfigService.resolve()` resolves these through the standard CLI > env > file > default chain. `ConfigFileReader` parses both camelCase and kebab-case variants.

### `SearchQuery` and `SearchResult` changes

`SearchQuery` gains a `mode: String` field (values: `"hybrid"`, `"bm25"`, `"embedding"`).
`SearchResult` gains a `mode: String` field mirroring the query mode.

### Command changes

**`SearchCommand` and `QueryCommand`**: Add `--mode` option (default resolved from config). Build the appropriate pipeline (`EmbeddingSearchPipeline`, `HybridSearchPipeline`, or a new `BM25SearchPipeline` thin wrapper) based on the resolved mode.

**`ShellCommand`**: Add `/search-bm25` and `/search-embedding` slash commands alongside the existing `/search` (which becomes hybrid by default). Update `/help` output.

**`StatusCommand`**: Call `BM25Repository.getMetadata()` and include doc count and index size in both text and JSON output.

### MCP tool changes

Add `McpBm25SearchTool` and `McpEmbeddingSearchTool` alongside the existing `McpSearchTool` (which becomes hybrid). Wire all three into `McpServerCommand`.

### Lucene analyser selection

`BM25Repository` accepts an `analyzerName: String` parameter. At construction time it instantiates either `StandardAnalyzer` (default) or `EnglishAnalyzer`. The same analyser instance is used for both indexing and querying, guaranteeing consistency. The choice is resolved once at startup via `ConfigService` and passed into `BM25Repository`.

### `--min-score` behaviour

`--min-score` is applied only when `mode == "embedding"`. For `bm25` and `hybrid` modes it is accepted but has no effect, and no warning is emitted.

### Build dependencies

Add to `build.gradle.kts` under the existing Spring AI BOM:
- `org.apache.lucene:lucene-core` (version 9.x)
- `org.apache.lucene:lucene-analysis-common` (version 9.x, needed for `EnglishAnalyzer`)

## Testing Decisions

**What makes a good test**: Tests verify observable behaviour through the public interface of a module, not internal implementation. Tests use in-memory or `@TempDir` stores and deterministic fake embedding models (following the pattern in `EmbeddingSearchPipelineTest`). Tests do not assert on Lucene internals or reflection.

**Modules to test:**

- **`BM25Repository`** — index documents, search by keyword, verify deduplication on re-index, verify `getMetadata()` counts, verify `isAlreadyIndexed()` tracks mtime. Use `@TempDir` for the Lucene directory.

- **`RrfFusion`** — fuse two ranked lists, verify RRF scores and ordering, verify deduplication of overlapping chunks, verify behaviour when one list is empty, verify top-k trimming.

- **`HybridSearchPipeline`** — use a fake `BM25Repository` and a fake `EmbeddingSearchPipeline` with controlled ranked outputs; verify that the fused output respects RRF ordering and top-k. Prior art: `EmbeddingSearchPipelineTest`.

- **`ConfigService`** — extend existing `ConfigServiceTest` with cases for `searchMode` and `analyzer` resolution across all four precedence levels.

- **`VectorStoreRepository`** — extend existing tests with store directory path handling (vector store JSON derived from directory); verify that existing chunk count and deduplication behaviour is unchanged.

## Out of Scope

- Migrating `SimpleVectorStore` to `LuceneVectorStore` for unified vector + BM25 storage (future TODO).
- Exposing the RRF constant `k` as a configurable option.
- Per-result annotation of which ranker(s) contributed to a hybrid result.
- Auto-rebuilding the BM25 index from an existing vector store (not needed; users re-ingest).
- Any analyser other than `standard` and `english`.
- BM25 support in the `status` command's per-document breakdown (only aggregate count and index size).

## Further Notes

- The Lucene index directory (`<store>/lucene/`) is created by `BM25Repository` on first `index()` call; no manual setup required.
- The store directory default `.ez-rag` is visually the same as the old `.ez-rag/vector-store.json` prefix, minimising user surprise. Existing `~/.ez-rag/config.yml` entries that specify `store-path: .ez-rag/vector-store.json` will need to be updated to `store-path: .ez-rag`.
- Since the project is not yet deployed, the `storePath` breaking change requires no migration tooling.
- The future `LuceneVectorStore` migration (out of scope here) would replace `SimpleVectorStore` + the separate `BM25Repository` with a single Lucene index, eliminating dual-index sync entirely.
