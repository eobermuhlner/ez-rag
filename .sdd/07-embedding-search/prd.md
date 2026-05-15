## Problem Statement

Users and agentic tools sometimes need pure semantic similarity search without LLM-generated answers. The full RAG pipeline in `query` (PRD 03) always calls a chat model, which costs money, adds latency, and requires LLM provider credentials. For use cases such as inspecting retrieval quality, building downstream pipelines, or operating in privacy-sensitive environments where document content must not reach a cloud LLM, users need a way to retrieve raw matching chunks directly from the vector store using only embeddings.

## Solution

Implement the `search` subcommand. It accepts a question from `--question` or stdin, embeds it using the configured embedding provider, retrieves the top-k most similar chunks from the vector store, optionally filters by a minimum similarity score, and prints the full chunk content with source and score metadata. No chat model is involved. The same capability is exposed as an MCP tool in `mcp-server` (PRD 05) and as a `/search` meta-command in the interactive shell (PRD 06).

## User Stories

1. As a user, I want to run `ez-rag search --question "What is the architecture?"` and get the raw matching chunks back, so that I can read the source material directly without an LLM summarising it.
2. As a user, I want to pipe a question via stdin (`echo "What is X?" | ez-rag search`), so that I can compose `search` with other shell tools.
3. As a user, I want each result to show the source file, similarity score, chunk index, and full chunk content, so that I have complete information to act on.
4. As a user, I want `--output json` to produce `{ "chunks": [{ "file": "...", "chunkIndex": 0, "score": 0.87, "content": "..." }] }`, so that downstream tools and scripts can parse results programmatically.
5. As a user, I want to control how many chunks are returned with `--top-k`, so that I can tune the result set size.
6. As a user, I want to filter out low-confidence results with `--min-score`, so that I only see chunks above a meaningful similarity threshold.
7. As a user, I want results ordered by similarity score descending, so that the most relevant chunk appears first.
8. As a user, I want the search to fail with a clear error if the vector store does not exist, so that I know I need to run `ingest` first.
9. As a user, I want `--verbose` to show the raw embedding vector dimension and the total number of chunks searched, so that I can understand the retrieval mechanics.
10. As a developer, I want `search` to depend only on `EmbeddingModel` and `VectorStoreRepository`, with no `ChatModel` dependency, so that it works without any LLM provider configured.
11. As an agentic tool (e.g., Claude Code), I want to call the `search` MCP tool and receive structured chunk data, so that I can incorporate retrieved context into my own reasoning without delegating generation to a second LLM.
12. As a user, I want to use `--embedding-provider` and `--embedding-model` flags to control which embedding model is used, so that search uses the same embedding space as ingestion.

## Implementation Decisions

- **SearchCommand**: A picocli `@Command(name = "search")` Spring bean. Accepts `--question` (String, optional — reads stdin if absent), `--top-k` (Int, default 5), `--min-score` (Double, default 0.0), `--store` (Path), `--embedding-provider` (String), `--embedding-model` (String), `--output` (enum: `text`/`json`, default `text`), `--verbose` (Boolean). Does **not** accept `--provider`, `--model`, or `--system-prompt`.
- **EmbeddingSearchPipeline module**: The central module. Accepts a `SearchQuery` (question: String, topK: Int, minScore: Double) and returns a `SearchResult` (list of `ChunkMatch`). Internally:
  1. Embeds the question via `EmbeddingModel`.
  2. Calls `VectorStoreRepository.search(vector, topK)` to get candidate chunks.
  3. Filters out chunks with score < `minScore`.
  4. Returns results sorted by score descending.
- **ChunkMatch**: A value type with `filePath: String`, `chunkIndex: Int`, `score: Double`, `content: String` (full chunk text). No truncation.
- **Text output format**: One block per chunk, separated by a blank line. Header line: `[1] score=0.87  source=docs/arch.md  chunk=3`. Then the full chunk content. Example:
  ```
  [1] score=0.87  source=docs/arch.md  chunk=3
  The architecture consists of three layers...

  [2] score=0.74  source=docs/overview.md  chunk=1
  An overview of the system...
  ```
- **JSON output format**: `{ "chunks": [{ "file": "docs/arch.md", "chunkIndex": 3, "score": 0.87, "content": "The architecture consists of..." }] }`. Top-level key is `chunks` (not `sources`) to distinguish from the `query` output schema.
- **Stdin reading**: Same as `QueryCommand` — reads all of stdin until EOF if `--question` is absent. Empty stdin is an error.
- **No ChatModel dependency**: `SearchCommand` and `EmbeddingSearchPipeline` must not import or reference any `ChatModel` type. This is enforced by keeping them in a module that does not depend on the chat provider configuration.
- **OutputFormatter reuse**: The existing `OutputFormatter` from PRD 03 is extended to handle `SearchResult` rendering in both text and JSON modes.
- **VectorStoreRepository reuse**: `EmbeddingSearchPipeline` calls the same `VectorStoreRepository` used by `RagPipeline`. No changes to `VectorStoreRepository` are required beyond confirming that its search API returns similarity scores (which PRD 02 already specifies via metadata).
- **Stub in PRD 01**: `SearchCommand` is added as a stub alongside `IngestCommand`, `QueryCommand`, etc. in the project scaffolding (see PRD 01 update).

## Testing Decisions

- **What makes a good test**: Test `EmbeddingSearchPipeline` with a mock `EmbeddingModel` and a pre-populated in-memory vector store. Assert that the returned `ChunkMatch` list is sorted by score, that `minScore` filtering removes low-scoring chunks, and that `topK` caps the result count.
- **EmbeddingSearchPipeline**: Unit-test with stub `EmbeddingModel` (returns a fixed vector) and an in-memory `VectorStoreRepository` pre-loaded with known documents. Test top-k limiting, min-score filtering, score-descending ordering, and the zero-results path.
- **OutputFormatter extension**: Unit-test text and JSON rendering of a fixed `SearchResult`. Assert the header format, blank-line separators, and JSON schema.
- **SearchCommand**: Unit-test that stdin is read when `--question` is absent, and that empty stdin produces an error exit code.
- **Integration test**: Ingest a small `.txt` file, run `search` against a term known to be in the file, assert at least one chunk is returned containing the expected text and the source path matches the ingested file.
- **No ChatModel in test classpath**: Add a test that verifies `EmbeddingSearchPipeline` can be constructed without any `ChatModel` bean present in the Spring context.

## Out of Scope

- Hybrid search (keyword + semantic).
- Re-ranking with a cross-encoder.
- Streaming output of chunks as they are retrieved.
- Filtering by source file or metadata beyond similarity score.
- Conversation history or follow-up questions.

## Further Notes

The primary motivation for `search` is the use case where no LLM provider credentials are available or desired — for example, a fully offline setup using ONNX embeddings (`--embedding-provider onnx`) where users want to retrieve relevant passages without any cloud API call. The `--min-score` flag is particularly valuable here: without an LLM to synthesise and filter, raw score thresholding is the user's main tool for controlling result quality. The recommended default of 0.0 (no filtering) is intentional — users discover the score range by first seeing all results, then tuning.

PRDs affected by this addition:
- **PRD 01**: `SearchCommand` stub added to the subcommand list.
- **PRD 05**: `search` MCP tool added (`search(question, topK?, minScore?)` → `SearchResult`).
- **PRD 06**: `/search <question>` added as a REPL meta-command in the interactive shell.
