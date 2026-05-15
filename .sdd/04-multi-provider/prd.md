## Problem Statement

Users have different LLM and embedding provider preferences depending on cost, privacy, and capability requirements. Some want to use OpenAI for chat but a local ONNX model for embeddings to avoid sending document content to a cloud API. Others want a fully local setup via Ollama. The tool must support all combinations without requiring recompilation or Spring profile switching.

## Solution

Implement runtime-selectable chat and embedding providers via `--provider` and `--embedding-provider` CLI flags. Include Spring AI starters for OpenAI, Anthropic, and Ollama (chat); OpenAI, Ollama, and ONNX (embeddings). Use conditional Spring bean configuration to create exactly one `ChatModel` bean and one `EmbeddingModel` bean at startup based on the resolved config values. Defaults are OpenAI for chat and OpenAI for embeddings.

## User Stories

1. As a user, I want to run `ez-rag query --provider openai --question "..."` to use GPT-4o-mini, so that I get a high-quality answer from OpenAI.
2. As a user, I want to run `ez-rag query --provider anthropic --question "..."` to use Claude, so that I can leverage Anthropic's models.
3. As a user, I want to run `ez-rag query --provider ollama --question "..."` to use a locally running Ollama model, so that no data leaves my machine.
4. As a user, I want to run `ez-rag ingest --embedding-provider onnx`, so that document embeddings are generated locally without any API call.
5. As a user, I want to run `ez-rag ingest --embedding-provider ollama`, so that I can use Ollama for embeddings alongside Ollama for chat in a fully local setup.
6. As a user, I want to run `ez-rag query --provider anthropic --embedding-provider openai`, so that I can mix providers (since Anthropic has no embedding API).
7. As a user, I want to set my default provider in `~/.ez-rag/config.yml` so I don't have to pass `--provider` every time.
8. As a user, I want to override the model name with `--model gpt-4o`, so that I can use a non-default model for a specific query.
9. As a user, I want to override the embedding model with `--embedding-model text-embedding-ada-002`, so that I can use a different embedding model without changing config.
10. As a user, I want a clear error message if I select `anthropic` as the embedding provider, since Anthropic has no embedding API, so that I understand why the command failed.
11. As a user, I want the ONNX embedding model (`all-MiniLM-L6-v2`) downloaded automatically on first use, so that I don't have to manually download model files.
12. As a user, I want API keys read from `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` environment variables, so that secrets are never in config files.
13. As a user, I want the Ollama base URL configurable via `OLLAMA_BASE_URL` or `--ollama-url`, so that I can point to a non-default Ollama instance.
14. As a developer, I want the provider selection logic isolated in a `ProviderFactory` module, so that adding a new provider only requires adding a new branch in one place.
15. As a developer, I want all command modules to depend only on `ChatModel` and `EmbeddingModel` interfaces, so that provider selection is entirely transparent to the rest of the application.

## Implementation Decisions

- **Dependencies**: Include all provider Spring AI starters in `build.gradle.kts`. Disable auto-configuration for all providers via `spring.autoconfigure.exclude` in `application.yml`. Provider beans are created manually in a `ProviderConfiguration` class based on runtime config.
- **ProviderFactory / ProviderConfiguration**: A `@Configuration` class with `@Bean` methods for `ChatModel` and `EmbeddingModel`. Each method reads `ConfigService` to determine the provider, then instantiates and configures the correct Spring AI client. No Spring profiles used.
- **ChatModel providers**:
  - `openai`: `OpenAiChatModel` with `OpenAiApi` configured from `OPENAI_API_KEY`. Default model: `gpt-4o-mini`.
  - `anthropic`: `AnthropicChatModel` configured from `ANTHROPIC_API_KEY`. Default model: `claude-sonnet-4-6`.
  - `ollama`: `OllamaChatModel` configured from `OLLAMA_BASE_URL` (default `http://localhost:11434`). Default model: `llama3.2`.
- **EmbeddingModel providers**:
  - `openai`: `OpenAiEmbeddingModel` from `OPENAI_API_KEY`. Default model: `text-embedding-3-small`.
  - `ollama`: `OllamaEmbeddingModel`. Default model: `nomic-embed-text`.
  - `onnx`: `TransformersEmbeddingModel` from `spring-ai-transformers`. Default model: `all-MiniLM-L6-v2` (downloaded from Hugging Face on first use to `~/.ez-rag/models/`).
- **Validation**: `ProviderConfiguration` throws `IllegalArgumentException` with a user-friendly message for unsupported combinations (e.g., Anthropic as embedding provider) before the application context finishes starting.
- **Default provider**: `openai` for both chat and embedding, so the tool works out of the box with just `OPENAI_API_KEY` set.

## Testing Decisions

- **What makes a good test**: Test that the correct `ChatModel` / `EmbeddingModel` subtype is instantiated for each provider string. Do not test that Spring AI's models actually call the API.
- **ProviderFactory**: Unit-test by constructing `ProviderConfiguration` with mock `ConfigService` objects for each provider value. Assert the returned bean is the correct subtype. Test that an invalid provider string throws with a descriptive message.
- **No live API tests**: Provider integration tests (actually calling OpenAI, Anthropic, Ollama) are out of scope for the automated test suite; they require live credentials and network.
- **ONNX smoke test**: A single integration test that instantiates `TransformersEmbeddingModel` with `all-MiniLM-L6-v2` and calls `embed("hello")` — validates the model loads correctly on CI without needing a GPU.

## Out of Scope

- Azure OpenAI provider (can be added as a follow-up; it reuses the OpenAI client with different endpoint config).
- Google Vertex AI / Gemini.
- Provider-specific advanced options (temperature, top-p, etc.) beyond model name selection.
- Automatic model pulling for Ollama (user must have the model already pulled).

## Further Notes

Disabling Spring AI's auto-configuration and doing manual bean construction is intentional. Auto-configuration requires provider-specific properties to be set at startup, which conflicts with runtime provider selection. Manual construction gives full control and avoids conditional property binding complexity. This is the most technically complex PRD in the set; it should be implemented after PRD 01 but can be parallelised with PRDs 02 and 03 since those depend only on the `ChatModel` / `EmbeddingModel` interfaces, not the implementations.
