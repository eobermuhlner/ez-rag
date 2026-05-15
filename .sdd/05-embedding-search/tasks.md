# Tasks: 05-embedding-search

## Task [01-search-domain-and-pipeline]

Create the `SearchQuery`/`ChunkMatch`/`SearchResult` value types and `EmbeddingSearchPipeline`, which embeds a question, retrieves chunks from `VectorStoreRepository`, filters by `minScore`, limits to `topK`, and returns them sorted by score descending. No `ChatModel` is involved anywhere in this slice.

### Implementation steps

- [x] Define `SearchQuery(question: String, topK: Int, minScore: Double)`, `ChunkMatch(filePath: String, chunkIndex: Int, score: Double, content: String)`, and `SearchResult(chunks: List<ChunkMatch>)` as data classes in the `rag` package
- [x] Implement `EmbeddingSearchPipeline(repository: VectorStoreRepository, embeddingModel: EmbeddingModel)` with a `search(query: SearchQuery): SearchResult` method
- [x] Inside `search`: call `repository.getStore().similaritySearch(SearchRequest.builder().query(question).topK(topK).similarityThreshold(minScore).build())`, map results to `ChunkMatch` using `doc.score ?: 0.0` and `doc.text ?: ""` (no 200-char truncation — full content always), sort descending by score, return as `SearchResult`
- [x] Write unit test: pre-populate in-memory store with two docs using a fake embedding model that returns *distinct* vectors per document (so cosine scores actually differ); assert returned chunks are sorted by score descending
- [x] Write unit test: `minScore=1.0` with all chunks scoring below 1.0 returns empty `SearchResult`
- [x] Write unit test: `topK=1` with two chunks in the store returns exactly one `ChunkMatch`
- [x] Write unit test: `EmbeddingSearchPipeline` can be constructed and `search()` invoked without any `ChatModel` class on the call stack

### Acceptance criteria

- [x] `search(query)` returns `ChunkMatch` list sorted by `score` descending when docs have distinct scores
- [x] `search(query)` with `minScore` above all document scores returns an empty list
- [x] `search(query)` with `topK=1` returns at most 1 chunk even when the store holds more
- [x] `ChunkMatch.content` contains the full chunk text with no length truncation applied
- [x] `EmbeddingSearchPipeline` compiles and runs without importing or referencing any `ChatModel` type

### Quality gates

- [x] No compiler warnings in the new classes
- [x] No reference to `ChatModel` or `ChatResponse` anywhere in `EmbeddingSearchPipeline` or the three new domain types
- [x] All existing `RagPipelineTest` tests still pass

---

## Task [02-search-result-formatting]

Extend `OutputFormatter` to render `SearchResult` in both text and JSON modes. Text uses one block per chunk with a specific header line; JSON uses a top-level `chunks` key (not `sources`) to distinguish it from the `query` output schema. Existing `RagResult` formatting must remain unchanged.

### Implementation steps

- [x] Add `formatText(result: SearchResult): String` to `OutputFormatter`: each chunk produces a header line `[N] score=<2-decimal-score>  source=<filePath>  chunk=<chunkIndex>` (score formatted as `"%.2f".format(score)`) followed by the full content; blocks are separated by a blank line; a result with zero chunks returns an empty string
- [x] Add `formatJson(result: SearchResult): String` to `OutputFormatter`: produces `{ "chunks": [{ "file": "...", "chunkIndex": N, "score": X.XX, "content": "..." }] }`; use the existing `escapeJsonString` helper for `file` and `content`
- [x] Write unit test: `formatText` of a fixed `SearchResult` with two chunks contains the exact header format `[1] score=0.87  source=...  chunk=...` for the first chunk and `[2]` for the second, with a blank line between them
- [x] Write unit test: `formatText` of a `SearchResult` with zero chunks returns an empty string
- [x] Write unit test: `formatJson` contains `"chunks"` as the top-level key (not `"sources"` or `"results"`) and each entry has `file`, `chunkIndex`, `score`, and `content` keys
- [x] Write unit test: `formatJson` with zero chunks produces `{ "chunks": [] }`
- [x] Write unit test: backslash, double-quote, and newline in `ChunkMatch.content` are escaped in the JSON output

### Acceptance criteria

- [x] Text header format matches `[N] score=X.XX  source=<path>  chunk=<index>` where score is two decimal places
- [x] Text blocks are separated by exactly one blank line between consecutive chunks; a single-chunk result has no trailing blank line
- [x] Text output for zero chunks is an empty string
- [x] JSON top-level key is `chunks` (not `sources` or `results`)
- [x] JSON entries include `file`, `chunkIndex`, `score`, and `content` keys
- [x] JSON output is parseable as valid JSON
- [x] Backslash, double-quote, and newline in content are escaped in JSON output

### Quality gates

- [x] No compiler warnings in the modified `OutputFormatter`
- [x] All existing `OutputFormatterTest` tests for `RagResult` still pass

---

## Task [03-search-command]

Implement the full `SearchCommand` end-to-end: parse all flags, read question from stdin when `--question` is absent, fail clearly when the vector store is missing, wire `EmbeddingSearchPipeline` and `OutputFormatter`, print verbose diagnostics to stderr, and verify correct behaviour with both unit tests and an integration test (ingest → search → assert).

### Implementation steps

- [x] Replace the stub `SearchCommand.call()` with a full implementation following the same structure as `QueryCommand`: resolve question from `--question` or stdin (reading all bytes until EOF); if stdin result is empty, print error and return exit code 1; check store file exists, otherwise print a message including the store path and return exit code 1
- [x] Add picocli `@Option` fields: `--top-k` (Int, default 5), `--min-score` (Double, default 0.0), `--store` (String?), `--output` (String, default "text"), `--verbose` (Boolean)
- [x] Inject `EmbeddingModel` via `@Autowired(required = false) var springEmbeddingModel: EmbeddingModel?`; if null, print a clear error and return exit code 1 — do not inject or reference `ChatModel` at all
- [x] Build `VectorStoreRepository` and `EmbeddingSearchPipeline` from the resolved store path and embedding model; call `pipeline.search(SearchQuery(...))` and format via `OutputFormatter`
- [x] When `--verbose`, write to stderr: the embedding model dimension (from `embeddingModel.dimensions()`) and the total chunk count (from `repository.getMetadata().chunkCount`)
- [x] Write unit test: when `--question` is absent the command reads from the injected `InputStream`; assert the text read becomes the search question
- [x] Write unit test: empty stdin (zero bytes) produces exit code 1 with a non-empty error message
- [x] Write unit test: when the vector store file does not exist the command exits with code 1 and the error message contains the store path
- [x] Write integration test: ingest a small `.txt` file from `src/test/resources/documents/`, then construct and call `SearchCommand` with a term known to appear in that file; assert at least one `ChunkMatch` is returned whose `filePath` matches the ingested file's path string
- [x] Verify that updating the stub does not break the existing `SubcommandTest` tests; adjust any test that assumes the stub always exits 0

### Acceptance criteria

- [x] `ez-rag search --question "X"` returns chunks from the vector store with exit code 0
- [x] `echo "X" | ez-rag search` (no `--question`) reads from stdin and returns the same result
- [x] Empty stdin produces exit code 1 with a user-readable error message
- [x] Running `search` when the vector store file does not exist exits with code 1 and a message containing the store path
- [x] `--output json` produces JSON with a `chunks` top-level key
- [x] `--verbose` writes embedding dimension and total chunk count to stderr (not stdout)
- [x] `--top-k N` limits returned chunks to at most N
- [x] `--min-score T` filters out chunks with score below T
- [x] `SearchCommand` does not import or reference `ChatModel`
- [x] Integration test: at least one returned `ChunkMatch.filePath` matches the path of the ingested file

### Quality gates

- [x] No compiler warnings in `SearchCommand`
- [x] No `ChatModel` reference anywhere in `SearchCommand`
- [x] All existing `SubcommandTest` tests still pass (update any test relying on the stub's always-0 exit code if necessary)
- [x] All other existing tests pass (no regression)
