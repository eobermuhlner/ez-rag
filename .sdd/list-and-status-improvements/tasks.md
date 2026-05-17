# Tasks: `list` Command and `status` Command Improvements

## Task 01-repository-staleness-and-aggregate-metadata

Extend `VectorStoreRepository.getMetadata()` to return staleness flags per document and aggregate stats for the store. This data-access foundation is consumed by both the new `list` command (Task 02) and the improved `status` command (Task 03).

`StoreDocumentInfo` gains `mtime: Long` (max mtime across all chunks for that source) and `stale: Boolean` (true when the source file is missing or its current mtime differs from the stored mtime). Staleness is determined via a `filesystemProbe: (String) -> Long?` parameter on `getMetadata()` (default: real `Files.getLastModifiedTime`; returns `null` if the file does not exist). Injecting the probe keeps tests free of real filesystem side-effects.

`StoreMetadata` gains: `documentCount: Int`, `storeSizeBytes: Long` (byte length of the on-disk store file), `lastIngestTime: Long` (max mtime across all chunks; 0 when store is empty), `staleDocumentCount: Int`.

### Implementation steps

- [x] Add `mtime: Long` and `stale: Boolean` fields to `StoreDocumentInfo`
- [x] Add `documentCount`, `storeSizeBytes`, `lastIngestTime`, `staleDocumentCount` fields to `StoreMetadata`
- [x] Add `filesystemProbe: (String) -> Long? = { path -> try { Files.getLastModifiedTime(Paths.get(path)).toMillis() } catch (_: Exception) { null } }` parameter to `getMetadata()`
- [x] Derive per-document mtime as the maximum mtime across all chunks for that source; use it for staleness comparison and expose it in `StoreDocumentInfo.mtime`
- [x] Compute `storeSizeBytes` as `storeFilePath.toFile().length()` (returns 0 when file does not exist)
- [x] Write unit tests in `VectorStoreRepositoryTest` covering: fresh doc (probe returns stored mtime → `stale = false`), stale doc (probe returns different mtime → `stale = true`), missing doc (probe returns `null` → `stale = true`), multiple chunks for one source uses max mtime, `lastIngestTime` is 0 for empty store, `staleDocumentCount` matches stale document count

### Acceptance criteria

- [x] `StoreDocumentInfo.stale` is `false` when the filesystem probe returns the same value as the stored mtime
- [x] `StoreDocumentInfo.stale` is `true` when the filesystem probe returns a different value than the stored mtime
- [x] `StoreDocumentInfo.stale` is `true` when the filesystem probe returns `null` (file missing on disk)
- [x] When one source has multiple chunks with differing stored mtimes, `StoreDocumentInfo.mtime` equals the maximum of those stored mtimes
- [x] `StoreMetadata.documentCount` equals the number of distinct source paths in the store
- [x] `StoreMetadata.storeSizeBytes` equals `storeFilePath.toFile().length()` after `save()` has been called (test must call `repo.save()` before asserting)
- [x] `StoreMetadata.lastIngestTime` is 0 when the store contains no chunks; otherwise equals the maximum mtime across all chunks
- [x] `StoreMetadata.staleDocumentCount` equals the count of documents where `stale == true`

### Quality gates

- [x] No compiler warnings introduced
- [x] All pre-existing `VectorStoreRepositoryTest` tests still pass without modification

---

## Task 02-list-command

Introduce the `list` subcommand end-to-end: repository data access, CLI dispatch via `EzRagCommand`, and output formatting. `list` enumerates all ingested documents sorted alphabetically, shows the chunk count, flags stale documents, and supports text and JSON output.

Text format (one line per document): `<relative-path>  (<N> chunks)  [STALE]` — `[STALE]` is omitted for fresh documents; paths are relative to the current working directory when possible, absolute otherwise.

JSON format: a JSON array of objects `{"path": "/absolute/path", "chunks": 3, "stale": false}` — paths are always absolute.

Exits with code `1` and a user-friendly error message (including the expected store path and a hint to run `ez-rag ingest`) when no store exists.

### Implementation steps

- [x] Create `ListCommand` following the `StatusCommand` constructor-injection pattern: `embeddingModel`, `storeDirOverride`, `outputWriter`, `errorWriter`, `startDirOverride`; add `@Option` for `--output-format` (default `text`)
- [x] Call `repository.getMetadata()` with the real default filesystem probe; exit code `1` with error message when store does not exist
- [x] Implement text output: relativise each path to CWD, append `  (<N> chunks)`, append `  [STALE]` when `stale == true`; sort alphabetically by path
- [x] Implement JSON output: absolute paths; emit `{"path": ..., "chunks": ..., "stale": ...}` per document; sort alphabetically by path
- [x] Register `ListCommand::class` in the `subcommands` array of `EzRagCommand`
- [x] Write `ListCommandTest` covering: text output format including chunk count, `[STALE]` appears only for stale documents, JSON output keys and types, no-store error message and exit code, alphabetical ordering

### Acceptance criteria

- [x] `list` text output contains one line per document in the format `<path>  (<N> chunks)` (N is a positive integer)
- [x] `list` text output appends `  [STALE]` on a document's line when that document's file mtime differs from the stored mtime or the file is missing; the suffix is absent for fresh documents
- [x] `list` text output is sorted alphabetically by document path
- [x] `list --output-format json` produces a valid JSON array; each element has a `path` string (absolute), a `chunks` integer ≥ 1, and a `stale` boolean
- [x] `list` when no store exists writes a message containing the expected store file path and the string `ez-rag ingest`, and exits with code `1`
- [x] `ez-rag --help` output lists `list` as a subcommand

### Quality gates

- [x] No compiler warnings introduced
- [x] All pre-existing tests still pass

---

## Task 03-status-command-improvements

Rework `StatusCommand` end-to-end: replace the per-document list with aggregate store health fields, add a configuration section derived from `EzRagConfig`, and filter the credentials section to only show keys relevant to the active providers.

Accept an `EzRagConfig?` constructor parameter (for direct test injection). Resolution order: constructor `config` takes precedence; fall back to `springConfigService?.resolve()`; then `EzRagConfig()` defaults.

**Store section** changes: remove per-document list; add `documentCount`, `storeSizeBytes` (human-readable in text, e.g. `142 KB`), `staleDocumentCount`, `lastIngestTime` (ISO-8601 in text; epoch millis in JSON; omitted/`null` when 0).

**Configuration section** (new): `storeDir`, `provider`, `model`, `embeddingProvider`, `embeddingModel`, `rerankModel` (shown as `"disabled"` when blank), `chunkSize`, `chunkOverlap`, `topK`.

**Credentials filter**: show `openai-api-key` only when `provider == "openai"` or `embeddingProvider == "openai"`; show `anthropic-api-key` only when `provider == "anthropic"`; omit both when neither provider requires an API key (e.g. `ollama` + `onnx`).

### Implementation steps

- [x] Add `EzRagConfig?` constructor parameter to `StatusCommand`; resolve it with the fallback chain above
- [x] Remove the per-document loop from both text and JSON output paths
- [x] Add `documentCount`, `storeSizeBytes`, `staleDocumentCount`, `lastIngestTime` to text and JSON output
- [x] Format `storeSizeBytes` as human-readable (KB/MB) in text output; use raw Long in JSON
- [x] Format `lastIngestTime` as ISO-8601 in text output; as epoch millis Long in JSON; omit/`null` when 0
- [x] Add a `Configuration:` section to text output with all config fields; add a `configuration` nested object to JSON output
- [x] Apply credential filtering logic based on the resolved `EzRagConfig`
- [x] Update `StatusCommandTest`: remove all assertions that check for individual document paths; add assertions for aggregate fields, configuration section, and credential filtering; update the eight credential tests to supply an `EzRagConfig` and verify the filtering behaviour

### Acceptance criteria

- [x] `status` text output does not contain individual document file paths
- [x] `status` text output contains a `Documents:` or equivalent label with the document count as an integer
- [x] `status` text output contains `storeSizeBytes` rendered as a human-readable size string (e.g. `142 KB` or `1 MB`)
- [x] `status` text output contains `staleDocumentCount` as an integer
- [x] `status` text output contains `lastIngestTime` as an ISO-8601 datetime string when the store is non-empty; the field is absent or labelled `none` when the store is empty
- [x] `status` text output contains a Configuration section with `provider`, `model`, `embeddingProvider`, `embeddingModel`, `rerankModel`, `chunkSize`, `chunkOverlap`, `topK`, and `storeDir`
- [x] When `provider` and `embeddingProvider` are both non-openai and non-anthropic, `status` output contains neither `openai-api-key` nor `anthropic-api-key`
- [x] `status --output-format json` includes `documentCount`, `storeSizeBytes`, `staleDocumentCount`, `lastIngestTime`, and a `configuration` nested object with all config fields

### Quality gates

- [x] No compiler warnings introduced
- [x] All updated `StatusCommandTest` tests pass (tests that previously asserted on per-document paths are updated to assert on aggregate fields instead)
