# Tasks: MCP API Quality Improvements

## Task 01-search-result-path-rename

Rename the document identifier field from `filePath` to `path` in `ChunkMatch` and propagate the change through both search pipelines, `RrfFusion`, `RagPipeline`, all three MCP search tool result classes, and `OutputFormatter` search format methods. After this task, `search`, `search_bm25`, and `search_embedding` all return `path` (never `filePath`) in every result chunk.

### Implementation steps

- [x] In `OutputFormatterTest`, change the existing assertion that checks for `"source"` key in search JSON output to assert `"path"` instead — this test now fails
- [x] In `McpBm25SearchToolTest`, `McpSearchToolTest`, and `McpEmbeddingSearchToolTest`, change any assertion that reads `chunk.filePath` to read `chunk.path` — these tests now fail
- [x] Rename `filePath: String` → `path: String` in `ChunkMatch`
- [x] Fix all compilation errors: update `BM25SearchPipeline` and `EmbeddingSearchPipeline` to use `path =` when constructing `ChunkMatch`; update `RrfFusion` (references `chunk.filePath` as map key — now `chunk.path`); update `RagPipeline.query()` (`<document source="${chunk.path}">` wrapper)
- [x] Update `OutputFormatter` search-result format methods: `formatText` (`source=${chunk.filePath}` → `source=${chunk.path}`), `formatJson` (`"source"` JSON key → `"path"`), `formatXml` (uses `chunk.path`)
- [x] Update README JSON output examples for search commands to show `"path"` as the document identifier key
- [x] Verify all updated tests pass

### Acceptance criteria

- [x] Calling `search`, `search_bm25`, or `search_embedding` via MCP returns chunk objects with a `path` field and no `filePath` field
- [x] CLI `--output-format json` for search commands emits `"path"` (not `"source"` or `"filePath"`) as the document identifier key in chunk objects
- [x] All three MCP search tool unit tests assert the `path` field name and pass
- [x] `./gradlew test` passes with no regressions

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors and zero compiler warnings related to renamed fields
- [x] No pre-existing passing test is broken by this change


## Task 02-search-result-heading-path

Add `headingPath: List<String>?` to `ChunkMatch`, annotate `ChunkMatch` with `@JsonInclude(NON_NULL)` to suppress null heading paths in MCP JSON responses, and populate the field from the `heading_path` Lucene stored field in both search pipelines. After this task, search results include structured section context for Markdown chunks and the `headingPath` field is absent (not serialized as null) for sources without heading metadata.

### Implementation steps

- [x] Verify that `LuceneRepository.luceneDocToSpringDoc()` maps `"heading_path"` into `Document.metadata` — it does; no repository changes needed
- [x] In `McpBm25SearchToolTest`, `McpSearchToolTest`, and `McpEmbeddingSearchToolTest`, add assertions: (a) `headingPath` is populated when the stub returns a chunk with `"heading_path"` metadata; (b) `headingPath` is null and absent from JSON when the stub omits it — these assertions now fail
- [x] Add `headingPath: List<String>? = null` to `ChunkMatch`
- [x] Annotate `ChunkMatch` with `@JsonInclude(JsonInclude.Include.NON_NULL)` so null `headingPath` is omitted from all MCP JSON responses
- [x] In `BM25SearchPipeline.search()`, read `doc.metadata["heading_path"] as? List<String>` and pass as `headingPath` when constructing `ChunkMatch`
- [x] In `EmbeddingSearchPipeline.search()`, do the same
- [x] `RrfFusion` uses `data class copy()` on `ChunkMatch` — no code change needed; `./gradlew build` verifies the field is carried through
- [x] Verify all updated tests pass

### Acceptance criteria

- [x] When a chunk was ingested from a Markdown source with headings, the search result includes a non-null `headingPath` list
- [x] When a chunk comes from a source with no heading metadata (e.g. PDF, plain text), `headingPath` is absent from the JSON result (not serialized as `null`)
- [x] `headingPath` propagates through hybrid (RRF) search: a hybrid search result for a Markdown document includes `headingPath`
- [x] Tests cover both the present and absent cases with concrete stub data

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No unchecked-cast compiler warnings introduced in `BM25SearchPipeline` or `EmbeddingSearchPipeline`
- [x] No pre-existing passing test is broken by this change


## Task 03-query-result-field-rename

Rename `filePath` → `path` and `similarityScore` → `score` in `SourceReference`, propagating through `RagPipeline`, `ShellCommand`, the `McpQueryTool` result class, and `OutputFormatter` RAG format methods. After this task, `query` results use the same field names as search results.

### Implementation steps

- [x] In `OutputFormatterTest`, change the existing assertion that checks for `"file"` key in RAG JSON output to assert `"path"` instead — this test now fails
- [x] In `McpQueryToolTest`, change assertions that read `source.filePath` or `source.similarityScore` to read `source.path` and `source.score` — these tests now fail
- [x] Rename `filePath` → `path` and `similarityScore` → `score` in `SourceReference`
- [x] Fix all compilation errors: update `RagPipeline` (constructs `SourceReference` using named fields); update `ShellCommand` (`source.filePath` → `source.path`, `source.similarityScore` → `source.score`); update `McpQueryTool` result data class; update `OutputFormatter` RAG format methods (`source.filePath` → `source.path`, `source.similarityScore` → `source.score`, JSON key `"file"` → `"path"`)
- [x] Update README JSON output examples for the `query` command to use `"path"` and `"score"`
- [x] Verify `McpQueryToolTest` and `OutputFormatterTest` pass

### Acceptance criteria

- [x] `query` tool results contain `path` (not `filePath`) in source references
- [x] `query` tool results contain `score` (not `similarityScore`) in source references
- [x] CLI `--output-format json` for the `query` command emits `"path"` and `"score"` in source reference objects
- [x] `McpQueryToolTest` passes with assertions on both renamed fields

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change


## Task 04-omit-error-on-success

Apply `@JsonInclude(JsonInclude.Include.NON_NULL)` to every MCP tool result data class so that the `error: String? = null` field is omitted from successful JSON responses. Error-path responses (non-null `error`) remain unaffected.

Note: test code in this task constructs `ChunkMatch` and `SourceReference` using the renamed fields `path` and `score` introduced in tasks 01–03.

### Implementation steps

- [x] Add a serialization test to at least one existing MCP tool test (e.g. `McpSearchToolTest`) that: (a) serializes a successful result via Jackson and asserts the string does not contain `"error"`; (b) serializes a result with a non-null `error` and asserts `"error"` IS present in the JSON — both assertions now fail
- [x] Add `@JsonInclude(JsonInclude.Include.NON_NULL)` to each MCP result data class: `SearchToolResult` (in each of the three search tools), `ShowToolResult`, `ChunkToolResult`, `DeleteToolResult`, `QueryToolResult`, `IngestToolResult`, `ReIngestToolResult`, and `StoreStatus` in `McpStatusTool`
- [x] Verify all tests pass

### Acceptance criteria

- [x] A successful `search` result serialized to JSON does not contain an `error` key
- [x] A successful `show` result serialized to JSON does not contain an `error` key
- [x] A successful `delete` result serialized to JSON does not contain an `error` key
- [x] When a tool sets a non-null `error`, the `error` key IS present in the serialized JSON (false-negative check)
- [x] `@JsonInclude(NON_NULL)` is present on all MCP result data classes listed in the implementation steps

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change


## Task 05-search-bm25-min-score

Add `minScore: Double? = null` to `search_bm25` for consistency with the other two search tools, and correct the `search` tool description to accurately state that `minScore` filters final RRF-fused scores. `BM25SearchPipeline` already applies `minScore` filtering and `LuceneRepository.bm25Search()` already normalizes scores relative to the top result (top = 1.0) — no pipeline changes are needed.

### Implementation steps

- [x] Add test to `McpBm25SearchToolTest`: call `searchBm25` with `minScore = 0.8` and assert `SearchQuery.minScore == 0.8` is forwarded — test fails
- [x] Add test to `McpBm25SearchToolTest`: call `searchBm25` with `minScore = null` and assert `SearchQuery.minScore == 0.0` — test fails
- [x] Add `minScore: Double? = null` to `McpBm25SearchTool.searchBm25()` annotated with `@ToolParam(required = false)`; the description must note that BM25 scores are normalized relative to the top result in the current result set (top result = 1.0); pass `minScore ?: 0.0` to `SearchQuery.minScore`
- [x] Update the `minScore` `@ToolParam` description in `McpSearchTool` to remove any text claiming the parameter is ignored in hybrid mode; replace with text stating it filters the final RRF-fused scores (normalized to 0–1)
- [x] Verify both new tests pass

### Acceptance criteria

- [x] Calling `search_bm25` with `minScore: 0.8` forwards `0.8` to `SearchQuery.minScore`
- [x] Calling `search_bm25` without `minScore` uses `0.0` as the effective threshold
- [x] `McpBm25SearchToolTest` contains passing tests for both the forwarding and default cases
- [x] The `@ToolParam` description in `McpSearchTool` for `minScore` no longer claims the parameter is ignored in hybrid mode

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change


## Task 06-optional-parameters

Make `includeChunks` in `show` and `window` in `chunk` truly optional in the MCP schema. `includeChunks` changes from required `Boolean` to optional `Boolean? = null` (treated as `false` when absent). `window` is already `Int?` but gains `@ToolParam(required = false)` so it is visible as optional in the generated MCP schema.

### Implementation steps

- [x] Add test to `McpShowToolTest`: call `show` with `includeChunks = null` and assert success with no chunk text in the result — test fails
- [x] Add test to `McpChunkToolTest`: call `chunk` with `window = null` and assert success returning the single target chunk — test fails or confirms the call path works
- [x] Change `includeChunks: Boolean` → `includeChunks: Boolean? = null` in `McpShowTool.show()`; add `required = false` to its `@ToolParam`; update method body to treat `null` as `false` (`includeChunks == true`)
- [x] Add `@ToolParam(required = false, ...)` to `window` in `McpChunkTool.chunk()` (parameter type stays `Int?`)
- [x] Verify both new tests pass

### Acceptance criteria

- [x] Calling `show` with `includeChunks` omitted succeeds and behaves identically to `includeChunks: false`
- [x] Calling `chunk` with `window` omitted succeeds and returns the single requested chunk
- [x] Unit tests confirm both parameters accept `null` without error
- [x] Both `@ToolParam` annotations carry `required = false`

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change


## Task 07-list-tool

Add `McpListTool`, a new MCP tool that returns a per-document inventory of `path`, `chunkCount`, and `stale`. Remove the `documents` list from `McpStatusTool.StoreStatus` so `status` is a lightweight health-check-only response. `LuceneRepository.getMetadata(filesystemProbe)` already provides staleness data via `StoreDocumentInfo` — no repository changes are needed.

### Implementation steps

- [x] In `McpStatusToolTest`, remove or update the assertion that checks `result.documents` — this assertion must now fail (the field will be removed)
- [x] Write `McpListToolTest` with three test cases, each using an injected `filesystemProbe` lambda:
  - Normal inventory: multiple documents, all fresh — assert each entry has `path`, `chunkCount`, `stale: false`
  - Stale document: inject a probe returning a different mtime for one document — assert `stale: true` for that document
  - Empty store: assert the result is an empty list, not an error
  All three tests fail (class does not exist)
- [x] Remove `documents: List<DocumentInfo>` from `McpStatusTool.StoreStatus` and remove the now-unused inner `DocumentInfo` class from `McpStatusTool`; update `status()` to no longer map documents
- [x] Create `McpListTool`; define its own `DocumentInfo(path: String, chunkCount: Int, stale: Boolean)` result item annotated with `@JsonInclude(JsonInclude.Include.NON_NULL)`; `list()` delegates to `repository.getMetadata(filesystemProbe)` and maps each `StoreDocumentInfo` to `DocumentInfo`; accept a `filesystemProbe` constructor parameter (defaulting to real filesystem) to allow test injection
- [x] Register `McpListTool` in `McpServerCommand`'s `buildList` block alongside the other tools
- [x] Update `McpServerCommandTest` to assert a tool named `"list"` is exposed
- [x] Verify all tests pass

### Acceptance criteria

- [x] Calling `list` returns one entry per indexed document with `path`, `chunkCount`, and `stale` fields
- [x] A document modified on disk since last ingest has `stale: true` (detected via injectable filesystem probe)
- [x] Calling `list` against an empty store returns an empty JSON array (not an error)
- [x] `list` results do not include an `error` key on success
- [x] `McpStatusTool.StoreStatus` no longer contains a `documents` field
- [x] `McpListToolTest` contains passing tests for the normal, stale, and empty-store cases

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change (including `McpStatusToolTest` after updating its assertions)


## Task 08-query-system-prompt

Replace `DEFAULT_RAG_SYSTEM_PROMPT` in `RagPipeline` with knowledge-base framing that instructs the LLM to cite source paths, eliminating "based on the second document provided" anti-pattern answers.

### Implementation steps

- [x] Update the existing `RagPipeline` test that asserts the default system prompt text to expect the new knowledge-base wording — test fails
- [x] Replace `DEFAULT_RAG_SYSTEM_PROMPT` with: `"You are a helpful assistant. Answer the user's question using ONLY content from the knowledge base provided below. For each claim, cite the source path. If the answer is not in the knowledge base, say so. The conversation history shows earlier exchanges; you may refer to them when answering follow-up questions."`
- [x] Verify the test passes
- [x] Verify that `RagQuery` callers that supply a custom system prompt are unaffected (custom prompt still overrides the default)

### Acceptance criteria

- [x] `RagPipeline.DEFAULT_RAG_SYSTEM_PROMPT` contains "knowledge base" framing and instructs the LLM to cite source paths
- [x] `RagPipeline.DEFAULT_RAG_SYSTEM_PROMPT` does not contain the phrase `"context documents provided"`
- [x] A `RagPipeline` unit test asserts the exact new prompt text is used when no custom prompt is supplied
- [x] Callers that supply a custom prompt via `RagQuery` are not affected by this change

### Quality gates

- [x] `./gradlew build` succeeds with zero compiler errors
- [x] No pre-existing passing test is broken by this change
