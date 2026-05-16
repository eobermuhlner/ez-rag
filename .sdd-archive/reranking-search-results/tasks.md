# Tasks: Reranking Search Results

## Task [01-reranker-interface-and-pipeline]

Define the `Reranker` interface and integrate it into `EmbeddingSearchPipeline` so that the pipeline can inflate its candidate pool, delegate scoring to a reranker, and return the top-K reranked results. This task has no user-visible effect on its own — it is the developer-facing foundation that tasks 03–04 build on. All behaviour is verified through unit tests with a deterministic stub reranker; no model download is required.

### Implementation steps

- [x] Define `Reranker` interface with `val name: String` and `fun rerank(query: String, candidates: List<ChunkMatch>): List<ChunkMatch>` (returns candidates re-scored and re-ordered, with `ChunkMatch.score` replaced by the reranker's scores)
- [x] Add `rerankCandidates: Int? = null` to `SearchQuery` (null means reranking is disabled for this query)
- [x] Update `EmbeddingSearchPipeline` constructor to accept an optional `reranker: Reranker? = null`
- [x] In `EmbeddingSearchPipeline.search()`: when `reranker != null` AND `query.rerankCandidates != null`, fetch `query.rerankCandidates` candidates from the store (instead of `query.topK`), pass all candidates to `reranker.rerank(query.question, candidates)`, then truncate the reranked list to `query.topK`; when `reranker == null` or `rerankCandidates == null`, behaviour is unchanged
- [x] Implement `StubReranker` in test sources: assigns scores in reverse order (last candidate gets highest score) so the reranked order is the opposite of the original
- [x] Write new tests in `EmbeddingSearchPipelineTest` covering the reranking path

### Acceptance criteria

- [x] `EmbeddingSearchPipelineTest`: when constructed with a `StubReranker`, the first chunk in the result is the one the stub ranked highest (i.e. the chunk the embedding model ranked last)
- [x] `EmbeddingSearchPipelineTest`: when `rerankCandidates=6` and `topK=2`, the store is asked for 6 candidates and the result contains exactly 2 chunks
- [x] `EmbeddingSearchPipelineTest`: `ChunkMatch.score` values in the reranked result equal the scores assigned by the stub, not the original embedding similarity scores
- [x] `EmbeddingSearchPipelineTest`: when constructed without a `Reranker` (null) and `rerankCandidates=null`, all four existing test cases still pass unchanged
- [x] `EmbeddingSearchPipelineTest`: when constructed without a `Reranker` but `SearchQuery.rerankCandidates` is set to a non-null value, the pipeline ignores `rerankCandidates` and fetches `topK` from the store (reranker being null is the deciding gate)

### Quality gates

- [x] No Kotlin compiler warnings (project enforces `-Werror`)
- [x] `./gradlew test` passes

---

## Task [02-ragpipeline-delegates-to-search-pipeline]

Refactor `RagPipeline` to delegate its vector-search step to `EmbeddingSearchPipeline` instead of calling `similaritySearch` directly. This eliminates duplicate retrieval logic and ensures that when a `Reranker` is later wired into `EmbeddingSearchPipeline`, the `query` command benefits automatically without any further changes. Existing query behaviour must be unchanged.

### Implementation steps

- [x] Add `rerankCandidates: Int? = null` to `RagQuery` (mirrors `SearchQuery`)
- [x] Change `RagPipeline` constructor to accept `EmbeddingSearchPipeline` instead of `VectorStoreRepository`; remove the direct `similaritySearch` call from `RagPipeline.query()` and replace it with `searchPipeline.search(SearchQuery(question, topK, minScore=0.0, rerankCandidates=ragQuery.rerankCandidates))`
- [x] Map `ChunkMatch` objects returned by `searchPipeline.search()` to `SourceReference` in `RagPipeline`
- [x] Update `QueryCommand`: when no `ragPipeline` is injected, construct `EmbeddingSearchPipeline(repo, embeddingModel)` first, then pass it to `RagPipeline(embeddingSearchPipeline, chatModel)`
- [x] Update any existing `RagPipeline` tests to construct via the new signature

### Acceptance criteria

- [x] `RagPipeline` source contains no `similaritySearch` call
- [x] `QueryCommand` constructs an `EmbeddingSearchPipeline` before constructing a `RagPipeline` in the fallback (non-injected) path
- [x] `RagPipelineTest` (or equivalent): a query against a populated store returns a non-empty answer and correct source references — i.e. observable query output is unchanged
- [x] `RagQuery` has a `rerankCandidates` field that defaults to null
- [x] `./gradlew test` passes with no failures

### Quality gates

- [x] No Kotlin compiler warnings
- [x] No direct call to `repository.getStore().similaritySearch(...)` remains inside `RagPipeline`

---

## Task [03-config-cli-plumbing-for-reranking]

Expose `rerankModel` and `rerankCandidates` through the full configuration stack — config file, environment variables, and CLI flags — with the same cascading precedence as other config fields. Commands read these values and pass `rerankCandidates` into `SearchQuery` and `RagQuery` so the pipeline can use them. No real reranker is wired yet; the pipeline still receives `reranker=null` and ignores the field values at runtime.

### Implementation steps

- [x] Add `rerankModel: String = ""` and `rerankCandidates: Int? = null` to `EzRagConfig`
- [x] Add `rerankModel: String? = null` and `rerankCandidates: Int? = null` to `CliFlags`
- [x] Update `ConfigService.resolve()` to resolve `rerankModel` via `cliFlags.rerankModel ?: envVars["RERANK_MODEL"] ?: file.rerankModel` and `rerankCandidates` via `cliFlags.rerankCandidates ?: envVars["RERANK_CANDIDATES"]?.toIntOrNull() ?: file.rerankCandidates`; when `rerankModel` is non-empty and the resolved `rerankCandidates` is still null, default it to `config.topK * 3`; when `rerankModel` is empty, leave `rerankCandidates` as null
- [x] Add `--rerank-model` and `--rerank-candidates` options (both `ScopeType.INHERIT`) to `EzRagCommand`; map them into `CliFlags` when commands call `ConfigService.resolve()`
- [x] Update `SearchCommand` to compute `rerankCandidates` from the resolved config and pass it in `SearchQuery`
- [x] Update `QueryCommand` to compute `rerankCandidates` from the resolved config and pass it in `RagQuery`
- [x] Write `ConfigServiceTest` cases for the new fields

### Acceptance criteria

- [x] `ConfigServiceTest`: `rerankModel` resolves to the CLI flag value when the flag is set; to `RERANK_MODEL` env var when only the env var is set; to the config-file value when only the file sets it; to `""` when no source specifies it
- [x] `ConfigServiceTest`: when `rerankModel` is non-empty and `rerankCandidates` is not set by any source, the resolved `rerankCandidates` equals `topK * 3`
- [x] `ConfigServiceTest`: when `rerankModel` is empty (default), the resolved `rerankCandidates` is null
- [x] `ConfigServiceTest`: `rerankCandidates` set explicitly via CLI overrides the `topK * 3` default
- [x] `./gradlew test` passes

### Quality gates

- [x] No Kotlin compiler warnings
- [x] `EzRagCommand` exposes exactly two new options: `--rerank-model` and `--rerank-candidates`, both with `ScopeType.INHERIT`

---

## Task [04-onnx-cross-encoder-reranker]

Deliver end-to-end reranking with a real cross-encoder model: implement `OnnxCrossEncoderReranker` using ONNX Runtime and HuggingFace tokenizers, wire it as an optional Spring bean, and inject it into `EmbeddingSearchPipeline` (and transitively into `RagPipeline`) when `rerankModel` is configured. Setting `rerankModel: cross-encoder/ms-marco-MiniLM-L-6-v2` in the config file now produces genuinely reranked `search` and `query` results.

### Implementation steps

- [x] Add `com.microsoft.onnxruntime:onnxruntime` and `ai.djl.huggingface:tokenizers` as explicit dependencies in `build.gradle.kts` (versions compatible with existing Spring AI transitive pulls)
- [x] Implement `OnnxCrossEncoderReranker(modelName: String, cacheDir: String)`: download and cache the ONNX model and tokenizer config to `<cacheDir>/<modelName>/` on first use; for each candidate, tokenize the `(query, passage)` pair as a sequence-pair input, run a forward pass through the ONNX session, extract the logit (single output scalar or the relevant logit from a 2-class output), return candidates sorted by logit descending with `ChunkMatch.score` set to the logit value
- [x] Add `fun reranker(): Reranker?` bean to `ProviderConfiguration`: returns `OnnxCrossEncoderReranker(config.rerankModel, cacheDir)` when `config.rerankModel` is non-empty; returns null otherwise; declared `@Bean` so it is injectable as `Reranker?`
- [x] Update `SearchCommand`: optionally autowire `Reranker?` (`@Autowired(required = false)`); pass it to `EmbeddingSearchPipeline` when constructing the pipeline manually in the non-injected path
- [x] Update `QueryCommand`: same optional autowire; pass it when constructing `EmbeddingSearchPipeline`
- [x] Write `OnnxCrossEncoderRerankerTest` tagged `@Tag("integration")` using `cross-encoder/ms-marco-MiniLM-L-6-v2`

### Acceptance criteria

- [x] `OnnxCrossEncoderRerankerTest` (`@Tag("integration")`): given query `"What is the capital of France?"`, the chunk containing `"Paris is the capital of France"` scores higher than the chunk `"The weather in London is rainy"` — score order matches relevance
- [x] `OnnxCrossEncoderRerankerTest`: model files are present in `~/.ez-rag/models/cross-encoder/ms-marco-MiniLM-L-6-v2/` after the test runs
- [x] All unit tests in the default test run pass without any network access or ONNX model download (unit tests use `StubReranker` only; `OnnxCrossEncoderReranker` is never instantiated in unit tests)
- [x] `./gradlew test` (default, no integration tag) passes
- [x] `./gradlew test -Dtags=integration` downloads and runs the cross-encoder integration test successfully

### Quality gates

- [x] No Kotlin compiler warnings
- [x] No test in the default test run instantiates `OnnxCrossEncoderReranker` directly

---

## Task [05-verbose-reranker-diagnostics]

When `--verbose` is active and a reranker is configured, `EmbeddingSearchPipeline` writes two diagnostic lines to stderr so users can see which reranker is running and how the candidate pool was reduced.

### Implementation steps

- [x] Add `errWriter: PrintWriter = PrintWriter(System.err, true)` parameter to `EmbeddingSearchPipeline` constructor (matches the pattern already used in commands)
- [x] In the reranking path of `EmbeddingSearchPipeline.search()`, write the following two lines to `errWriter` when `verbose=true` is passed alongside the query — add `verbose: Boolean = false` to `SearchQuery`:
  - `"Reranker: <reranker.name>"`
  - `"Reranking: <rerankCandidates> candidates → top <topK>"`
- [x] Update `SearchCommand` and `QueryCommand` to pass their `verbose` field into `SearchQuery` and `RagQuery`; update `RagPipeline` to forward `verbose` in the `SearchQuery` it creates internally

### Acceptance criteria

- [x] When `verbose=true` and a `StubReranker` is injected, `EmbeddingSearchPipeline.search()` writes exactly a line starting with `"Reranker: "` and a line starting with `"Reranking: "` to the `errWriter`
- [x] The `"Reranker: "` line contains the stub's `name` value
- [x] The `"Reranking: "` line matches the pattern `"Reranking: <N> candidates → top <K>"` where N equals `rerankCandidates` and K equals `topK`
- [x] When `verbose=false` (default), no reranker diagnostic lines are written even when a reranker is active
- [x] When `verbose=true` but no reranker is configured (`reranker=null`), no reranker diagnostic lines are written
- [x] `./gradlew test` passes

### Quality gates

- [x] No Kotlin compiler warnings
- [x] The two diagnostic line formats are verified by string prefix assertions (not substring contains), so the test fails if the format regresses
