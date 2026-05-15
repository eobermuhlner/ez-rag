# Tasks: 02-multi-provider

## Task 01-spring-ai-setup-and-openai-provider

Add all Spring AI provider starters to the build, disable their auto-configurations, expose provider CLI flags that are bridged into the Spring environment before context startup, and implement `ProviderConfiguration` that creates `OpenAiChatModel` and `OpenAiEmbeddingModel` when the resolved provider values are `"openai"`. This is the full vertical slice — from CLI flag through resolved config through Spring bean — for the default OpenAI provider combination, and establishes the `ProviderConfiguration` pattern all subsequent tasks extend.

The key design constraint: Spring beans are created during context startup, before picocli parses command arguments. Bridge this by pre-parsing `--provider`, `--embedding-provider`, `--model`, and `--embedding-model` from raw args in `EzRagApplication.main()` before calling `SpringApplication.run()`, and injecting the parsed values into the Spring environment (e.g., via system properties or `SpringApplicationBuilder.properties()`). `ProviderConfiguration` then reads them via `ConfigService.resolve()` at bean creation time.

`ProviderConfiguration` must accept `ConfigService` as a constructor parameter so unit tests can inject a mock without starting a Spring context.

### Implementation steps

- [x] Add Spring AI BOM and these starters to `build.gradle.kts`: `spring-ai-openai-spring-boot-starter`, `spring-ai-anthropic-spring-boot-starter`, `spring-ai-ollama-spring-boot-starter`, `spring-ai-transformers-spring-boot-starter`
- [x] Exclude all Spring AI auto-configuration classes in `application.yml` via `spring.autoconfigure.exclude` so that no Spring AI beans are created by auto-config
- [x] Add `--provider`, `--embedding-provider`, `--model`, `--embedding-model` as picocli `@Option` on `EzRagCommand` with `ScopeType.INHERIT`
- [x] In `EzRagApplication.main()`, pre-parse the four provider-related flags from raw args before `SpringApplication.run()` and propagate the resolved values into the Spring environment so `ConfigService` can read them at bean-creation time
- [x] Create `ProviderConfiguration @Configuration` class with `ConfigService` constructor parameter; implement `chatModel(): ChatModel` and `embeddingModel(): EmbeddingModel` `@Bean` methods; for `"openai"` return `OpenAiChatModel` / `OpenAiEmbeddingModel` configured from `System.getenv("OPENAI_API_KEY")`; default chat model `gpt-4o-mini`, default embedding model `text-embedding-3-small`
- [x] Unit test: construct `ProviderConfiguration` with a stub `ConfigService` returning provider=`"openai"`, assert `chatModel()` instanceof `OpenAiChatModel`
- [x] Unit test: construct `ProviderConfiguration` with a stub `ConfigService` returning embeddingProvider=`"openai"`, assert `embeddingModel()` instanceof `OpenAiEmbeddingModel`

### Acceptance criteria

- [x] `./gradlew build` succeeds with all four Spring AI starters on the classpath
- [x] `ez-rag --help` exits 0 with no Spring AI auto-configuration errors in stderr
- [x] Unit test passes: `ProviderConfiguration.chatModel()` returns an instance of `OpenAiChatModel` when provider=`"openai"`
- [x] Unit test passes: `ProviderConfiguration.embeddingModel()` returns an instance of `OpenAiEmbeddingModel` when embeddingProvider=`"openai"`
- [x] `ez-rag query --provider openai` starts without error when `OPENAI_API_KEY` is unset (key is only needed for live API calls, not bean construction)

### Quality gates

- [x] Kotlin compiler reports zero warnings (`-Werror` enforced)
- [x] `./gradlew test` passes

---

## Task 02-anthropic-chat-provider

Extend `ProviderConfiguration` to support `provider="anthropic"`, creating an `AnthropicChatModel` configured from the `ANTHROPIC_API_KEY` environment variable. Default model is `claude-sonnet-4-6`. Anthropic has no embedding API, so this task covers chat only; the embedding path is intentionally left for task 05 to validate with a clear error.

### Implementation steps

- [x] Add `"anthropic" -> AnthropicChatModel(...)` branch inside `ProviderConfiguration.chatModel()`; read `ANTHROPIC_API_KEY` from `System.getenv()`; default model `claude-sonnet-4-6`
- [x] Unit test: construct `ProviderConfiguration` with stub `ConfigService` returning provider=`"anthropic"`, assert `chatModel()` instanceof `AnthropicChatModel`
- [x] Unit test: assert the default model name in the constructed `AnthropicChatModel` is `claude-sonnet-4-6`

### Acceptance criteria

- [x] Unit test passes: `ProviderConfiguration.chatModel()` returns an instance of `AnthropicChatModel` when provider=`"anthropic"`
- [x] Unit test passes: the `AnthropicChatModel` default model is `claude-sonnet-4-6`
- [x] `ez-rag query --provider anthropic` starts without error when `ANTHROPIC_API_KEY` is unset (key needed only for live calls)

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes

---

## Task 03-ollama-providers

Extend `ProviderConfiguration` to support `provider="ollama"` (OllamaChatModel) and `embeddingProvider="ollama"` (OllamaEmbeddingModel). Add `ollamaUrl` to `EzRagConfig` and `CliFlags`, wire it from the new `--ollama-url` CLI flag and `OLLAMA_BASE_URL` environment variable (default: `http://localhost:11434`), and propagate it through `ConfigService`. Both Ollama model beans are configured with the resolved URL so they can target a non-default Ollama instance.

### Implementation steps

- [x] Add `ollamaUrl: String = "http://localhost:11434"` to `EzRagConfig` and `ollamaUrl: String? = null` to `CliFlags`
- [x] Wire `ollamaUrl` in `ConfigService.resolve()`: `cliFlags.ollamaUrl ?: envVars["OLLAMA_BASE_URL"] ?: file.ollamaUrl`
- [x] Add `--ollama-url` picocli `@Option` on `EzRagCommand` with `ScopeType.INHERIT`; include it in the pre-parser in `main()` so it reaches `ProviderConfiguration` at context startup
- [x] Add `"ollama" -> OllamaChatModel(...)` branch to `ProviderConfiguration.chatModel()` using the resolved `ollamaUrl`; default model `llama3.2`
- [x] Add `"ollama" -> OllamaEmbeddingModel(...)` branch to `ProviderConfiguration.embeddingModel()` using the resolved `ollamaUrl`; default model `nomic-embed-text`
- [x] Unit test: `ProviderConfiguration.chatModel()` returns `OllamaChatModel` when provider=`"ollama"`
- [x] Unit test: `ProviderConfiguration.embeddingModel()` returns `OllamaEmbeddingModel` when embeddingProvider=`"ollama"`
- [x] Unit test: both Ollama beans use the `ollamaUrl` from the resolved config (not a hard-coded string)

### Acceptance criteria

- [x] Unit test passes: `ProviderConfiguration.chatModel()` returns an instance of `OllamaChatModel` when provider=`"ollama"`
- [x] Unit test passes: `ProviderConfiguration.embeddingModel()` returns an instance of `OllamaEmbeddingModel` when embeddingProvider=`"ollama"`
- [x] Unit test passes: when `ConfigService` returns `ollamaUrl="http://my-ollama:11434"`, the constructed Ollama beans use that URL
- [x] `EzRagConfig` contains `ollamaUrl` with default `"http://localhost:11434"` (verifiable by inspecting the data class)

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes

---

## Task 04-onnx-embedding-provider

Extend `ProviderConfiguration` to support `embeddingProvider="onnx"`, creating a `TransformersEmbeddingModel` (from `spring-ai-transformers`) backed by the `all-MiniLM-L6-v2` model. The model is downloaded automatically from Hugging Face to `~/.ez-rag/models/` on first use. Add a CI-runnable integration smoke test that confirms the model loads and produces a 384-dimensional embedding vector for the input `"hello"`.

### Implementation steps

- [x] Add `"onnx" -> TransformersEmbeddingModel(...)` branch to `ProviderConfiguration.embeddingModel()`; configure the model cache directory to `~/.ez-rag/models/` and default model `all-MiniLM-L6-v2`
- [x] Unit test: construct `ProviderConfiguration` with stub `ConfigService` returning embeddingProvider=`"onnx"`, assert `embeddingModel()` instanceof `TransformersEmbeddingModel`
- [x] Integration smoke test: instantiate `TransformersEmbeddingModel` with `all-MiniLM-L6-v2`, call `embed(listOf("hello"))`, assert the returned float array has length 384 and is not all-zeros

### Acceptance criteria

- [x] Unit test passes: `ProviderConfiguration.embeddingModel()` returns an instance of `TransformersEmbeddingModel` when embeddingProvider=`"onnx"`
- [x] Smoke test passes: `embed(listOf("hello"))` returns a non-empty float array of length 384
- [x] Model files are stored under `~/.ez-rag/models/` after the smoke test runs (verifiable by listing the directory)

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes including the ONNX smoke test (no GPU required; `all-MiniLM-L6-v2` is CPU-only)

---

## Task 05-provider-validation

Add validation to `ProviderConfiguration` that throws `IllegalArgumentException` with descriptive, user-friendly messages before the application context finishes starting. Three cases must be covered: (1) Anthropic is requested as the embedding provider (no embedding API exists); (2) an unrecognized string is given as the chat provider; (3) an unrecognized string is given as the embedding provider. Each error message must name the invalid value and list the valid alternatives so the user understands exactly what to fix.

### Implementation steps

- [x] Add `"anthropic" -> throw IllegalArgumentException(...)` in `ProviderConfiguration.embeddingModel()` with a message that explains Anthropic has no embedding API and lists valid embedding providers: `openai`, `ollama`, `onnx`
- [x] Add `else -> throw IllegalArgumentException(...)` in `ProviderConfiguration.chatModel()` with a message that includes the invalid provider value and lists valid chat providers: `openai`, `anthropic`, `ollama`
- [x] Add `else -> throw IllegalArgumentException(...)` in `ProviderConfiguration.embeddingModel()` with a message that includes the invalid provider value and lists valid embedding providers: `openai`, `ollama`, `onnx`
- [x] Unit test: `ProviderConfiguration.embeddingModel()` throws `IllegalArgumentException` when embeddingProvider=`"anthropic"`; message contains `"anthropic"` and `"embedding"`
- [x] Unit test: `ProviderConfiguration.chatModel()` throws `IllegalArgumentException` when provider=`"bogus"`; message contains `"bogus"` and at least one valid provider name
- [x] Unit test: `ProviderConfiguration.embeddingModel()` throws `IllegalArgumentException` when embeddingProvider=`"bogus"`; message contains `"bogus"` and at least one valid embedding provider name

### Acceptance criteria

- [x] Unit test passes: embeddingProvider=`"anthropic"` throws `IllegalArgumentException` with a message explaining Anthropic has no embedding API
- [x] Unit test passes: provider=`"not-a-provider"` throws `IllegalArgumentException` with a message containing `"not-a-provider"` and listing valid providers
- [x] Unit test passes: embeddingProvider=`"not-a-provider"` throws `IllegalArgumentException` with a message containing `"not-a-provider"` and listing valid embedding providers

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes
