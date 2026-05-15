## Problem Statement

Users and agentic tools need to ask natural-language questions against the ingested document corpus and receive accurate, grounded answers. Without a query pipeline, the vector store is only a storage artifact with no value. The pipeline must embed the question, retrieve relevant chunks, augment the LLM prompt with context, generate an answer, and present it with source citations.

## Solution

Implement the `query` subcommand. It reads a question from `--question` or stdin, embeds it using the configured embedding provider, retrieves the top-k most similar chunks from the vector store, constructs a RAG prompt combining the question and retrieved context, sends it to the configured LLM, and prints the answer along with source citations to stdout.

## User Stories

1. As a user, I want to run `ez-rag query --question "What is the architecture?"`, so that I get an answer grounded in my ingested documents.
2. As a user, I want to pipe a question via stdin (`echo "What is X?" | ez-rag query`), so that I can compose `ez-rag` with other shell tools.
3. As a user, I want the answer followed by source citations (document path + relevance score), so that I can verify where the information came from.
4. As a user, I want to use `--output json` to get a structured response `{ "answer": "...", "sources": [...] }`, so that agentic tools can parse the result programmatically.
5. As a user, I want the tool to say clearly "No relevant documents found" if the store is empty or no chunks exceed a minimum similarity threshold, so that I get honest feedback rather than a hallucinated answer.
6. As a user, I want to control how many chunks are retrieved with `--top-k`, so that I can trade off context richness against token cost.
7. As a user, I want to override the LLM model for a single query with `--model`, so that I can compare answers from different models.
8. As a user, I want to override the system prompt with `--system-prompt`, so that I can give the LLM domain-specific instructions for a particular query.
9. As a user, I want the query to fail with a clear error if the vector store does not exist yet, so that I understand I need to run `ingest` first.
10. As a user, I want `--verbose` to show which chunks were retrieved and their similarity scores, so that I can debug retrieval quality.
11. As a developer, I want the RAG pipeline to depend only on `EmbeddingModel` and `ChatModel` interfaces, so that the provider implementation is fully substitutable.
12. As a user, I want the default system prompt to instruct the LLM to answer only from the provided context and say "I don't know" when the answer isn't in the documents, so that the tool does not hallucinate.
13. As a user, I want the system prompt to be overridable via `~/.ez-rag/config.yml`, so that I can set a project-specific or domain-specific instruction persistently.
14. As an agentic tool (e.g., Claude Code), I want to call `ez-rag query` and parse the JSON output, so that I can incorporate retrieved context into my own reasoning.
15. As a user, I want multi-line questions supported (e.g., pasted code block via stdin), so that I can ask complex questions.

## Implementation Decisions

- **RagPipeline module**: The central module. Accepts a `RagQuery` (question string, topK, systemPrompt, model) and returns a `RagResult` (answer string, list of `SourceReference`). Internally:
  1. Embeds the question via `EmbeddingModel`.
  2. Searches `VectorStoreRepository` for top-k similar chunks.
  3. Builds a prompt: system prompt + retrieved chunks (each labeled with source) + user question.
  4. Sends to `ChatModel` and returns the response.
- **SourceReference**: A value type with `filePath: String`, `chunkIndex: Int`, `similarityScore: Double`, `excerpt: String` (first 200 chars of the chunk). Included in both text and JSON output.
- **Minimum similarity threshold**: Configurable (default 0.0, effectively disabled). Chunks below the threshold are excluded before passing to the LLM. If zero chunks remain, a "no relevant documents" response is returned without calling the LLM.
- **Default RAG system prompt**:
  ```
  You are a helpful assistant. Answer the user's question using ONLY the context documents provided below.
  If the answer is not found in the context, say "I don't know based on the provided documents."
  Always cite which document(s) your answer is based on.
  ```
  Stored as a constant, overridable via config/CLI.
- **Stdin reading**: If `--question` is absent, `QueryCommand` reads all of stdin until EOF. Empty stdin is an error.
- **Output formatting**: A dedicated `OutputFormatter` module handles both text and JSON rendering of `RagResult`. Text format: answer paragraph, then `--- Sources ---` section listing each source. JSON format: `{ "answer": "...", "sources": [{ "file": "...", "score": 0.87, "excerpt": "..." }] }`.
- **VectorStoreRepository** (from PRD 02) is reused for search. `QueryCommand` depends only on `RagPipeline` and `OutputFormatter`.

## Testing Decisions

- **What makes a good test**: Test `RagPipeline` with a mock `EmbeddingModel` and mock `ChatModel`. Assert that the prompt sent to the `ChatModel` contains the retrieved chunks and the user question. Assert that the returned `RagResult` contains the expected sources.
- **RagPipeline**: Unit-test with a pre-populated in-memory vector store, stub `EmbeddingModel` (returns fixed vector), stub `ChatModel` (returns fixed string). Test that top-k limiting works, that the "no documents" path triggers correctly, and that sources are populated.
- **OutputFormatter**: Unit-test text and JSON rendering with a fixed `RagResult`. Assert exact output structure without running a Spring context.
- **Integration test**: End-to-end: ingest a small text file, run a query whose answer is in the file, assert the answer contains the expected information and the source lists the ingested file.

## Out of Scope

- Streaming responses (token-by-token output).
- Conversation history / multi-turn chat.
- Re-ranking retrieved chunks with a cross-encoder.
- Hybrid search (keyword + semantic).
- Minimum similarity threshold configuration in this PRD (can be added in a follow-up).

## Further Notes

The `RagPipeline` is the deepest module in this PRD. It must be designed so that the prompt construction logic is testable in isolation — extract a `PromptBuilder` if necessary so the prompt template logic can be unit-tested without involving the `ChatModel`. The agentic coding use case (Claude Code calling `ez-rag query --output json`) is the primary driver for the clean JSON output contract.
