# Tasks: zero-config-default

## Task 01-passthrough-provider

Add a `PassthroughChatModel` class and wire it end-to-end: `ProviderConfiguration.chatModel()` returns it when `provider = "passthrough"`, and `RagPipeline.query()` short-circuits the generation step when it detects a `PassthroughChatModel`, returning the formatted context text directly as the answer. `sources` is still populated normally.

The short-circuit is inserted between the augmentation step (building `contextText`) and the generation step (`chatModel.call()`). The existing empty-store early return (`"No relevant documents found"`) is unaffected and stays before the short-circuit. `PassthroughChatModel.call()` throws `UnsupportedOperationException` since `RagPipeline` is its only consumer and the short-circuit prevents it from ever being called.

### Implementation steps

- [x] Write a failing test in `RagPipelineTest`: query with `PassthroughChatModel` returns the formatted context text as answer, populates sources, and never invokes `chatModel.call()`
- [x] Create `PassthroughChatModel` implementing Spring AI `ChatModel`; `call(Prompt)` throws `UnsupportedOperationException`
- [x] Add the `instanceof PassthroughChatModel` check in `RagPipeline.query()` after `contextText` is built, returning `RagResult(answer = contextText, sources = sources)` directly
- [x] Write a failing test in `ProviderConfigurationTest`: `chatModel()` returns a `PassthroughChatModel` when `provider = "passthrough"`
- [x] Add the `"passthrough"` branch in `ProviderConfiguration.chatModel()` returning `PassthroughChatModel()`
- [x] Update the unsupported-provider error message to include `"passthrough"` in the valid providers list
- [x] Verify the existing `ProviderConfigurationTest` for unknown provider now expects `"passthrough"` in the error message

### Acceptance criteria

- [x] `ProviderConfiguration.chatModel()` returns an instance of `PassthroughChatModel` when `provider = "passthrough"`
- [x] `RagPipeline.query()` with `PassthroughChatModel` and at least one ingested chunk returns an answer equal to the `contextText` string built by joining retrieved docs as `"--- Context: <source> ---\n<text>"` joined with `"\n\n"`
- [x] `RagPipeline.query()` with `PassthroughChatModel` populates `sources` with one entry per retrieved chunk (filePath, chunkIndex, similarityScore, excerpt)
- [x] `chatModel.call()` is never invoked when `PassthroughChatModel` is active (verified via a boolean flag in a lambda stub, as per existing test pattern)
- [x] The error message thrown for an unknown chat provider contains the string `"passthrough"` in the list of valid values

### Quality gates

- [x] `mvn test -Dtest=RagPipelineTest,ProviderConfigurationTest` passes with zero test failures
- [x] `mvn compile` produces zero new compiler warnings

---

## Task 02-zero-config-defaults

Change `EzRagConfig` default values so that a freshly installed tool works without any configuration. The new defaults are `provider="passthrough"`, `embeddingProvider="onnx"`, `model=""`, `embeddingModel="all-MiniLM-L6-v2"`. The four-level config precedence (CLI flags → env vars → config file → defaults) is unchanged.

Note: `model=""` is valid only when `provider="passthrough"`. For existing users who override `provider` in a config file or CLI flag but omit `model`, the behaviour is the same as today (an empty model name is sent to the API). No guard is added — this is acceptable because `model` is only consulted when the provider is not `passthrough`.

`ConfigServiceTest` must be updated to assert the new defaults and to confirm that CLI and env-var overrides still take precedence.

### Implementation steps

- [x] Update the failing `EzRagConfig has all twelve fields with correct defaults` test in `ConfigServiceTest` to expect the new default values
- [x] Change `EzRagConfig` data class default parameter values: `provider = "passthrough"`, `embeddingProvider = "onnx"`, `model = ""`, `embeddingModel = "all-MiniLM-L6-v2"`
- [x] Confirm `ConfigServiceTest.CLI flag beats config file value` and `env var beats config file value` still pass unchanged

### Acceptance criteria

- [x] `EzRagConfig()` with no arguments produces `provider = "passthrough"`
- [x] `EzRagConfig()` with no arguments produces `embeddingProvider = "onnx"`
- [x] `EzRagConfig()` with no arguments produces `embeddingModel = "all-MiniLM-L6-v2"`
- [x] `EzRagConfig()` with no arguments produces `model = ""`
- [x] `ConfigService.resolve(CliFlags(provider = "openai"))` returns `provider = "openai"` (CLI override still beats new default)
- [x] `ConfigService.resolve()` with env var `PROVIDER=openai` returns `provider = "openai"` (env-var override still beats new default)

### Quality gates

- [x] `mvn test -Dtest=ConfigServiceTest` passes with zero test failures
- [x] `mvn compile` produces zero new compiler warnings

---

## Task 03-onnx-first-run-message

Add first-run detection in `IngestCommand`: before the pre-flight `model.embed("test")` call, check whether the active embedding model is a `TransformersEmbeddingModel` and whether the ONNX model cache directory (`~/.ez-rag/models/`) is absent or empty. When both conditions are true, print exactly one line to `outputWriter` using `config.embeddingModel` for the model name:

```
Downloading embedding model <embeddingModel> (first run, this may take a moment)…
```

The ellipsis is the single Unicode character U+2026 (`…`), not three ASCII dots. The message is printed before any file I/O begins.

Tests must stub `TransformersEmbeddingModel` rather than constructing a real one (real instantiation triggers a network download). Use a temp directory for the model cache path.

### Implementation steps

- [x] Create `IngestCommandTest` (new test class)
- [x] Write a failing test: when a `TransformersEmbeddingModel` stub is the embedding model and the cache dir (temp dir, absent) is given, the captured output contains `"Downloading embedding model all-MiniLM-L6-v2 (first run, this may take a moment)…"` at an index before the `"files ingested"` summary line
- [x] Add the first-run detection block in `IngestCommand.call()` before `model.embed("test")`: check `model is TransformersEmbeddingModel` and cache dir absent-or-empty; if both true, print the download message
- [x] Write a failing test: when the embedding model is a plain fake (`EmbeddingModel` lambda), the download message is NOT in output
- [x] Write a failing test: when cache dir exists and contains at least one file, the download message is NOT in output
- [x] Make `IngestCommand` accept an injectable `modelCachePath: Path` parameter (defaulting to `~/.ez-rag/models/`) so tests can inject a temp dir without touching the real home directory

### Acceptance criteria

- [x] When embedding model is `TransformersEmbeddingModel` and cache dir is absent, output contains exactly `"Downloading embedding model all-MiniLM-L6-v2 (first run, this may take a moment)…"` (U+2026 ellipsis)
- [x] `indexOf(downloadMessage) < indexOf("files ingested")` in the captured output string (message precedes ingestion summary)
- [x] When embedding model is `TransformersEmbeddingModel` and cache dir exists with at least one file, the download message is NOT present in output
- [x] When embedding model is not `TransformersEmbeddingModel`, the download message is NOT present in output

### Quality gates

- [x] `mvn test -Dtest=IngestCommandTest` passes with zero test failures (new class created as part of this task)
- [x] `mvn compile` produces zero new compiler warnings
