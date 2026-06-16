## Problem Statement

Users need a way to inspect what the ez-rag ingestion pipeline produces for any supported file type, both before and after chunking. Currently, the `to-markdown` command offers a partial preview for PDF and HTML files only, leaving most supported formats (Word, PowerPoint, Excel, RTF, CSV, plain text) without any inspection capability. Without visibility into the intermediate representations, users cannot diagnose ingestion quality, tune chunking parameters, or verify that conversions are producing useful content before committing to a full ingest.

## Solution

Replace the `to-markdown` command with two general-purpose pipeline inspection commands:

- **`to-document`** — shows the fully converted text for any supported file or URL, exactly as the ingestion pipeline would see it before chunking. This generalises `to-markdown` to cover all supported file types.
- **`to-chunks`** — shows the individual chunks that the ingestion pipeline would produce for any supported file or URL, complete with chunk metadata. Works standalone without an initialised store, so users can experiment with chunk size and overlap before ingesting.

Together these commands make the ingestion pipeline transparent and inspectable at each of its two key stages.

## User Stories

1. As a user, I want to run `to-document` on a Word document, so that I can see the Markdown text the pipeline will extract before ingesting it.
2. As a user, I want to run `to-document` on a PowerPoint file, so that I can verify that slide titles and content are correctly captured as headings and text.
3. As a user, I want to run `to-document` on an Excel file, so that I can confirm that table data is rendered in a readable format.
4. As a user, I want to run `to-document` on a CSV file, so that I can check how the tabular data is represented before ingestion.
5. As a user, I want to run `to-document` on an RTF file, so that I can see the plain text extracted from it.
6. As a user, I want to run `to-document` on a plain text file, so that I can confirm its content passes through unchanged.
7. As a user, I want to run `to-document` on a Markdown file, so that I can verify front matter is stripped and the body is preserved.
8. As a user, I want to run `to-document` on a PDF file, so that I can see the Markdown extracted from it before ingestion.
9. As a user, I want to run `to-document` on an HTML file or HTTP URL, so that I can inspect the Markdown produced from web content.
10. As a user, I want to pass `--pdf-mode rag` to `to-document` when processing a PDF, so that I can use the RAG-optimised extraction mode instead of the readable default.
11. As a user, I want to pass `--pdf-max-pages N` to `to-document`, so that I can preview only the first N pages of a large PDF.
12. As a user, I want `to-document` to print an informative error when I supply an unsupported file type, so that I understand why the command failed.
13. As a user, I want `to-document` to print an informative error when the file does not exist, so that I can correct my input.
14. As a user, I want `to-document` to write errors to stderr and content to stdout, so that I can pipe the output to other tools without mixing in error messages.
15. As a user, I want to run `to-chunks` on any supported file, so that I can see exactly how it would be split into chunks by the ingestion pipeline.
16. As a user, I want `to-chunks` to work without an initialised store, so that I can experiment with chunking before deciding to ingest.
17. As a user, I want to pass `--chunk-size` and `--chunk-overlap` to `to-chunks`, so that I can tune chunking parameters and see the effect before ingesting.
18. As a user, I want `to-chunks` to display metadata alongside each chunk (chunk index, heading path, heading title, heading level, page title, filename), so that I understand how the chunk will be indexed and retrieved.
19. As a user, I want `to-chunks` to omit metadata fields that are not present for a given file type, so that the output is not cluttered with empty fields.
20. As a user, I want to request `--output-format text` from `to-chunks` (the default), so that I get a human-readable preview in the terminal.
21. As a user, I want to request `--output-format xml` from `to-chunks`, so that I can parse the output programmatically or pass it to other tools.
22. As a user, I want to request `--output-format json` from `to-chunks`, so that I can process the chunk list with standard JSON tooling.
23. As a user, I want `to-chunks` to print an informative error for unsupported file types or missing files, so that I understand why the command failed.
24. As a user, I want existing scripts that call `to-markdown` on PDF or HTML files to be informed of the rename, so that I know to update them to `to-document`.

## User Acceptance Tests

1. Given a Word (.docx) file with headings and body text, when `to-document` is run on it, then the output contains Markdown headings (`#`) corresponding to the document's heading styles and the body text is present.
2. Given a PowerPoint (.pptx) file with titled slides, when `to-document` is run on it, then each slide title appears as a Markdown heading in the output.
3. Given an Excel (.xlsx) file with a named sheet and tabular data, when `to-document` is run on it, then the sheet name appears as a heading and the data is rendered as a Markdown table.
4. Given a CSV file with headers and data rows, when `to-document` is run on it, then the output contains a Markdown table with the correct headers and data.
5. Given an RTF file with formatted text, when `to-document` is run on it, then the output contains the plain text content of the file.
6. Given a plain text file, when `to-document` is run on it, then the output matches the file content exactly.
7. Given a PDF file, when `to-document` is run with `--pdf-mode rag`, then the output differs from the default `--pdf-mode readable` output, reflecting RAG-optimised extraction.
8. Given a PDF file with 10 pages, when `to-document` is run with `--pdf-max-pages 3`, then the output contains content from at most the first 3 pages.
9. Given a file with an unsupported extension, when `to-document` is run on it, then an error message appears on stderr and the exit code is non-zero.
10. Given a path to a non-existent file, when `to-document` is run on it, then an error message appears on stderr and the exit code is non-zero.
11. Given a Markdown file with a word repeated many times, when `to-chunks` is run with `--chunk-size 50 --chunk-overlap 10`, then the output contains more chunks than when run with `--chunk-size 500`.
12. Given a Markdown file with multiple headings, when `to-chunks` is run on it, then each chunk's metadata includes the heading path reflecting the section it belongs to.
13. Given any supported file, when `to-chunks` is run with `--output-format xml`, then the output is valid XML with one `<result>` element per chunk, each carrying metadata as attributes and chunk text as content.
14. Given any supported file, when `to-chunks` is run with `--output-format json`, then the output is valid JSON containing a `chunks` array with one entry per chunk.
15. Given a plain text file (no headings), when `to-chunks` is run on it, then chunk metadata does not include heading-related fields.
16. Given `to-chunks` is run on a file without a store being present, then the command succeeds and produces chunk output.
17. Given an HTML page is fetched via an HTTP URL, when `to-chunks` is run on the URL, then the output contains chunks from the page content.

## Definition of Done

- All user acceptance tests pass.
- `to-document` handles every file type supported by `ingest` (PDF, HTML, DOCX, DOC, PPTX, PPT, XLSX, XLS, RTF, CSV, TXT, MD) and HTTP/HTTPS URLs.
- `to-chunks` produces correct chunk output for every supported file type, with accurate metadata.
- `to-markdown` is no longer present as a CLI subcommand.
- `--output-format text|json|xml` works correctly for `to-chunks` with well-formed output in each format.
- `to-chunks` works without an initialised store directory.
- All automated tests pass (unit and command-level).
- No regression in existing CLI commands or MCP tools.
- README updated to reflect the new commands and the removal of `to-markdown`.

## Out of Scope

- Directory input: both `to-document` and `to-chunks` accept only a single file or URL, not directories.
- Batch processing or glob expansion.
- Writing output to a file (always stdout).
- Embedding or indexing chunks (this is a preview/diagnostic tool only; use `ingest` to actually index).
- Configuring the chunking strategy (only token-based chunking with `--chunk-size` and `--chunk-overlap` is supported, matching `ingest`).
- A migration shim or alias for `to-markdown`; users must update their scripts manually.

## Further Notes

- The output of `to-document` for plain text and RTF files is plain text, not Markdown. This is intentional and reflects the actual intermediate representation used by the ingestion pipeline.
- `to-chunks` metadata fields vary by file type (e.g., `heading_path` is absent for plain text; `page_title` is only present for HTML). Fields are omitted from output when not present for a given chunk.
- The `to-chunks` text format should visually resemble the `search` command's text output to provide a consistent user experience.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### 1. `ToDocumentCommand` replaces `ToMarkdownCommand`

- New class `ToDocumentCommand` registered as `to-document` in `EzRagCommand`.
- `ToMarkdownCommand` and its registration are deleted.
- Uses `DocumentReaderRegistry` (already used by `IngestService`) to look up the appropriate reader by file extension or content type, rather than hardcoding PDF/HTML checks.
- For URLs, content-type detection follows the same logic as `IngestService`.
- PDF-specific picocli options:
  - `--pdf-mode` (was `--mode`): `readable` | `rag`, default `readable`
  - `--pdf-max-pages` (was `--max-pages`): `Int`, default `0` (unlimited)
- The `--output-format markdown|xml` option from `ToMarkdownCommand` is dropped; output is always the raw text produced by the reader.
- Output (text) goes to stdout; errors go to stderr; non-zero exit on error.

#### 2. `ToChunksCommand`

- New class `ToChunksCommand` registered as `to-chunks` in `EzRagCommand`.
- Does **not** require an open `LuceneRepository` — reads and chunks the file entirely in memory.
- Accepts a single file path or HTTP/HTTPS URL as a positional argument.
- Picocli options:
  - `--output-format`: `text` | `json` | `xml`, default `text`
  - `--chunk-size`: `Int`, default `1000` (mirrors `IngestCommand`)
  - `--chunk-overlap`: `Int`, default `200` (mirrors `IngestCommand`)
- Uses `DocumentReaderRegistry` to load the document, then applies the same chunking path as `IngestService` (heading-aware splitting for Markdown output, `TokenTextSplitter` fallback).
- PDF-specific options (`--pdf-mode`, `--pdf-max-pages`) should also be available for consistency with `to-document`.
- Metadata fields rendered per chunk (omit when absent):
  - `chunk_index` (always present)
  - `heading_title`, `heading_level`, `heading_path` (Markdown, DOCX, PPTX, PDF, HTML)
  - `page_title` (HTML)
  - `filename` (plain text)

#### 3. `OutputFormatter` extensions

- Add overloaded methods to `OutputFormatter` for a `List<Document>` (Spring AI document type carrying `content` and `metadata: Map<String, Any>`):
  - `formatText(chunks: List<Document>): String`
  - `formatJson(chunks: List<Document>): String`
  - `formatXml(chunks: List<Document>): String`
- Text format: mirrors `search` text output but replaces `score=` with available metadata fields, e.g.:
  ```
  [1] chunk=0  heading_path=Introduction > Overview
  <chunk text>

  [2] chunk=1  heading_path=Introduction > Details
  <chunk text>
  ```
- XML format: mirrors `search` XML but without `score` or `mode` attributes:
  ```xml
  <results>
  <result index="1" chunk="0" heading_path="Introduction &gt; Overview">
  chunk text here
  </result>
  </results>
  ```
- JSON format:
  ```json
  {
    "chunks": [
      {"chunkIndex": 0, "headingPath": "Introduction > Overview", "content": "chunk text here"},
      ...
    ]
  }
  ```
- Metadata keys use camelCase in JSON, snake_case attribute names in XML, and raw key=value pairs in text — consistent with existing `OutputFormatter` conventions.

#### 4. `EzRagCommand` update

- Remove `ToMarkdownCommand::class` from the `@Command(subcommands = [...])` annotation.
- Add `ToDocumentCommand::class` and `ToChunksCommand::class`.

### Automated Testing Decisions

**What makes a good test:** Tests should invoke the command class directly (or via picocli's `CommandLine`) with a real test fixture file and assert on stdout/stderr strings or exit codes. Do not test internal converter classes through these command tests — those are tested elsewhere. Only test the observable command behaviour.

#### `ToDocumentCommandTest`
- Prior art: `ToMarkdownCommandTest` (253 lines) — same pattern of constructing the command with injected output streams and calling `.execute(args)`.
- Tests to write:
  - Each supported file type produces non-empty output (one test per type with a fixture file).
  - `--pdf-mode rag` produces different output than `--pdf-mode readable` for a PDF fixture.
  - `--pdf-max-pages 1` limits PDF output to one page.
  - Unsupported extension → error on stderr, non-zero exit.
  - Non-existent file → error on stderr, non-zero exit.
  - HTTP URL returning HTML → non-empty output.
  - `--pdf-mode` on a non-PDF file → error on stderr.

#### `ToChunksCommandTest`
- Prior art: `SearchCommandTest` (608 lines) — tests output format variants and error paths via picocli `CommandLine.execute()`.
- Tests to write:
  - Default text output contains chunk index metadata.
  - `--output-format xml` produces well-formed XML with `<result>` elements.
  - `--output-format json` produces valid JSON with `chunks` array.
  - `--chunk-size 50` on a large file produces more chunks than default.
  - Heading metadata appears in output for a Markdown fixture with headings.
  - Plain text fixture output does not include `heading_path` field.
  - Unsupported extension → error on stderr, non-zero exit.
  - Non-existent file → error on stderr, non-zero exit.
  - Works without a store directory present.

#### `OutputFormatterTest` extensions
- Prior art: `OutputFormatterTest` (270 lines) — calls `OutputFormatter` methods directly with hand-constructed model objects.
- Tests to write:
  - `formatText` with a list of chunks containing heading metadata produces correct `[N] chunk=X heading_path=...` headers.
  - `formatXml` produces valid XML with correct attributes and escaped content.
  - `formatJson` produces valid JSON with all metadata fields present.
  - Chunks without optional metadata fields (e.g., no `heading_path`) omit those fields from output.
  - Empty chunk list produces empty/valid output in all three formats.
