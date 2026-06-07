# PRD: Reranking Search Results

## Problem Statement

Vector similarity search (cosine similarity over embeddings) is an effective first-pass retrieval mechanism, but it has a well-known weakness: it measures semantic proximity in embedding space, not true query-document relevance. A document that uses similar vocabulary but is not actually relevant to the query can outscore a highly relevant document that uses different phrasing. Users of `ez-rag search` and `ez-rag query` may receive lower-quality results when the most relevant chunks are not in the top positions returned by the vector store.

## Solution

After the initial vector similarity search retrieves a pool of candidate chunks, a cross-encoder reranking model re-scores every candidate by jointly encoding the query and the chunk together. This produces a more accurate relevance score than embedding similarity alone. The reranker is configured independently from the embedding model, is opt-in (activated by setting `rerankModel`), and runs entirely locally via ONNX inference — no API keys or network calls required.

The final result set returned to the user contains the top `topK` chunks ordered by the cross-encoder's relevance score.

## User Stories

1. As a CLI user, I want to configure a reranker model by setting `rerankModel` in my config file, so that search results are reordered by a more accurate relevance score without changing any other configuration.
2. As a CLI user, I want reranking to be disabled by default (no `rerankModel` set), so that existing behaviour is unchanged until I explicitly opt in.
3. As a CLI user, I want to specify the reranker model on the command line with `--rerank-model`, so that I can experiment with different models without editing my config file.
4. As a CLI user, I want to set `RERANK_MODEL` as an environment variable, so that I can enable reranking in scripted or CI environments without a config file.
5. As a CLI user, I want the reranker model to be downloaded and cached automatically (the same way the embedding model is), so that I do not need to manage model files manually.
6. As a CLI user, I want the reranker to fetch more candidates from the vector store than `topK` before reranking, so that the reranker has a larger pool to choose the best results from.
7. As a CLI user, I want to configure the number of reranking candidates with `rerankCandidates` in my config file, so that I can tune the recall/speed tradeoff.
8. As a CLI user, I want `rerankCandidates` to default to `topK * 3` when reranking is active but `rerankCandidates` is not explicitly set, so that I get sensible behaviour out of the box.
9. As a CLI user, I want to override `rerankCandidates` via `--rerank-candidates` CLI flag, so that I can adjust the candidate pool per invocation.
10. As a CLI user, I want to set `RERANK_CANDIDATES` as an environment variable, so that I can control the candidate pool in scripted environments.
11. As a CLI user running `ez-rag search`, I want the returned chunks to be ordered by the cross-encoder relevance score (not the original embedding similarity score), so that the most relevant chunks appear first.
12. As a CLI user running `ez-rag query`, I want the chunks used to construct the LLM prompt to be the top `topK` results after reranking, so that the generated answer is grounded in the most relevant context.
13. As a CLI user, I want the `score` field in search results to reflect the cross-encoder relevance score when reranking is active, so that the displayed score accurately represents why that chunk was selected.
14. As a CLI user using `--output json`, I want the JSON `score` field to be the cross-encoder score when reranking is active, so that downstream tooling receives the most meaningful score.
15. As a CLI user using `--verbose`, I want to see which reranker model is being used, how many candidates were fetched, and how many were returned after reranking, so that I can understand and debug the reranking pipeline.
16. As an MCP client using the `search` tool, I want reranking to be applied transparently when configured, so that MCP consumers benefit from improved result quality without any client-side changes.
17. As an MCP client using the `query` tool, I want reranking to be applied transparently when configured, so that LLM-generated answers in MCP mode are also grounded in reranked context.
18. As a developer, I want the reranker to be injected as an optional dependency into `EmbeddingSearchPipeline`, so that the pipeline degrades gracefully to embedding-only search when no reranker is configured.
19. As a developer, I want the reranker to be expressed as a simple interface, so that I can mock it in unit tests without requiring model downloads.
20. As a developer, I want the ONNX cross-encoder implementation to be a separate module implementing the reranker interface, so that it can be tested in isolation as an integration test.

## Implementation Decisions

### New: `Reranker` interface

A new interface with a single method:

```
fun rerank(query: String, candidates: List<ChunkMatch>): List<ChunkMatch>
```

The interface accepts the raw query string and a list of candidates (already populated with content and metadata). It returns the same candidates re-scored and re-ordered, with `ChunkMatch.score` replaced by the cross-encoder's relevance score.

### New: `OnnxCrossEncoderReranker` (implements `Reranker`)

A concrete implementation that:
- Downloads and caches the cross-encoder ONNX model to `~/.ez-rag/models/` (same cache directory as the embedding model).
- Uses `onnxruntime` for inference and `ai.djl.huggingface:tokenizers` for tokenization.
- Tokenizes each `(query, chunk.content)` pair, runs a forward pass through the cross-encoder, and extracts the logit as the relevance score.
- Returns candidates sorted by relevance score descending, with `ChunkMatch.score` set to the cross-encoder score.

### Modified: `EmbeddingSearchPipeline`

- Accepts an optional `Reranker?` injected dependency (null = reranking disabled).
- When `reranker != null`:
  1. Computes effective candidate count: `rerankCandidates` (from query or config), default `topK * 3`.
  2. Calls `similaritySearch` with `topK = rerankCandidates` to get the enlarged candidate pool.
  3. Passes candidates to `reranker.rerank(query, candidates)`.
  4. Truncates the reranked list to `topK` before returning.
- When `reranker == null`: behaviour is identical to today.
- Verbose logging: logs reranker model name, candidate pool size, and final result size when verbose mode is active.

### Modified: `EzRagConfig`

Two new fields added to the config data class:
- `rerankModel: String = ""` — empty string means reranking disabled.
- `rerankCandidates: Int? = null` — null means auto-compute as `topK * 3` at resolution time.

### Modified: `ConfigService`

- Resolves `rerankModel` with the same cascading precedence: CLI flag → env var → config file → default (`""`).
- Resolves `rerankCandidates` with the same cascading precedence; when not set, computes `topK * 3` at resolution time (only when `rerankModel` is non-empty).

### Modified: `EzRagCommand` (inherited CLI options)

Two new inherited options added:
- `--rerank-model` → mapped to `rerankModel`.
- `--rerank-candidates` → mapped to `rerankCandidates`.

### Modified: `ProviderConfiguration`

- Creates an optional Spring bean for `OnnxCrossEncoderReranker` when `rerankModel` is non-empty.
- Injects `Reranker?` into `EmbeddingSearchPipeline`.

### New dependencies in `build.gradle.kts`

- `com.microsoft.onnxruntime:onnxruntime` — ONNX Runtime for Java.
- `ai.djl.huggingface:tokenizers` — HuggingFace tokenizer bindings for Java (already a transitive dependency via Spring AI transformers; made explicit).

### Configuration file format (YAML)

```yaml
rerankModel: "cross-encoder/ms-marco-MiniLM-L-6-v2"
rerankCandidates: 15   # optional; defaults to topK * 3
```

### Environment variables

| Variable | Maps to |
|---|---|
| `RERANK_MODEL` | `rerankModel` |
| `RERANK_CANDIDATES` | `rerankCandidates` |

### SearchQuery / RagQuery changes

`rerankCandidates` is passed through from config into the query objects so that `EmbeddingSearchPipeline` can read the effective value at search time.

## Testing Decisions

**What makes a good test:** Tests verify observable, external behaviour — the shape and ordering of results, the score values, the number of results returned — not the internal mechanics (how tokenization works, which ONNX API was called, etc.). Tests should be deterministic and require no network access or model downloads.

### Unit tests: `EmbeddingSearchPipelineTest` (extended)

- Prior art: existing `EmbeddingSearchPipelineTest` mocks `VectorStoreRepository` to return predetermined `Document` objects with scores.
- New tests inject a mock `Reranker` that returns candidates with a known score ordering that differs from the embedding score ordering.
- Verify that: the pipeline fetches `rerankCandidates` from the store (not `topK`), the final result list is truncated to `topK`, and `ChunkMatch.score` reflects the mock reranker's scores.
- Verify that: when `reranker` is null, behaviour is identical to the pre-reranking tests (no regression).

### Unit tests: `Reranker` interface contract tests

- A simple `StubReranker` (returns input reversed) used to validate the interface contract.
- No real model required.

### Integration test: `OnnxCrossEncoderRerankerTest` (tagged `@Tag("integration")`)

- Downloads `cross-encoder/ms-marco-MiniLM-L-6-v2` from HuggingFace on first run (cached).
- Verifies that a clearly relevant chunk scores higher than a clearly irrelevant chunk for a given query.
- Excluded from the default test run (CI can opt in via a Gradle test filter).

### No changes to `QueryCommand` or `SearchCommand` tests

The reranking is fully encapsulated in `EmbeddingSearchPipeline`. Command-level tests do not need to change.

## Out of Scope

- Remote reranking APIs (e.g., Cohere Rerank, Jina Reranker API).
- Combining the embedding score and cross-encoder score (score fusion / hybrid scoring).
- Diversity-aware reranking (MMR — Maximal Marginal Relevance).
- Lexical reranking (BM25).
- Per-command opt-in/opt-out of reranking (reranking applies globally when `rerankModel` is set).
- A `--no-rerank` override flag to disable reranking for a single invocation when it is set in config.
- Exposing `rerankCandidates` as a field in the search/query JSON output.
- Support for cross-encoder models requiring an API key.

## Further Notes

- The default recommended model `cross-encoder/ms-marco-MiniLM-L-6-v2` is a 6-layer MiniLM fine-tuned on MS MARCO passage ranking. It is fast enough for interactive use (sub-second on CPU for ≤25 candidates) and well-established in the reranking literature.
- Cross-encoder inference is O(candidates) forward passes; users with large `rerankCandidates` values on slow hardware should be aware of the latency tradeoff.
- The `rerankCandidates` cap should not exceed the total number of chunks in the store; `EmbeddingSearchPipeline` already handles this gracefully since `SimpleVectorStore.similaritySearch` returns at most the number of stored documents.
- Model files for the cross-encoder will be cached in `~/.ez-rag/models/` alongside the embedding model files, following the existing convention.
