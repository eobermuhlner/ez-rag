# Tasks: 04-rag-query

## Task 01-rag-pipeline

Define `RagQuery`, `RagResult`, `SourceReference` data types and implement `RagPipeline`. The pipeline embeds the question, searches the vector store for the top-k most similar chunks, builds a RAG prompt (system prompt + retrieved context labeled per source + user question), sends it to `ChatModel`, and returns a `RagResult` containing the answer and source references. If the store yields zero chunks the pipeline returns a "No relevant documents found" answer without calling the `ChatModel`.

### Implementation steps

- [x] Write failing test: `RagPipeline` with empty store returns `RagResult(answer = "No relevant documents found", sources = emptyList())` without invoking the stub `ChatModel`
- [x] Define `RagQuery(question: String, topK: Int, systemPrompt: String, modelOverride: String?)`, `SourceReference(filePath: String, chunkIndex: Int, similarityScore: Double, excerpt: String)`, `RagResult(answer: String, sources: List<SourceReference>)` data classes
- [x] Implement `RagPipeline.query(RagQuery)` no-documents path to pass that test
- [x] Write failing test: with a pre-populated store and capturing stub `ChatModel`, the `UserMessage` content sent to `ChatModel` contains each retrieved chunk's text preceded by a `--- Context:` label and ends with the user question
- [x] Implement normal retrieval path: embed question → `similaritySearch` → `buildPrompt` → `ChatModel.call` → return `RagResult`
- [x] Write failing test: `topK=1` with two chunks in store produces exactly one source in `RagResult.sources`
- [x] Implement top-k limiting in the similarity search call
- [x] Write failing test: `SourceReference.excerpt` is truncated to at most 200 characters even when chunk text is longer
- [x] Implement excerpt truncation in the source mapping step
- [x] Write failing test: `SourceReference.chunkIndex` equals the integer stored in the chunk's `"chunk_index"` metadata field (set during ingest)
- [x] Implement `chunkIndex` extraction from document metadata
- [x] Write failing test: when `RagQuery.systemPrompt` is blank, the `SystemMessage` content contains the default RAG system prompt constant (`DEFAULT_RAG_SYSTEM_PROMPT`)
- [x] Write failing test: a non-blank `RagQuery.systemPrompt` replaces the default text in the `SystemMessage`

### Acceptance criteria

- [x] With an empty vector store, `RagPipeline.query()` returns `RagResult` with `answer == "No relevant documents found"` and `sources.isEmpty() == true`, without invoking `ChatModel`
- [x] With a populated store, the `UserMessage` sent to `ChatModel` contains each retrieved chunk's text and ends with the user question
- [x] `RagResult.sources` contains one `SourceReference` per retrieved chunk with correct `filePath`, `similarityScore`, `chunkIndex`, and `excerpt.length <= 200`
- [x] `topK` parameter limits the number of retrieved chunks (and sources) to exactly the specified value when more chunks exist
- [x] Default system prompt text is present in the `SystemMessage` when `RagQuery.systemPrompt` is blank
- [x] A non-blank `RagQuery.systemPrompt` replaces the default system prompt in the `SystemMessage`

### Quality gates

- [x] No compiler warnings
- [x] All unit tests pass without a Spring context and without a live LLM or embedding API

---

## Task 02-output-formatter

Implement `OutputFormatter` that renders a `RagResult` in either text or JSON format. Text format: answer paragraph followed by a `--- Sources ---` section listing each source with its file path and score. JSON format: `{ "answer": "...", "sources": [{ "file": "...", "score": 0.87, "excerpt": "..." }] }`.

### Implementation steps

- [x] Write failing test: text format of a `RagResult` with one source contains the answer text and a `--- Sources ---` line followed by the file path and score
- [x] Implement `OutputFormatter.formatText(result: RagResult): String`
- [x] Write failing test: text format with an empty sources list does not contain `--- Sources ---`
- [x] Write failing test: JSON format of a `RagResult` with one source parses as valid JSON with an `answer` string and a `sources` array containing `file`, `score`, and `excerpt` keys
- [x] Implement `OutputFormatter.formatJson(result: RagResult): String`
- [x] Write failing test: JSON format with empty sources produces `"sources": []`

### Acceptance criteria

- [x] Text output contains the answer text followed by `--- Sources ---` when sources are non-empty
- [x] Text output with an empty sources list does not contain `--- Sources ---`
- [x] JSON output parses as valid JSON with `answer` (string) and `sources` (array)
- [x] Each JSON source object contains `file` (string), `score` (number), and `excerpt` (string)
- [x] JSON output with empty sources contains `"sources": []`

### Quality gates

- [x] No compiler warnings
- [x] `OutputFormatter` carries no Spring annotations — pure Kotlin class usable without a Spring context
- [x] Unit tests require no Spring context

---

## Task 03-query-command

Wire `QueryCommand` to use `RagPipeline` and `OutputFormatter`. Add `--top-k`, `--model`, `--system-prompt`, `--output` options, stdin reading (via injected `InputStream`) when `--question` is absent, error exit when the vector store file does not exist, and `--verbose` chunk details written to stderr.

### Implementation steps

- [x] Write failing test: `QueryCommand` with a non-existent store path exits code 1 and stdout contains the word "ingest"
- [x] Inject `VectorStoreRepository` (or store path), `RagPipeline`, `OutputFormatter`, `outputWriter: PrintWriter`, `errorWriter: PrintWriter`, `inputStream: InputStream` into `QueryCommand` constructor; implement store-existence check at the top of `call()`
- [x] Write failing test: `--question "hello"` with a pre-populated store and stub pipeline exits code 0 and answer appears on `outputWriter`
- [x] Implement the main `call()` path: resolve question → call `RagPipeline.query()` → format → print
- [x] Write failing test: when `--question` is absent, `QueryCommand` reads the injected `InputStream` until EOF and uses the full content as the question
- [x] Implement stdin reading via injected `InputStream`
- [x] Write failing test: empty `InputStream` (no `--question`) exits code 1 with message "No question provided"
- [x] Write failing test: `--output json` produces output routed through `OutputFormatter.formatJson()`
- [x] Implement `--output` flag routing to the appropriate formatter method
- [x] Write failing test: `--top-k 2` passes `topK=2` to `RagPipeline.query()` via `RagQuery`
- [x] Write failing test: `--model "claude-3-5-sonnet"` passes `modelOverride="claude-3-5-sonnet"` in `RagQuery`
- [x] Write failing test: `--system-prompt "Custom"` passes `systemPrompt="Custom"` in `RagQuery`
- [x] Write failing test: `--verbose` writes at least one line per source to `errorWriter` containing the file path and score

### Acceptance criteria

- [x] Running with a missing vector store exits code 1 and stdout contains the word "ingest"
- [x] `--question "..."` produces the answer on stdout and exits code 0
- [x] Absence of `--question` reads stdin until EOF and uses the full content as the question
- [x] Empty stdin (no `--question`) exits code 1 with message "No question provided"
- [x] `--output json` produces JSON-formatted output
- [x] `--top-k N` is passed as `RagQuery.topK = N`
- [x] `--model X` is passed as `RagQuery.modelOverride = X`
- [x] `--system-prompt "..."` is passed as `RagQuery.systemPrompt`
- [x] `--verbose` writes one line per retrieved source to stderr containing the source file path and similarity score

### Quality gates

- [x] No compiler warnings
- [x] All unit tests pass without a running Spring context

---

## Task 04-integration-test

Add an end-to-end integration test that ingests a small text file using a fake `EmbeddingModel` and a controllable stub `ChatModel`, runs `QueryCommand` with a question, and verifies that the wired system (with real disk I/O through `VectorStoreRepository`) returns an answer and lists the ingested file as a source.

The stub `ChatModel` returns a fixed answer string; tests assert structural correctness (exit code, sources, JSON validity) rather than answer quality.

### Implementation steps

- [x] Write integration test: ingest a single text file via `IngestCommand`, then call `QueryCommand` with `--question`, assert exit code 0 and the answer is non-empty
- [x] Assert `RagResult.sources` (captured via a recording `OutputFormatter` stub or parsed from output) contains the ingested file's path
- [x] Write test: `--output json` produces valid JSON with `sources[0].file` matching the ingested file path
- [x] Write test: querying with no existing store file exits non-zero and output contains "ingest"
- [x] Write test: a multi-line question provided via stdin produces exit code 0

### Acceptance criteria

- [x] After ingesting a text file, querying exits code 0 and the answer is non-empty
- [x] The sources in the output contain the ingested file's path
- [x] `--output json` output is valid JSON with `sources` containing at least one entry whose `file` matches the ingested path
- [x] Querying with no store file exits non-zero with output containing "ingest"
- [x] Multi-line stdin question is accepted and exits code 0

### Quality gates

- [x] No live LLM or embedding API required — stub `ChatModel` and fake `EmbeddingModel` only
- [x] No `@SpringBootTest` context required
