# Tasks: 03-document-ingestion

## Task [01-ingest-txt-to-persistent-store]

Ingest a single `.txt` file end-to-end: file path → loaded as Spring AI `Document` → split into overlapping chunks (default 1000 tokens, 200 overlap) → embedded → saved to `.ez-rag/vector-store.json`. The `ingest` subcommand becomes functional for the simplest case. The `.ez-rag/` directory is created automatically if absent. A summary line is printed to stdout.

Modules introduced: `DocumentLoader` (`.txt` only), `DocumentChunker` (wraps `TokenTextSplitter`), `VectorStoreRepository` (load from disk if file exists, add documents, save to disk).

### Implementation steps

- [x] Implement `DocumentLoader` for `.txt` using Spring AI's `TextReader`; return a list of `Document` with `source` metadata set to the file path
- [x] Write `DocumentLoader` unit test: loading a small `.txt` returns ≥1 `Document` whose `source` metadata equals the file path
- [x] Implement `DocumentChunker` wrapping `TokenTextSplitter`; propagate `source` metadata to each chunk
- [x] Write `DocumentChunker` unit test: a document exceeding the chunk size produces >1 chunk, each carrying `source` metadata
- [x] Implement `VectorStoreRepository` with `load()` (create empty store if file absent), `add(documents)`, and `save()`
- [x] Wire `IngestCommand` to call `DocumentLoader` → `DocumentChunker` → `VectorStoreRepository.add()` → `VectorStoreRepository.save()`; create `.ez-rag/` if absent
- [x] Print summary: `X files ingested, Y chunks created, Z skipped`
- [x] Write integration test: ingest a small `.txt` to a temp store path; assert store file exists, is valid JSON, and chunk count ≥ 1

### Acceptance criteria

- [x] `ez-rag ingest sample.txt` exits 0
- [x] Integration test: after ingestion, the store JSON file exists at the configured path and deserialises without error
- [x] Integration test: stdout contains `1 files ingested, N chunks created, 0 skipped` where N ≥ 1
- [x] Integration test: running `ez-rag ingest` when `.ez-rag/` already exists does not fail (idempotent directory creation, verified by test that calls ingest twice)
- [x] `DocumentLoader` unit test: loading a small `.txt` returns ≥1 `Document` with `source` metadata equal to the file path
- [x] `DocumentChunker` unit test: a document larger than the chunk size produces >1 chunk; each chunk carries the `source` metadata of the original

### Quality gates

- [x] `./gradlew check` passes with zero failures

---

## Task [02-directory-discovery-pdf-markdown]

Extend `ingest` to accept directory paths and add `.pdf` and `.md` support. A `DirectoryWalker` recursively enumerates files with supported extensions in deterministic alphabetical order, emitting a warning line for each unsupported extension. `DocumentLoader` is extended with `PagePdfDocumentReader` for `.pdf` and a text-pass-through reader that strips YAML front-matter for `.md`. `IngestCommand` delegates directory inputs to `DirectoryWalker`.

### Implementation steps

- [x] Implement `DirectoryWalker`: recursively enumerate `.txt`, `.pdf`, `.md`; log a warning for each unsupported file; return paths sorted alphabetically
- [x] Write `DirectoryWalker` unit test: temp dir with `.txt`, `.pdf`, `.md`, and `.xyz` returns exactly 3 paths sorted alphabetically
- [x] Extend `DocumentLoader` with `.pdf` support via `PagePdfDocumentReader`
- [x] Write `DocumentLoader` unit test: loading a small `.pdf` from test resources returns ≥1 `Document`
- [x] Extend `DocumentLoader` with `.md` support; strip YAML front-matter before passing content to Spring AI `Document`
- [x] Write `DocumentLoader` unit test: loading a `.md` file with YAML front-matter returns ≥1 `Document` whose content does not contain the front-matter delimiters (`---`)
- [x] Update `IngestCommand` to pass each path through `DirectoryWalker` when the path is a directory
- [x] Write integration test: ingest a temp directory containing one `.txt`, one `.pdf`, one `.md`, and one `.xyz`; assert summary shows `3 files ingested` and a warning for the `.xyz` file

### Acceptance criteria

- [x] `ez-rag ingest docs/` ingests all `.txt`, `.pdf`, and `.md` files found recursively
- [x] Each unsupported extension produces a warning line on stdout/stderr (verified by integration test capturing output)
- [x] Integration test: ingesting a mixed temp directory produces `3 files ingested` in the summary
- [x] `DirectoryWalker` unit test: temp dir returns exactly the 3 supported-extension paths, alphabetically sorted
- [x] `DocumentLoader` unit test: `.pdf` loading returns ≥1 `Document`
- [x] `DocumentLoader` unit test: `.md` with YAML front-matter returns a `Document` whose content does not contain `---`

### Quality gates

- [x] `./gradlew check` passes with zero failures

---

## Task [03-incremental-ingestion-deduplication]

Re-running `ingest` on a previously seen file skips it rather than re-embedding it. Each chunk stores the source file path and last-modified timestamp (epoch millis) as metadata. On subsequent runs `VectorStoreRepository` checks existing metadata and skips files whose `source` path and `mtime` both match; the skipped count appears in the summary. Existing chunks are preserved.

### Implementation steps

- [x] Add `source` (file path) and `mtime` (last-modified epoch millis) metadata to each chunk inside `DocumentChunker` or `IngestCommand`
- [x] Implement `VectorStoreRepository.isAlreadyIngested(path: String, mtime: Long): Boolean` by scanning chunk metadata in the loaded store
- [x] Write `VectorStoreRepository` unit test: after adding documents for `file.txt` with mtime=1000, `isAlreadyIngested("file.txt", 1000)` returns `true`
- [x] Write `VectorStoreRepository` unit test: `isAlreadyIngested("file.txt", 9999)` (different mtime) returns `false`
- [x] Update `IngestCommand` to call `isAlreadyIngested` before loading each file; increment the skipped counter and skip embedding when already ingested
- [x] Write integration test: ingest a temp `.txt` file; ingest same file again without modifying it; assert second run prints `0 files ingested, 0 chunks created, 1 skipped`
- [x] Write integration test: after two runs, the store chunk count equals the first run's count (no duplicates added)
- [x] Write integration test: ingest a file, change its mtime via `Files.setLastModifiedTime` to a future value, ingest again; assert the file is re-ingested (not skipped)

### Acceptance criteria

- [x] Integration test: second ingest of unchanged file prints `0 files ingested, 0 chunks created, 1 skipped`
- [x] Integration test: store chunk count after two identical runs equals chunk count after the first run (no duplicates)
- [x] Integration test: after `Files.setLastModifiedTime` advances the mtime, re-ingesting the file is not skipped (summary shows `1 files ingested`)
- [x] `VectorStoreRepository` unit test: `isAlreadyIngested(path, mtime)` returns `true` for known path+mtime
- [x] `VectorStoreRepository` unit test: `isAlreadyIngested(path, differentMtime)` returns `false`

### Quality gates

- [x] `./gradlew check` passes with zero failures

---

## Task [04-status-command]

`ez-rag status` becomes functional. It reads `VectorStoreRepository.getMetadata()` and displays store path, total chunk count, and a per-file breakdown (file path + chunk count) sorted alphabetically. With `--output-format json` (the inherited flag) it outputs a JSON object. If the store file does not exist, it prints an error referencing the store path and `ez-rag ingest`, then exits non-zero.

JSON schema (from `getMetadata()`):
```
{ "storePath": "<absolute path>",
  "chunkCount": <int>,
  "documents": [ { "path": "<source path>", "chunkCount": <int> }, ... ] }
```

### Implementation steps

- [x] Implement `VectorStoreRepository.getMetadata()`: scan all chunk metadata, aggregate total chunk count and per-source-file chunk counts; return a data structure matching the schema above
- [x] Write `VectorStoreRepository` unit test: after adding 3 chunks for `a.txt` and 2 for `b.txt`, `getMetadata()` returns `chunkCount=5` and per-file counts `a.txt→3`, `b.txt→2`
- [x] Implement `StatusCommand` to call `VectorStoreRepository.load()` then `getMetadata()`; format text output as specified
- [x] Implement JSON output path in `StatusCommand` gated on `outputFormat == "json"` (inherited config field)
- [x] Handle missing store file: print error including store path and `ez-rag ingest`, exit non-zero
- [x] Write integration test: ingest two small files; run `status`; assert text output contains store path, total chunk count, and both filenames
- [x] Write integration test: `status` with `--output-format json` produces valid JSON matching the schema; verify with JSON parsing in the test
- [x] Write integration test: `status` with no store file exits non-zero and output contains the store path

### Acceptance criteria

- [x] Integration test: text output contains `Store: <path>`, `Chunks: N`, and a line for each ingested file with its chunk count
- [x] Integration test: JSON output parses without error and contains keys `storePath` (string), `chunkCount` (int ≥ 1), `documents` (array of `{path, chunkCount}`)
- [x] Integration test: `status` with no store exits non-zero; output contains the store path and a hint to run `ez-rag ingest`
- [x] Text output lists files alphabetically (verified with a test ingesting files whose names would be out of order)
- [x] `VectorStoreRepository` unit test: `getMetadata()` returns correct per-file and total counts

### Quality gates

- [x] `./gradlew check` passes with zero failures

---

## Task [05-configurable-chunking-and-store-path]

Wire the already-declared `--chunk-size`, `--chunk-overlap`, and `--store` CLI flags through `IngestCommand`. `EzRagConfig` and `CliFlags` already carry these fields; this task connects them to `DocumentChunker` and `VectorStoreRepository` inside `IngestCommand`.

### Implementation steps

- [x] Pass `config.chunkSize` and `config.chunkOverlap` from resolved config into `DocumentChunker` inside `IngestCommand`
- [x] Pass `config.storePath` from resolved config into `VectorStoreRepository` inside `IngestCommand`
- [x] Write integration test: ingest a multi-paragraph `.txt` with `--chunk-size 200 --chunk-overlap 50`; assert chunk count in the store is greater than with default settings on the same file
- [x] Write integration test: ingest with `--store /tmp/custom-test.json`; assert the file exists at that path and `.ez-rag/vector-store.json` is absent

### Acceptance criteria

- [x] Integration test: `--chunk-size 200 --chunk-overlap 50` produces more chunks than the default (1000/200) on the same input file, verified by comparing store chunk counts from two runs against the same content
- [x] Integration test: `--store /tmp/custom-test.json` writes the store to the specified path; the default path is not created

### Quality gates

- [x] `./gradlew check` passes with zero failures

---

## Task [06-verbose-output-and-embedding-preflight-validation]

`--verbose` on `ingest` emits a log line for each file processed and each chunk created. Before loading any file, `IngestCommand` validates that the embedding provider configuration is usable (e.g., required API key present for remote providers); if not, it exits non-zero with a human-readable error message and does not create the `.ez-rag/` directory or any partial store.

### Implementation steps

- [x] Add per-file log line in `IngestCommand` under the `verbose` flag: `"Loading: <path>"`
- [x] Add per-chunk log line in `IngestCommand` under `verbose`: `"Chunk <index>: <N> tokens"`
- [x] Write a test: `--verbose ingest` on a small file captures stdout/stderr containing at least one `Loading:` line and at least one `Chunk` line
- [x] Implement pre-flight embedding validation: before file iteration, call a no-op embed (or inspect config) to verify the provider is reachable/configured; surface a user-friendly error on failure
- [x] Write a test: with `--embedding-provider openai` and `OPENAI_API_KEY` unset, `ingest` exits non-zero, the error output contains a human-readable message (not a stack trace as the primary output), and `.ez-rag/` has not been created

### Acceptance criteria

- [x] Test: `ez-rag --verbose ingest sample.txt` stdout/stderr contains ≥1 `Loading:` line and ≥1 `Chunk` line
- [x] Test: when the embedding provider requires a key that is absent, `ingest` exits non-zero with a message that does not lead with a stack trace and does not create the store directory
- [x] The pre-flight check runs before any file I/O (verified by asserting `.ez-rag/` is absent after the failed run)

### Quality gates

- [x] `./gradlew check` passes with zero failures
