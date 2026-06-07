# Tasks: onnx-chat-provider

## Task 01-onnx-model-downloader

Extract the ONNX model download-and-cache logic from `OnnxCrossEncoderReranker` into a standalone `OnnxModelDownloader` class, then refactor `OnnxCrossEncoderReranker` to delegate to it. End-to-end behavior: any ONNX asset (reranker or chat model) is downloaded on first use, cached atomically, and served from cache on subsequent uses.

### Implementation steps

- [x] Write `OnnxModelDownloaderTest` covering: first download writes file to the expected cache path, second call skips the HTTP request and returns the cached file, a failed/interrupted download does not leave a corrupt file at the destination (atomic rename via temp file)
- [x] Create `OnnxModelDownloader` with constructor params `(modelName: String, cacheRoot: File)` and two public methods: `ensureFile(remotePath: String, localFileName: String): File` and a generic `ensureCachedOnnxModel(primaryPath: String, fallbackPath: String): File` that tries the primary path first
- [x] Refactor `OnnxCrossEncoderReranker` to instantiate `OnnxModelDownloader` and delegate all download logic to it; remove duplicated download code from the reranker

### Acceptance criteria

- [x] `OnnxModelDownloaderTest`: first call to `ensureFile` writes the file to `<cacheRoot>/<modelName>/<localFileName>` and returns that path
- [x] `OnnxModelDownloaderTest`: second call to `ensureFile` for the same file makes no HTTP request (verified by local mock server or call counter)
- [x] `OnnxModelDownloaderTest`: when the download fails mid-stream, no file exists at the destination path (the partial download does not remain as a corrupt cache entry)
- [x] All pre-existing `OnnxCrossEncoderReranker` tests pass unchanged after the refactor
- [x] `OnnxCrossEncoderReranker` contains no inline HTTP download logic — all file acquisition is delegated to `OnnxModelDownloader`

### Quality gates

- [x] `./gradlew build` completes with no new compiler warnings or errors
- [x] No `TODO` or `FIXME` comments in newly created source files

---

## Task 02-onnx-chat-model

Implement `OnnxChatModel`, which takes a Spring AI `Prompt`, formats it into the Qwen2 chat template, tokenizes using `HuggingFaceTokenizer` (already on the classpath via `ai.djl.huggingface:tokenizers`), runs a greedy KV-cache decode loop over an ONNX session, and returns a Spring AI `ChatResponse`. Uses `OnnxModelDownloader` (from task 01) to fetch `onnx/decoder_model_merged.onnx` (falling back to `onnx/model.onnx`) plus `tokenizer.json`, `tokenizer_config.json`, and `config.json`. Initialisation is lazy — nothing is downloaded or loaded until the first `call()`.

### Implementation steps

- [x] Write `OnnxChatModelTest` using a test-double ONNX session that returns a fixed token sequence (e.g. three content tokens then EOS) — no network access, no real model
- [x] Implement chat-template formatting: concatenate `SystemMessage` and `UserMessage` from the `Prompt` into Qwen2 system/user/assistant tags; handle missing `SystemMessage` gracefully
- [x] Add context-length guard: if `HuggingFaceTokenizer` encodes more than 32 768 tokens, truncate to the limit and print a warning to stderr before continuing
- [x] Implement the ONNX greedy decode loop: feed `input_ids`, `attention_mask`, and `position_ids` tensors; take argmax of the last-token logit slice; accumulate `past_key_values` between steps; stop at EOS token ID or after 256 new tokens
- [x] Decode the generated token IDs back to text using the tokenizer, stripping special tokens and the echoed prompt prefix
- [x] Download model files via `OnnxModelDownloader.ensureCachedOnnxModel(primary = "onnx/decoder_model_merged.onnx", fallback = "onnx/model.onnx")` plus individual `ensureFile` calls for the tokenizer and config files; print a progress message to stderr before the download begins

### Acceptance criteria

- [x] Given a `Prompt` with both `SystemMessage` and `UserMessage`, `call()` returns a `ChatResponse` with non-empty text content
- [x] Given a `Prompt` with only a `UserMessage` (no `SystemMessage`), `call()` succeeds and returns non-empty text content
- [x] The stub ONNX session that returns EOS as the first generated token causes `call()` to return immediately (generation length = 0 new tokens, no infinite loop)
- [x] When input tokens exceed 32 768, a message is written to stderr and generation proceeds (rather than throwing)
- [x] The ONNX session is invoked with tensors named `input_ids`, `attention_mask`, and `position_ids`; the logits output has shape `[1, seqLen, vocabSize]`
- [x] `OnnxChatModel` implements `org.springframework.ai.chat.model.ChatModel`
- [x] `OnnxChatModelTest` is a pure unit test: no network access, no file download, no real ONNX model required

### Quality gates

- [x] `./gradlew build` completes with no new compiler warnings
- [x] `OnnxChatModelTest` passes within the standard `./gradlew test` run (no integration-test tag required)

---

## Task 03-onnx-provider-wiring

Wire `OnnxChatModel` into `ProviderConfiguration` as the `"onnx"` case. Change `EzRagConfig` defaults so that new users get `provider: onnx` and `model: Xenova/Qwen2-0.5B-Instruct` without any configuration. Fix the unsupported-provider error message to list all valid provider names. Extend the existing provider and command tests. Add a tagged integration test that downloads the real model and runs a full query. Update README to reflect the new default behaviour.

### Implementation steps

- [x] Add `"onnx"` branch to `ProviderConfiguration.chatModel()`, constructing `OnnxChatModel` with the resolved model name and cache root; ensure the instance is created lazily so that the Spring context starts without triggering a download
- [x] Change `EzRagConfig.provider` default to `"onnx"` and `EzRagConfig.model` default to `"Xenova/Qwen2-0.5B-Instruct"`
- [x] Update the `else` (unsupported provider) error message to include `"onnx"` alongside the other valid provider names
- [x] Add `ProviderConfigurationTest` case: `provider: onnx` → produced bean is an instance of `OnnxChatModel`
- [x] Add `ProviderConfigurationTest` case: unsupported provider value → `IllegalArgumentException` whose message contains each of `openai`, `anthropic`, `ollama`, `onnx`, and `passthrough`
- [x] Extend `QueryCommandTest`: when no provider is configured, the `ChatModel` injected into `RagPipeline` is an instance of `OnnxChatModel` (not `PassthroughChatModel`)
- [x] Add `OnnxChatIntegrationTest` with the same integration-test tag as `OnnxEmbeddingIntegrationTest`; it downloads `Xenova/Qwen2-0.5B-Instruct`, runs `OnnxChatModel.call()` with a short context prompt, and asserts the response text is non-empty
- [x] Update `README.md`: document the new default provider, the automatic model download behaviour, the `~/.ez-rag/models/` cache location, and how to switch to a different provider via the config file

### Acceptance criteria

- [x] `ProviderConfigurationTest`: `provider: onnx` produces an `OnnxChatModel` instance
- [x] `ProviderConfigurationTest`: any unsupported provider value throws `IllegalArgumentException` with a message listing `openai`, `anthropic`, `ollama`, `onnx`, and `passthrough`
- [x] `QueryCommandTest`: with no provider in config, the injected `ChatModel` is `OnnxChatModel`
- [x] `OnnxChatIntegrationTest` (integration tag): returns a non-empty answer string for a one-sentence context query
- [x] `./gradlew test` (standard run, integration tests excluded) passes without any network access or model file download
- [x] The `OnnxChatModel` bean is constructed lazily — starting the Spring context with `provider: onnx` does not trigger a file download

### Quality gates

- [x] `./gradlew build` completes with no new compiler warnings
- [x] `OnnxChatIntegrationTest` is annotated/tagged identically to `OnnxEmbeddingIntegrationTest` so that the same Gradle filter excludes both from the standard test run
