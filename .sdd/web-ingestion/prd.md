# PRD: Web Page Ingestion

## Problem Statement

ez-rag can only ingest local files (`.txt`, `.pdf`, `.md`). Users who want to build a knowledge base from web documentation, blog posts, or other online content must manually download each page before ingesting it — an unnecessary friction that breaks the workflow.

## Solution

Extend the existing `ingest` command (and the MCP ingest tool) to accept HTTP/HTTPS URLs alongside local file paths. The tool fetches the page, extracts structured text preserving headings and paragraphs, chunks it using the existing heading-aware splitter, and stores it in the Lucene index exactly like a local file. Re-ingestion is skipped unless the page content has actually changed.

## User Stories

1. As a CLI user, I want to pass an HTTP/HTTPS URL to `ez-rag ingest`, so that I can add a web page to my knowledge base without downloading it manually.
2. As a CLI user, I want to pass a mix of file paths and URLs to a single `ingest` call, so that I can build a knowledge base from both local and remote sources in one command.
3. As a CLI user, I want heading structure from a web page (h1–h6) to be preserved as chunks with heading metadata, so that search results show meaningful context about where a passage came from.
4. As a CLI user, I want the page `<title>` to appear in the metadata of chunks ingested from a URL, so that I can identify the source page in search and show output.
5. As a CLI user, I want `ez-rag ingest <url>` to skip re-fetching a URL whose content has not changed since the last ingest, so that repeated `ingest` calls are fast and idempotent.
6. As a CLI user, I want `ez-rag ingest <url>` to re-ingest a URL when its content has changed (even if the server does not provide a `Last-Modified` header), so that my knowledge base stays up to date.
7. As a CLI user, I want `ez-rag re-ingest` to also re-check all previously ingested URLs for content changes, so that I can refresh the entire knowledge base with one command.
8. As a CLI user, I want `ez-rag ingest <url>` to follow HTTP redirects transparently, so that I do not need to know the canonical URL of a page.
9. As a CLI user, I want ingesting a PDF served over HTTPS to work the same as ingesting a local PDF, so that URLs to PDF documents are not a special case I have to handle myself.
10. As a CLI user, I want a clear error message when a URL cannot be fetched (network error, 404, unsupported content type), so that I know what went wrong and can fix it.
11. As an MCP tool user, I want the `mcp_ingest` tool to accept URLs in its input, so that AI assistants can index web pages into the knowledge base on my behalf.
12. As a CLI user, I want the `list` and `show` commands to display ingested URLs alongside ingested files, so that I have a complete view of what is in my knowledge base.
13. As a CLI user, I want the staleness check to use a content hash (SHA-256 of raw bytes) as the authoritative signal, so that cosmetic re-uploads or server clock drift do not cause unnecessary re-ingestion.
14. As a CLI user, I want the same content-hash staleness check to apply to local files, so that files that are touched without content changes are not re-ingested.
15. As a CLI user, I want `ez-rag ingest --details <url>` to show chunk previews for a fetched URL, so that I can inspect how a web page was chunked.
16. As a CLI user, I want the original URL I typed (not any redirect target) to appear as the source key in the index, so that `delete`, `show`, and `list` commands refer to the same identifier I used at ingest time.

## Implementation Decisions

### IngestSource sealed class

A new `IngestSource` sealed class with two variants replaces `List<File>` throughout the ingestion pipeline:

```
sealed class IngestSource
data class FileSource(val file: File) : IngestSource()
data class UrlSource(val url: String) : IngestSource()
```

`IngestCommand` and `McpIngestTool` detect `http://`/`https://` prefix to construct the appropriate variant. `IngestService` and `ReIngestService` both operate on `List<IngestSource>`.

### HtmlDocumentReader

A new `HtmlDocumentReader` receives raw HTML text and produces `List<Document>` using Jsoup for parsing. It converts `<h1>`–`<h6>` to Markdown-style heading lines and extracts `<p>`, `<li>`, and `<code>`/`<pre>` blocks as structured text. The result is fed into the existing `MarkdownDocumentReader` heading-aware chunking logic (or its extracted `SectionSplitter` directly). The page `<title>` is stored as a `page_title` metadata field on every chunk.

### UrlFetcher interface

A thin `UrlFetcher` interface wraps Jsoup's `Connection` API. It returns a `FetchResult` value object containing raw bytes, `Content-Type`, HTTP `Last-Modified` header value (nullable), and the final response status. The production implementation uses Jsoup with a 10 s connect / 30 s read timeout. In tests, a fake implementation provides canned responses without network access. This is the key seam for testing `IngestService` URL ingestion in isolation.

### Content hash field in LuceneRepository

A new `content_hash` stored field (SHA-256 hex string of raw bytes) is added to every document in the Lucene index. This applies to both file and URL sources — files are hashed at ingest time from their raw bytes. The staleness check logic becomes:

1. If `mtime` in the cache matches the current value → skip (fast path, no I/O).
2. If `mtime` differs → read the `content_hash` from the index and compare to the freshly computed hash.
3. Only re-ingest if the hash also differs.

`isAlreadyIngested` is extended (or a new `isContentUnchanged` method added) to support this two-step check. The `sourceMtimeCache` fast path is preserved.

### Non-HTML content types via URL

When the `Content-Type` of a URL response is `application/pdf`, the raw bytes are written to a temp file and handed to `PdfDocumentReader`. Other MIME types produce a descriptive error. HTML and XHTML types are handled by `HtmlDocumentReader`. Plain text (`text/plain`) is handled by `PlainTextDocumentReader`.

### ReIngestService URL handling

`ReIngestService` iterates `StoreDocumentInfo.path` values from the index. Entries whose `path` starts with `http://` or `https://` are treated as `UrlSource`; others remain `FileSource`. The `File.exists()` guard only applies to `FileSource` entries; URL sources always proceed to the fetch-and-hash check.

### RepositoryModels changes

`StoreDocumentInfo` gains an optional `contentHash: String?` field. `DocumentChunkInfo` gains a matching optional field. These are populated from the new Lucene stored field and used by `ReIngestService` for the two-step staleness check.

### Dependency: Jsoup

`org.jsoup:jsoup` is added to `build.gradle.kts` as an `implementation` dependency. No other new HTTP or HTML library is introduced.

## Testing Decisions

Good tests assert observable behaviour through the public interface of a module, not implementation details. They do not assert which methods were called internally, which library parsed the HTML, or how many intermediate objects were created. A test for `HtmlDocumentReader` asserts the chunks produced, their text content, and their metadata — not how Jsoup was invoked.

**Modules with tests:**

- `HtmlDocumentReader` — unit tests covering: heading hierarchy produces heading metadata, `page_title` metadata on every chunk, plain-text fallback when no headings exist, YAML-like `<head>` content excluded, code blocks preserved in chunks. Prior art: `MarkdownDocumentReaderTest`.

- `UrlFetcher` (fake implementation) — the fake is tested to ensure it behaves consistently with the interface contract expected by `IngestService` tests.

- `IngestService` (URL path) — unit tests using the fake `UrlFetcher`: URL source ingested correctly, URL skipped when hash unchanged (mtime changed but hash same), URL re-ingested when hash changes, PDF content-type routes to PdfDocumentReader, unsupported content-type produces a warning and skips. Prior art: `IngestServiceTest`.

- `LuceneRepository` — unit tests for the new content-hash field: hash stored correctly on add, `isAlreadyIngested` skips when hash unchanged, re-ingests when hash changed. Prior art: `LuceneRepositoryTest`.

- `ReIngestService` — unit tests for URL sources: URL sources re-fetched, missing URL produces a warning and is skipped gracefully. Prior art: `ReIngestServiceTest`.

- `IngestCommand` — unit test that a `http://`-prefixed argument is parsed as a URL source (not a file path). Prior art: `IngestCommandTest`.

## Out of Scope

- Web crawling (following links from an ingested page).
- `robots.txt` compliance.
- Authentication (HTTP Basic, Bearer tokens, cookies).
- Configurable HTTP timeouts.
- Sitemap ingestion.
- JavaScript-rendered pages (only static HTML is fetched).
- Scheduled or automatic re-fetching of URLs on a timer.

## Further Notes

- The `source` key stored in the Lucene index for a URL is always the original URL as typed by the user, not any redirect target. This ensures that `delete`, `show`, and `list` commands refer to the same identifier used at ingest time.
- SHA-256 is used for the content hash. The hex-encoded string fits naturally as a Lucene `StoredField`.
- `mtime` for a URL is the epoch-millisecond value of the HTTP `Last-Modified` response header, or `0` when absent. A stored `mtime` of `0` always fails the fast-path equality check, forcing a hash comparison on every re-ingest attempt for servers that do not emit `Last-Modified`.
