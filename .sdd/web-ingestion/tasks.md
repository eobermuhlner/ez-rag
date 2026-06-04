# Tasks: Web Ingestion

## Task 01-content-hash-staleness

Add a SHA-256 content hash to the Lucene index as the authoritative staleness signal for local files. A file whose mtime changed but whose content is identical is skipped on re-ingest; a file whose content changed is re-ingested. This lays the staleness infrastructure that URL ingestion will reuse.

### Implementation steps

- [x] Add `content_hash` as a `StoredField` (SHA-256 hex string, one field per Lucene document) to `LuceneRepository`; the hash is stored on every chunk of a source but logically belongs to the source — it is the same value across all chunks of a given source
- [x] Add `contentHash: String?` to `StoreDocumentInfo` in `RepositoryModels`; populate it from the Lucene stored field in `getMetadata()`
- [x] Compute SHA-256 of raw file bytes in `IngestService` before calling the staleness check; pass the hash alongside `mtime`
- [x] Implement two-step staleness check in `LuceneRepository`: if `mtime` in the cache matches the incoming value → skip immediately (fast path, no I/O); if `mtime` differs → retrieve `content_hash` from the index and compare; skip if hash matches, re-ingest if hash differs (or if no hash is stored)
- [x] All existing callers of `isAlreadyIngested(source, mtime)` are updated or the method is extended without breaking existing call sites

### Acceptance criteria

- [x] `IngestService` ingesting a file twice — where the second call has the same content but a different `mtime` (simulated via a test stub) — reports `filesIngested = 0` and `skipped = 1`
- [x] `IngestService` ingesting a file twice — where the second call has changed content and a different `mtime` — reports `filesIngested = 1` and `skipped = 0`
- [x] `LuceneRepository.getMetadata()` returns `StoreDocumentInfo` with a non-null `contentHash` for every stored source after ingestion
- [x] `StoreDocumentInfo` does not expose `contentHash` at the per-chunk (`DocumentChunkInfo`) level; it is a per-source property only

### Quality gates

- [x] All existing `LuceneRepositoryTest`, `IngestServiceTest`, and `ReIngestServiceTest` cases pass without modification
- [x] `./gradlew build` compiles without warnings

---

## Task 02-html-url-ingestion

`ez-rag ingest https://example.com/page.html` fetches an HTML page, extracts structured text (h1–h6 headings, paragraphs, code blocks), chunks it with the existing heading-aware splitter, stores chunks with `page_title` and `heading_title` metadata, and skips re-ingestion on a second call if the raw HTML bytes are unchanged. Mixed invocations — file paths and URLs in the same command — work in a single call.

### Implementation steps

- [x] Add `org.jsoup:jsoup` to `build.gradle.kts` as an `implementation` dependency
- [x] Introduce `IngestSource` sealed class with `FileSource(val file: File)` and `UrlSource(val url: String)` variants in the ingestion package
- [x] Define `UrlFetcher` interface returning a `FetchResult` value object: raw bytes (`ByteArray`), `contentType: String`, `lastModifiedEpochMs: Long` (0 when the HTTP `Last-Modified` header is absent), `statusCode: Int`
- [x] Implement a Jsoup-backed `JsoupUrlFetcher` (10 s connect timeout, 30 s read timeout, follow redirects, store original URL as the source key regardless of redirect target)
- [x] Create `HtmlDocumentReader(html: String, chunkSize: Int, chunkOverlap: Int)` using Jsoup: convert h1–h6 to Markdown heading lines, extract p/li/pre as body text, feed the result into `SectionSplitter` (the same splitter used by `MarkdownDocumentReader`), attach `page_title` from the HTML `<title>` element to every produced chunk's metadata
- [x] Add an optional `urlFetcher: UrlFetcher` constructor parameter to `IngestService` (defaults to `JsoupUrlFetcher`); tests inject a fake
- [x] Add a new overload `IngestService.ingest(sources: Iterable<IngestSource>): IngestResult`; keep the existing `ingest(files: List<File>)` as a convenience overload that wraps each `File` in `FileSource` and delegates to the new overload — this preserves all existing callers without modification (Note: uses `Iterable<IngestSource>` instead of `List<IngestSource>` to avoid JVM type-erasure clash)
- [x] In the new `ingest(Iterable<IngestSource>)` overload, route `UrlSource` through: `UrlFetcher.fetch()` → compute SHA-256 of raw bytes → `isContentUnchanged` two-step check (using `lastModifiedEpochMs` as `mtime`, where `0` means the fast-path never fires and hash comparison always runs) → `HtmlDocumentReader` → `LuceneRepository.add()`
- [x] Update `IngestCommand` to detect `http://`/`https://` prefix in each positional argument → `UrlSource`; everything else → `FileSource`; invoke `ingest(Iterable<IngestSource>)`

### Acceptance criteria

- [x] `IngestService` ingesting a fake HTML URL (injected via `UrlFetcher` stub) with HTML containing `<h2>Installation</h2>` produces at least one chunk whose metadata contains `heading_title = "Installation"`
- [x] All chunks produced from a URL ingest carry a `page_title` metadata entry matching the HTML `<title>` element
- [x] A second `IngestService.ingest()` call with the same URL and identical raw bytes reports `filesIngested = 0`, `skipped = 1`
- [x] A second `IngestService.ingest()` call with the same URL but changed raw bytes reports `filesIngested = 1`, `skipped = 0`
- [x] `IngestService.ingest(listOf(FileSource(file), UrlSource("https://...")))` with both a real file and a fake URL reports `filesIngested = 2`
- [x] A fake URL returning HTTP status 404 results in a warning printed to the warning writer and `filesIngested = 0`, `skipped = 1` (or an error count if added)
- [x] The existing `ingest(List<File>)` overload still compiles and all tests that call it still pass without modification

### Quality gates

- [x] All existing `IngestServiceTest`, `IngestCommandTest`, `McpIngestToolTest`, and `DocumentReaderRegistryTest` cases pass without modification (the `List<File>` overload must not be removed)
- [x] No test in the `IngestService` URL-path test suite makes a real outbound HTTP call (all use the injected fake `UrlFetcher`)
- [x] `./gradlew build` compiles without warnings

---

## Task 03-non-html-url-content-types

`ez-rag ingest https://example.com/doc.pdf` downloads the PDF over HTTPS and ingests it through the existing `PdfDocumentReader`, producing the same chunks as a local PDF ingest. A `text/plain` URL is routed through `PlainTextDocumentReader`. An unsupported content type (e.g. `image/png`) emits a clear warning and is counted as skipped.

### Implementation steps

- [x] After `UrlFetcher.fetch()` succeeds in `IngestService`, branch on `FetchResult.contentType`:
  - `text/html` or `application/xhtml+xml` → existing `HtmlDocumentReader` path (from Task 02)
  - `application/pdf` → write raw bytes to a temp file in a test-injectable temp directory, pass to `PdfDocumentReader`, delete the temp file in a `finally` block
  - `text/plain` → pass raw bytes decoded as UTF-8 string to `PlainTextDocumentReader` (via a new string-based constructor or by writing to a temp file)
  - Any other type → emit a warning to the warning writer, increment the skipped counter, continue
- [x] The temp-file path for PDF downloads is created inside an injectable `tempDirProvider: () -> Path` (defaults to `Files.createTempDirectory("ez-rag-url-")`) so tests can assert on cleanup in a `@TempDir`

### Acceptance criteria

- [x] `IngestService` with a fake `UrlFetcher` returning `Content-Type: application/pdf` and valid PDF bytes produces at least one chunk and reports `filesIngested = 1`
- [x] `IngestService` with a fake `UrlFetcher` returning `Content-Type: text/plain` produces at least one chunk and reports `filesIngested = 1`
- [x] `IngestService` with a fake `UrlFetcher` returning `Content-Type: image/png` reports `filesIngested = 0`, `skipped = 1`, and the warning writer receives a message containing the unsupported content type
- [x] After `IngestService.ingest()` returns for a PDF URL, the injected temp directory contains no leftover temporary files

### Quality gates

- [x] All existing and Task 02 ingestion tests still pass
- [x] `./gradlew build` compiles without warnings

---

## Task 04-reingest-url-sources

`ez-rag re-ingest` re-fetches all URL sources stored in the index. Sources whose content hash is unchanged are skipped; changed sources are re-ingested. An unreachable URL emits a warning and is skipped safely — no previously stored chunks are deleted unless the fetch succeeds.

### Implementation steps

- [x] Change `ReIngestService.onFileReIngesting` callback type from `((Path) -> Unit)?` to `((String) -> Unit)?`; update `ReIngestCommand`, `McpReIngestTool`, and all existing tests to pass `absolutePath.toString()` instead of the `Path` directly
- [x] In `ReIngestService`, when iterating `StoreDocumentInfo`, detect URL sources by `path.startsWith("http://") || path.startsWith("https://")` and construct `UrlSource`; otherwise construct `FileSource(File(path))`
- [x] For URL sources, skip the `File.exists()` guard entirely
- [x] Change the delete-then-ingest order for URL sources to a fetch-first pattern: attempt the fetch before deleting old chunks from the index; only delete and re-ingest if the fetch succeeds and the hash differs; log a warning and skip if the fetch fails, leaving old chunks intact
- [x] Pass the collected `List<IngestSource>` to `IngestService.ingest(List<IngestSource>)` for the actual re-ingestion

### Acceptance criteria

- [x] After ingesting a URL source (via fake `UrlFetcher`) and then changing the fake's response bytes, `re-ingest` reports `filesReIngested = 1`
- [x] After ingesting a URL source with unchanged response bytes, `re-ingest` reports `filesReIngested = 0`, `filesSkipped = 1`
- [x] When a URL source is unreachable during `re-ingest` (fake `UrlFetcher` throws), the warning writer receives a message, `filesSkipped` is incremented, and the previously stored chunks for that URL remain in the index
- [x] File sources already in the index are still re-ingested correctly by `re-ingest` (no regression)

### Quality gates

- [x] All existing `ReIngestServiceTest`, `ReIngestCommandTest`, and `McpReIngestToolTest` cases pass (with callback type updated to `String`)
- [x] `./gradlew build` compiles without warnings

---

## Task 05-mcp-url-ingestion

The `mcp_ingest` MCP tool accepts an HTTP/HTTPS URL as its `path` parameter and ingests it with the same staleness and error-handling behaviour as the CLI `ingest` command. The tool description is updated to advertise URL support.

### Implementation steps

- [x] Add an optional `urlFetcher: UrlFetcher` constructor parameter to `McpIngestTool` (defaults to `JsoupUrlFetcher()`); forward it to `IngestService` via the `ingestServiceFactory` lambda (update the factory type from `(Int, Int) -> IngestService` to `(Int, Int, UrlFetcher) -> IngestService`)
- [x] In `McpIngestTool.ingest()`, detect `http://`/`https://` prefix on `path` → build `UrlSource`; otherwise build `FileSource(File(path))`; call `service.ingest(listOf(source))` using the `List<IngestSource>` overload
- [x] Update `@Tool(description = ...)` to mention that HTTP and HTTPS URLs are accepted in addition to file paths
- [x] Update `@ToolParam(description = ...)` for `path` accordingly

### Acceptance criteria

- [x] `McpIngestTool` with an injected fake `UrlFetcher` and a URL path returns `IngestToolResult(filesIngested = 1, chunksCreated ≥ 1, skipped = 0)`
- [x] A second call with the same URL and unchanged bytes returns `IngestToolResult(filesIngested = 0, skipped = 1)`
- [x] A call with an unreachable URL (fake `UrlFetcher` throws) returns `IngestToolResult(filesIngested = 0, error ≠ null)`
- [x] `McpIngestTool.ingest("/local/file.txt", ...)` still produces `filesIngested = 1` (no regression for file paths)

### Quality gates

- [x] All existing `McpIngestToolTest` cases pass (updated to use the new factory signature)
- [x] `./gradlew build` compiles without warnings
