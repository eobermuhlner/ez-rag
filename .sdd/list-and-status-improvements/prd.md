# PRD: `list` Command and `status` Command Improvements

## Problem Statement

Users and agentic coding tools have no dedicated way to see which documents are ingested or whether they are up to date. The `status` command conflates document enumeration with store health, making it noisy for quick health checks and inadequate for programmatic document inspection. There is also no way to inspect the active configuration from the CLI, so users cannot easily verify which provider, model, or chunking parameters are in effect.

## Solution

Introduce a new `list` subcommand that enumerates all ingested documents with staleness detection. Rework `status` to focus on store health, active configuration, and aggregate counts — without listing individual documents.

## User Stories

1. As a developer, I want to run `ez-rag list` and see all ingested documents so that I know what is in the knowledge base.
2. As a developer, I want `list` to show the chunk count per document so that I can understand how each file was split.
3. As a developer, I want `list` to flag documents whose on-disk modification time differs from the ingested mtime so that I know which files need re-ingestion.
4. As a developer, I want `list` to flag documents whose source file no longer exists on disk so that I can clean up stale entries.
5. As a developer, I want `list` to print paths relative to the current working directory in text mode so that the output is readable.
6. As an agentic coding tool, I want `list --output-format json` to return absolute paths so that I can reference files unambiguously.
7. As an agentic coding tool, I want `list --output-format json` to include a `stale` boolean per document so that I can programmatically trigger re-ingestion.
8. As a developer, I want `ez-rag status` to show the total number of documents and chunks so that I can assess the size of the knowledge base at a glance.
9. As a developer, I want `status` to show the store size on disk so that I understand the storage footprint.
10. As a developer, I want `status` to show the number of stale documents so that I know at a glance whether re-ingestion is needed.
11. As a developer, I want `status` to show the last ingest time so that I understand the freshness of the knowledge base.
12. As a developer, I want `status` to show the active provider and model so that I can confirm the LLM configuration without reading config files.
13. As a developer, I want `status` to show the active embedding provider and model so that I can confirm the vector representation in use.
14. As a developer, I want `status` to show the active rerank model so that I can confirm whether reranking is enabled.
15. As a developer, I want `status` to show `chunkSize`, `chunkOverlap`, and `topK` so that I can understand retrieval parameters.
16. As a developer, I want `status` to show the active `storeDir` so that I know which store is being used.
17. As a developer, I want `status` to show credentials only for providers that are active so that the output is not cluttered with irrelevant keys.
18. As a developer, I want `status` to no longer list individual documents so that it stays concise and focused on health information.
19. As a developer, I want `status --output-format json` to include all configuration and health fields in a structured object so that I can parse it programmatically.
20. As a developer, I want `list` to produce no output and exit non-zero when no store exists, with a helpful error message, so that I understand what went wrong.
21. As a developer, I want `list` output sorted alphabetically by path so that the list is predictable and easy to scan.

## Implementation Decisions

### Module: `VectorStoreRepository` (modify)

Extend `getMetadata()` and the data classes it returns to carry the new fields required by both `list` and `status`.

**`StoreDocumentInfo`** gains two new fields:
- `mtime: Long` — the modification time stored at ingest time (max mtime across all chunks for that source)
- `stale: Boolean` — true if the source file no longer exists on disk, or if its current `Files.getLastModifiedTime()` differs from the stored `mtime`

**`StoreMetadata`** gains:
- `documentCount: Int` — number of distinct source documents
- `storeSizeBytes: Long` — `storeFilePath.toFile().length()`
- `lastIngestTime: Long` — maximum `mtime` value across all chunks in the store (epoch millis); `0` if the store is empty
- `staleDocumentCount: Int` — count of documents where `stale == true`

The staleness check for a document is a pure function of the stored mtime and a `(path: String) -> Long?` filesystem probe (returns `null` if the file does not exist). Injecting this probe as a lambda keeps `VectorStoreRepository` testable without touching the real filesystem.

### Module: `ListCommand` (new)

A new `@Command(name = "list")` Picocli command following the same constructor injection pattern as `StatusCommand` (embedding model, storeDir override, output writer, start dir override for resolver walk).

**Text output** (one line per document):
```
<relative-path>  (<chunkCount> chunks)  [STALE]
```
The `[STALE]` suffix is omitted for non-stale documents. Paths are relativised to the current working directory when possible; absolute paths are used as fallback.

**JSON output** (array of objects):
```json
[
  { "path": "/abs/path/to/doc.txt", "chunks": 3, "stale": false },
  { "path": "/abs/path/to/old.txt", "chunks": 1, "stale": true }
]
```
Paths are always absolute in JSON output.

Exits with code `1` and an error message when no store exists.

### Module: `StatusCommand` (modify)

Remove the per-document loop. Replace the `documents` section with two new sections:

**Store section** (already present, extend):
- `storeFilePath` (already present)
- `documentCount`
- `chunkCount` (already present)
- `storeSizeBytes` (human-readable in text: e.g. `142 KB`)
- `staleDocumentCount`
- `lastIngestTime` (ISO-8601 in text; epoch millis in JSON; omitted/`null` if store is empty)

**Configuration section** (new):
- `storeDir`
- `provider` + `model`
- `embeddingProvider` + `embeddingModel`
- `rerankModel` (shown as `"disabled"` when empty)
- `chunkSize`, `chunkOverlap`
- `topK`

**Credentials section** (retain, but filter):
- Show credential line only for the providers that are actually in use:
  - `openai-api-key` if `provider == "openai"` or `embeddingProvider == "openai"`
  - `anthropic-api-key` if `provider == "anthropic"`
  - Omit both if neither provider requires API keys (e.g. `ollama` + `onnx`)

`StatusCommand` currently has optional Spring injection of `ConfigService` (`springConfigService`). The resolved config must be passed into the command for display. Extend the constructor to accept an `EzRagConfig?` parameter (nullable, resolved by the Spring wiring path and supplied directly in tests).

### Module: `EzRagCommand` (modify)

Register `ListCommand` as a subcommand alongside the existing commands.

### Testing

`VectorStoreRepository` staleness logic is injected via a filesystem probe lambda, so `VectorStoreRepositoryTest` can exercise stale/fresh/missing scenarios without touching real files.

`ListCommandTest` follows the `StatusCommandTest` pattern: construct the command with injected `PrintWriter`, `embeddingModel`, and `storeDirOverride`; ingest documents directly via `VectorStoreRepository`; assert on text and JSON output strings.

`StatusCommandTest` retains existing tests but updates assertions that currently expect document paths in `status` output — those assertions move to `ListCommandTest`. New tests cover the configuration section and the new aggregate fields.

## Testing Decisions

A good test for a command:
- Constructs the command directly (no Spring context) with injected collaborators
- Populates a real `SimpleVectorStore` via `VectorStoreRepository` in a `@TempDir`
- Calls `.call()` and asserts on the `PrintWriter` output string
- Tests observable output only, not internal method calls or field values

Modules with tests:
- `VectorStoreRepository` — staleness logic (new unit tests)
- `ListCommand` — text output, JSON output, stale flag, no-store error, alphabetical order
- `StatusCommand` — new aggregate fields, configuration section, filtered credentials, removed document list

Prior art: `StatusCommandTest` and `ShowCommandTest` demonstrate the constructor-injection + `TempDir` + string-assertion pattern used throughout.

## Out of Scope

- Filtering `list` output (e.g. `--stale-only` flag)
- Pagination of `list` output
- MCP tool equivalents for `list` (can be added in a follow-up)
- Showing config source (CLI flag vs env var vs config file) in `status`
- `ollamaUrl` in the `status` configuration section (shown only when provider is `ollama` — deferred)

## Further Notes

The `mtime` stored per chunk is the file modification time at ingest time (Unix epoch millis). The staleness check should compare this against `Files.getLastModifiedTime(path).toMillis()`. If a document has multiple chunks with differing mtimes (edge case from partial re-ingestion), use the maximum stored mtime for the staleness comparison.

The `lastIngestTime` field in `StoreMetadata` is derived as the maximum `mtime` across all chunks — this is a proxy for ingest freshness, not a wall-clock timestamp of when `ingest` was run.
