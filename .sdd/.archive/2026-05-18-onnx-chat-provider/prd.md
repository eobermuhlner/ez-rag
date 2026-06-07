# PRD: ONNX Chat Provider

## Problem Statement

ez-rag requires an external service (OpenAI API key, running Ollama server) or produces raw chunk output (`passthrough`) in order to answer questions. A first-time user who simply wants to query their documents gets either an authentication error or an unhelpful dump of context text — not the natural-language answer they expect. There is no zero-configuration, fully self-contained path to real question answering.

## Solution

Add a local ONNX chat provider that runs a small open-weight language model entirely on-device. When configured as `provider: onnx`, ez-rag downloads the model on first use and generates answers without any API key or external server. This becomes the default provider so that `ez-rag query "what is X?"` works out of the box for any new user.

Additionally, fix a misleading error message: setting `provider` to an unsupported value currently causes "No embedding model configured" (a Spring context startup failure side-effect) instead of a clear diagnostic pointing to the unsupported provider value.

## User Stories

1. As a new user, I want `ez-rag query` to produce a natural-language answer without configuring any API key or external server, so that I can evaluate the tool immediately after installation.
2. As a new user, I want the model to be downloaded automatically on first use, so that I do not need to run a separate setup step.
3. As a new user, I want a clear progress message when the model is downloading, so that I understand why the first query takes longer.
4. As a user on a resource-constrained machine, I want to configure a smaller model via `model: <name>` in the config file, so that I can trade answer quality for lower memory usage.
5. As a power user, I want to configure a better model via `model: <name>` in the config file, so that I can improve answer quality at the cost of a larger download.
6. As a user, I want the model files to be cached in `~/.ez-rag/models/` so that subsequent queries do not re-download the model.
7. As a user, I want answers to be factual and deterministic across repeated runs of the same query, so that the tool behaves predictably.
8. As a user, I want a warning when my context is longer than the model's input token limit, so that I understand why the answer may be incomplete.
9. As a user, I want to switch back to `provider: passthrough` in my config file, so that I can inspect raw chunks when I need to debug retrieval quality.
10. As a user, I want to use `provider: openai` or `provider: ollama` alongside `provider: onnx` without interference, so that I can choose the best provider for each use case.
11. As a user, I want a clear error message when I set `provider` to an unsupported value, so that I can immediately understand what to fix in my config.
12. As a user, I want the custom `--system-prompt` CLI option to work with the ONNX chat provider, so that I can customise the assistant's behaviour without changing the provider.
13. As a developer, I want the ONNX model download logic to be shared with the existing reranker download code, so that there is one consistent caching mechanism for all ONNX assets.
14. As a developer, I want `OnnxChatModel` to implement Spring AI's `ChatModel` interface, so that it integrates into the existing `RagPipeline` without modifications.
15. As a developer, I want the ONNX chat model to be testable in isolation from the rest of the RAG pipeline, so that I can verify its input formatting and output parsing without a full end-to-end integration test.

## Implementation Decisions

### Default provider change
- `EzRagConfig.provider` default changes from `"passthrough"` to `"onnx"`.
- `EzRagConfig.model` default changes from `""` to `"Xenova/Qwen2-0.5B-Instruct"` — the Xenova organisation publishes pre-exported, tested ONNX versions of popular models on HuggingFace.

### New module: `OnnxModelDownloader`
Extracted from the existing `OnnxCrossEncoderReranker` download logic into a standalone class. Responsibility: given a HuggingFace model name and a local cache root, download named files to `<cacheRoot>/<modelName>/<file>` with atomic rename (temp file → destination). `OnnxCrossEncoderReranker` is refactored to delegate to this class. `OnnxChatModel` uses the same class.

Interface (single responsibility — no ONNX loading, no tokenisation):
- `ensureFile(remotePath: String, localFileName: String): File`
- `ensureCachedOnnxModel(): File` (tries `onnx/decoder_model_merged.onnx`, falls back to `onnx/model.onnx`)

### New module: `OnnxChatModel`
Implements Spring AI's `ChatModel` interface. Internal steps:

1. **Format input**: flatten the `Prompt`'s `SystemMessage` and `UserMessage` into the Qwen2 chat template format (system/user/assistant tags). Falls back gracefully when no system message is present.
2. **Tokenise**: use `HuggingFaceTokenizer` (already a dependency via `ai.djl.huggingface:tokenizers`) loading `tokenizer.json` from the model cache.
3. **Context truncation guard**: if the encoded input exceeds the model's maximum input length (32 768 tokens for Qwen2-0.5B), truncate and print a warning to stderr.
4. **Greedy decoding loop**: run the ONNX decoder session, taking the argmax of the last token's logits at each step. Accumulate KV cache between steps (past_key_values). Stop at EOS token or `maxNewTokens` (256, hardcoded for V1).
5. **Decode output**: convert token IDs back to text using the tokenizer, stripping any special tokens or the echoed input prefix.
6. **Return**: wrap the generated string in a Spring AI `ChatResponse`.

Lazy initialisation of tokenizer and ONNX session (same pattern as `OnnxCrossEncoderReranker`). Download triggered on first `call()` invocation, with a progress message to stderr before the download begins.

### `ProviderConfiguration` change
Add `"onnx"` case to the `chatModel()` bean factory. Error message for the `else` branch updated to include `onnx` in the list of valid providers.

### `RagPipeline` — no change required
`RagPipeline` already short-circuits for `PassthroughChatModel` before invoking `ChatModel.call()`. No modification needed to accommodate `OnnxChatModel`.

### ONNX model files
For `Xenova/Qwen2-0.5B-Instruct`, the required files downloaded from HuggingFace are:
- `onnx/decoder_model_merged.onnx` — merged decoder (handles both prefill and KV-cache decode steps via a `use_cache_branch` input flag)
- `tokenizer.json`
- `tokenizer_config.json`
- `config.json`

### Generation parameters (V1 — hardcoded)
- Decoding strategy: greedy (argmax)
- Max new tokens: 256
- Temperature: 0.0 (no sampling)
- No top-k / top-p filtering needed with greedy decoding

## Testing Decisions

Good tests verify observable behaviour through the public interface, not internal implementation steps. They do not assert on which private methods were called, how many ONNX sessions were created, or the exact internal tensor shapes.

### `OnnxModelDownloaderTest` (unit test)
Tests the download and caching behaviour using a local HTTP server or a temp-directory fixture. Verifies:
- File is written to the expected cache path on first call.
- Second call returns the cached file without re-downloading (assert no HTTP request made).
- Atomic rename: a partially downloaded file does not leave a corrupt file in the cache.

Prior art: `OnnxCrossEncoderRerankerTest` for the download pattern.

### `OnnxChatModelTest` (unit test)
Tests `OnnxChatModel` with a fake/stub ONNX session that returns a fixed token sequence. Verifies:
- Given a `Prompt` with system and user messages, the call returns a `ChatResponse` with non-empty text.
- Given only a user message (no system message), the call still succeeds.
- Given a `--system-prompt` override, it is reflected in the formatted input.
- The EOS token stops generation before `maxNewTokens` is reached.
- A context length warning is printed to stderr when input tokens exceed the limit.

Prior art: `RagPipelineTest`, `OnnxCrossEncoderRerankerTest`.

### `OnnxChatIntegrationTest` (integration test — downloads real model)
End-to-end test that downloads `Xenova/Qwen2-0.5B-Instruct` and runs one query with a short context. Verifies that the returned answer is a non-empty string. Tagged with the same integration-test tag used by `OnnxEmbeddingIntegrationTest` so it is excluded from the regular test run.

Prior art: `OnnxEmbeddingIntegrationTest`.

### `ProviderConfigurationTest` (unit test — existing file, extended)
Add a test case verifying that `provider: onnx` produces an instance of `OnnxChatModel`. Add a test case verifying that an unsupported provider value throws `IllegalArgumentException` with a message listing all valid providers including `onnx`.

### `QueryCommandTest` (unit test — existing file, extended)
Verify that when no provider is configured, the default `OnnxChatModel` is used (not `PassthroughChatModel`).

## Out of Scope

- Configurable generation parameters (temperature, top-k, max tokens) — hardcoded for V1.
- Streaming output (token-by-token printing as generation proceeds).
- Support for encoder-decoder models (T5, BART) as chat providers.
- GPU / ONNX execution provider selection (CUDA, CoreML) — CPU only for V1.
- Quantised model variants (INT4, INT8) — full float32 ONNX only for V1.
- MCP tool changes — the `McpQueryTool` already delegates to `RagPipeline` and will benefit automatically.
- Offline mode (pre-bundling the model in the distribution artifact).

## Further Notes

- The Xenova HuggingFace organisation (`huggingface.co/Xenova`) publishes ONNX-exported versions of popular open models. The `decoder_model_merged.onnx` pattern is the same one used by transformers.js and is well-tested.
- The existing `OnnxCrossEncoderReranker` is good prior art for the file download and ONNX session initialisation patterns. Extracting `OnnxModelDownloader` removes duplication and ensures consistent caching behaviour.
- The KV-cache (`past_key_values`) inputs and outputs are essential for acceptable generation speed. Without KV cache, each decode step re-processes the full growing sequence, making 256-token generation impractically slow.
- `flan-t5-small` and `flan-t5-base` were considered but rejected because their 512-token input limit is insufficient for real RAG workloads where context chunks alone may exceed this.
