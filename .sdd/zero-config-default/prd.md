# Zero-Config Default — PRD

## Problem Statement

A new user who has just installed ez-rag cannot use the tool immediately. The default configuration
requires an OpenAI API key for both embedding and generation. Without one, every command fails
at startup. Users who want to evaluate, develop against, or demo ez-rag must first obtain an API
key and configure it — a friction point that discourages adoption and complicates local development.

## Solution

Change the out-of-the-box defaults so that ez-rag works with zero configuration:

- **Embedding** defaults to the ONNX provider (`all-MiniLM-L6-v2`), which downloads and caches
  the model locally on first use. No API key required.
- **Generation** defaults to a new `passthrough` provider, which returns the retrieved context
  chunks directly as the answer instead of calling an LLM. The `query` command degrades
  gracefully to search-like output rather than failing.

Users who want LLM-generated answers configure a real provider (`openai`, `anthropic`, `ollama`)
explicitly via CLI flag, environment variable, or config file — just as they do today.

## User Stories

1. As a new user, I want to run `ez-rag ingest <file>` immediately after installation without configuring any API key, so that I can start using the tool right away.
2. As a new user, I want to run `ez-rag search <query>` without any configuration, so that I can retrieve relevant chunks from ingested documents.
3. As a new user, I want to run `ez-rag query <question>` without any configuration, so that the tool returns useful output rather than an error, even without an LLM.
4. As a new user, I want the `query` command with default settings to return the retrieved context chunks, so that I understand what information the RAG pipeline found before I add an LLM.
5. As a new user, I want to see a clear one-line message the first time ONNX downloads the embedding model, so that I understand why there is a short delay during first use.
6. As a user, I want the first-run download message to appear before any file processing starts, so that I am not confused by a silent pause.
7. As a user, I want ONNX embedding to be used by default even in the MCP server mode, so that the MCP tools work without API key configuration.
8. As a developer, I want to run the full test suite without a live API key, so that CI passes without secrets.
9. As a developer, I want to override the provider with `--provider openai` on the CLI, so that I can use a real LLM without changing the default config.
10. As a developer, I want to override the embedding provider with `--embedding-provider openai`, so that I can switch back to OpenAI embeddings without changing defaults.
11. As a user, I want `ez-rag query` with the passthrough provider to include source attribution alongside the returned chunks, so that I know which documents were retrieved.
12. As a user, I want the passthrough output format to be consistent with the `search` command output, so that the two commands feel coherent when no LLM is configured.
13. As a user, I want to set `provider: openai` in `~/.ez-rag/config.yml` to permanently switch to OpenAI generation, so that I don't need to pass a flag every time.
14. As a user, I want `PROVIDER=openai` (or the existing env var name) to override the default, so that I can configure the provider in my shell environment.
15. As a user, I want the `status` command (or equivalent) to show `provider: passthrough` in its output, so that I can confirm which provider is active.
16. As a user, I want the ONNX model to be cached in `~/.ez-rag/models/` between runs, so that the download only happens once.
17. As a user, I want the passthrough provider to still populate the `sources` list in the result, so that I can see similarity scores and chunk indices.
18. As a user, I want switching from passthrough to a real provider to require no re-ingestion of documents, so that my vector store remains valid across provider changes.
19. As a user, I want `ez-rag ingest` to complete successfully on first run even if the ONNX model download takes time, so that the ingestion is not interrupted.
20. As a developer integrating the MCP server, I want the default ONNX + passthrough configuration to work without any environment setup on the host machine, so that MCP clients can use ez-rag out of the box.

## Implementation Decisions

### PassthroughChatModel (new module)

A new class `PassthroughChatModel` that implements Spring AI's `ChatModel` interface. It serves
as a sentinel value: its presence signals to `RagPipeline` that the generation step should be
skipped. It does not make any network calls or load any model.

`RagPipeline` detects `PassthroughChatModel` via an `instanceof` check before the generation
step. When detected, it formats the retrieved context chunks as the answer (the same
`contextText` already built for the LLM prompt) and returns it directly, skipping the `chatModel.call()` path. The `sources` list is still populated normally.

### RagPipeline modification

The passthrough short-circuit is inserted between the augmentation step (building `contextText`)
and the generation step (calling `chatModel`). The returned `RagResult.answer` contains the
formatted context text, identical to what an LLM would have received as context. `RagResult.sources`
is fully populated as in the normal path.

The empty-store early return (`"No relevant documents found"`) is unaffected and remains before
the passthrough check.

### ProviderConfiguration modification

The `chatModel()` factory adds a `"passthrough"` branch that returns `PassthroughChatModel`.
The error message for unsupported providers is updated to include `passthrough` in the list of
valid values.

### EzRagConfig default changes

The default values change from:

```
provider = "openai"
embeddingProvider = "openai"
model = "gpt-4o-mini"
embeddingModel = "text-embedding-3-small"
```

to:

```
provider = "passthrough"
embeddingProvider = "onnx"
model = ""
embeddingModel = "all-MiniLM-L6-v2"
```

The `model` field is ignored when `provider = "passthrough"`.

### IngestCommand first-run message

Before the pre-flight `model.embed("test")` call, `IngestCommand` checks whether the active
embedding model is a `TransformersEmbeddingModel` and whether the ONNX model cache directory
(`~/.ez-rag/models/`) is empty or absent. If both are true, it prints to `outputWriter`:

```
Downloading embedding model all-MiniLM-L6-v2 (first run, this may take a moment)…
```

The message is printed exactly once, before any file I/O begins.

### Config precedence unchanged

The existing four-level precedence (CLI flags → environment variables → config file → defaults)
is unchanged. The new defaults are simply lower-priority than any explicit override.

## Testing Decisions

**What makes a good test:** test only the observable output of a module given controlled inputs —
never internal state or implementation details. Use fakes for collaborators (fake `EmbeddingModel`,
stub `ChatModel`) rather than mocks that assert internal calls. Follow the pattern established
in `RagPipelineTest` (fake embedding model + lambda ChatModel stub) and `ProviderConfigurationTest`
(in-process `configServiceWith()` helper).

**Modules with tests:**

- **`RagPipelineTest`** — add: `query with PassthroughChatModel returns contextText as answer and
  populates sources`; also verify that `chatModel.call()` is never invoked when passthrough is active.
  Prior art: the existing `empty store returns no-documents result without invoking ChatModel` test.

- **`ProviderConfigurationTest`** — add: `chatModel returns PassthroughChatModel when provider is
  passthrough`; update the unsupported-provider error message test to confirm `passthrough` is listed
  in the valid providers. Prior art: the existing `chatModel returns OpenAiChatModel when provider is
  openai` pattern.

- **`ConfigServiceTest`** — add: verify that `EzRagConfig()` with no arguments produces
  `provider="passthrough"` and `embeddingProvider="onnx"`. This guards against accidental default
  regression. Prior art: existing default-value assertions in `ConfigServiceTest`.

- **`IngestCommandTest`** (or `IngestIntegrationTest`) — add: when embedding model is
  `TransformersEmbeddingModel` and cache dir is absent, the output contains the download message
  before any ingestion output. The test should use a temp dir for the model cache.

## Out of Scope

- **Reranking**: A future reranking provider (cross-encoder model) is independent of this feature
  and is not configured here.
- **Passthrough MCP tool**: No new MCP tool is added. Existing MCP search and query tools inherit
  the new defaults automatically.
- **Automatic provider fallback**: No runtime detection of missing API keys with automatic fallback
  to passthrough. The provider is always resolved from the config chain.
- **Progress bar for ONNX download**: Only a single printed line; no byte-level progress indicator.
- **Interactive shell changes**: The REPL slash commands are not modified.

## Further Notes

- The ONNX `TransformersEmbeddingModel` downloads `all-MiniLM-L6-v2` from HuggingFace on first use
  and caches it in `~/.ez-rag/models/`. Subsequent runs use the cache with no network access.
- Users on air-gapped machines can pre-populate the cache directory manually.
- The `passthrough` provider name is intentional: it communicates that data flows through without
  transformation, rather than implying something is broken or absent (`none`, `dummy`).
- Existing users with `~/.ez-rag/config.yml` explicitly setting `provider: openai` are unaffected;
  the config file overrides the new defaults.
