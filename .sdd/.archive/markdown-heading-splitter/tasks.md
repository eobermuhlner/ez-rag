# Tasks: Markdown Heading Splitter & Per-Format Document Reader Registry

## Task [01-reader-registry]

Establish a `DocumentReaderRegistry` and format-specific readers (`PlainTextDocumentReader`, `PdfDocumentReader`, `MarkdownDocumentReader` with token-based chunking only) that replace `DocumentLoader` and `DocumentChunker`. `IngestService` is rewired to use the registry and takes over `source` metadata responsibility (which `DocumentLoader` previously set). After this task, ingestion works end-to-end for `.txt`, `.pdf`, and `.md` files via the new path; `DocumentLoader` and `DocumentChunker` are deleted.

### Implementation steps

- [x] Write a failing `PlainTextDocumentReaderTest`: a `.txt` file with known content produces at least one chunk whose text contains that content, and no chunk has `source` in its metadata
- [x] Implement `PlainTextDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int)` with `read(): List<Document>` wrapping Spring AI's `TextReader` + `TokenTextSplitter` — do not set `source` metadata
- [x] Write a failing `PdfDocumentReaderTest`: a `.pdf` file produces at least one non-empty chunk; no chunk has `source` in its metadata
- [x] Implement `PdfDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int)` wrapping `PagePdfDocumentReader` + `TokenTextSplitter` — do not set `source` metadata
- [x] Write a failing `MarkdownDocumentReaderTest` (token-only mode): a `.md` file with body text (no headings) produces at least one chunk; no chunk has `source` in its metadata
- [x] Implement `MarkdownDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int)` that strips YAML front-matter and applies `TokenTextSplitter` — do not set `source` metadata (heading splitting comes in Task 02)
- [x] Write failing `DocumentReaderRegistryTest` covering: `supports()` returns true for txt/pdf/md and false for docx; `read()` dispatches to the correct reader; `read()` throws `IllegalArgumentException` for unsupported extension
- [x] Implement `DocumentReaderRegistry(chunkSize: Int, chunkOverlap: Int)` mapping `.txt → PlainTextDocumentReader`, `.pdf → PdfDocumentReader`, `.md → MarkdownDocumentReader`
- [x] Rewrite `IngestService` to construct `DocumentReaderRegistry` internally; explicitly set `source` on each document after `read()` returns (moved from readers); `mtime` and `chunk_index` continue to be set by `IngestService` as before
- [x] Delete `DocumentLoader`, `DocumentChunker`, `DocumentLoaderTest`, `DocumentChunkerTest`

### Acceptance criteria

- [x] A `.txt` file ingested through the new registry produces at least one chunk; no reader sets `source` (confirmed by reader unit tests asserting `source` is absent from reader output)
- [x] A `.pdf` file ingested through the new registry produces at least one chunk; no reader sets `source`
- [x] A `.md` file with body text ingested through the new registry produces at least one chunk (token-based); no reader sets `source`
- [x] `DocumentReaderRegistry.supports("txt")`, `supports("pdf")`, `supports("md")` return `true`; `supports("docx")` returns `false`
- [x] `DocumentReaderRegistry.read(file)` throws `IllegalArgumentException` for a file with an unsupported extension
- [x] `IngestService` sets `source`, `mtime`, and `chunk_index` on every chunk regardless of format
- [x] `DocumentLoader` and `DocumentChunker` no longer exist in the codebase

### Quality gates

- [x] No compiler warnings
- [x] All `PlainTextDocumentReaderTest`, `PdfDocumentReaderTest`, `MarkdownDocumentReaderTest`, and `DocumentReaderRegistryTest` pass
- [x] All existing `IngestServiceTest` assertions still pass
- [x] All existing `ShowCommandTest` assertions still pass

---

## Task [02-markdown-heading-splitter]

Replace the token-only `MarkdownDocumentReader` with a full heading-aware implementation. The reader splits Markdown on ATX-style headings (`^(#{1,6})\s+(.+)$`), prepends the full ancestor heading path (with original `#` markers) to each chunk's content, emits pre-heading content as a standalone chunk without heading metadata, stores `heading_title`, `heading_level`, and `heading_path` on each heading-section chunk, suppresses empty chunks, and falls back to `TokenTextSplitter` when no headings are found. YAML front-matter is stripped before heading detection.

Follow the project's mandatory TDD cycle: write one failing test, implement just enough to pass it, then move to the next test.

### Implementation steps

- [x] Test & implement: a Markdown file with no headings falls back to token-based chunking — at least one chunk produced, no chunk has `heading_title` in metadata
- [x] Test & implement: YAML front-matter (`---…---`) is stripped before processing — front-matter delimiters do not appear in any chunk's content
- [x] Test & implement: pre-heading body text (before the first heading) emits as a standalone chunk without `heading_title`, `heading_level`, or `heading_path` metadata
- [x] Test & implement: a single heading with body text produces one chunk; its content begins with `# Heading Title\n` followed by the body
- [x] Test & implement: a heading with no body text (or whitespace-only body) produces no chunk
- [x] Test & implement: `heading_title` equals the immediate heading text (without `#` marks); `heading_level` equals the count of `#` characters (1–6)
- [x] Test & implement: nested headings — a `##` section under a `#` section has `heading_path = listOf("H1 Title", "H2 Title")` and its content is prepended with `# H1 Title\n## H2 Title\n`
- [x] Test & implement: two consecutive `##` headings produce two independent chunks; the second chunk's `heading_path` does not include the first `##` title (sibling resets the stack at that level)
- [x] Add a quality-gate assertion in `MarkdownDocumentReaderTest` or `IngestServiceTest` that a `heading_path` value written by the reader survives a `SimpleVectorStore` JSON serialisation round-trip and can be read back as `List<String>` by `VectorStoreRepository.getChunksForFile()`

### Acceptance criteria

- [x] A Markdown file with no headings produces at least one chunk via token-based fallback; no chunk has `heading_title` in metadata
- [x] YAML front-matter (`---…---`) does not appear in any chunk's content
- [x] Pre-heading content emits as a chunk without `heading_title`, `heading_level`, or `heading_path`
- [x] Each ATX heading with non-empty body produces exactly one chunk
- [x] A heading with whitespace-only or absent body produces no chunk
- [x] Each chunk's content begins with the full ancestor heading path in `#`-prefixed lines (one per ancestor level), followed by the body text
- [x] `heading_title` = the heading text after the `#` marks and whitespace; `heading_level` = count of `#` (1–6)
- [x] `heading_path` = ordered list of ancestor heading titles from outermost to current, inclusive
- [x] Two consecutive `##` headings produce two independent chunks; the second chunk's `heading_path` contains only its own title at the `##` level (and any enclosing `#` ancestors), not the first `##` title
- [x] `heading_path` stored as a JSON array by `SimpleVectorStore` round-trips correctly back to `List<String>` via `VectorStoreRepository.getChunksForFile()`

### Quality gates

- [x] No compiler warnings
- [x] All `MarkdownDocumentReaderTest` tests pass (one test per behaviour, written before implementation)
- [x] `heading_path` round-trip test passes (serialise via `SimpleVectorStore`, read back via `VectorStoreRepository`)
- [x] All existing `IngestServiceTest` and `ShowCommandTest` assertions still pass

---

## Task [03-show-heading-display]

Extend `DocumentChunkInfo` with optional heading fields (`headingTitle`, `headingLevel`, `headingPath`), populate them in `VectorStoreRepository.getChunksForFile()` from stored chunk metadata, and display them in `ShowCommand` in both text and JSON output modes.

Follow the project's mandatory TDD cycle: write one failing test, implement just enough to pass it, then move to the next test.

### Implementation steps

- [x] Test & implement: add `headingTitle: String?`, `headingLevel: Int?`, `headingPath: List<String>?` to `DocumentChunkInfo` with defaults `null`; existing tests using the data class continue to compile and pass
- [x] Test & implement (VectorStoreRepositoryTest): `getChunksForFile()` returns `headingTitle`, `headingLevel`, and `headingPath` populated when those metadata keys are present on a stored chunk
- [x] Test & implement (VectorStoreRepositoryTest): `getChunksForFile()` returns `null` for all three heading fields when the metadata keys are absent (non-Markdown chunk)
- [x] Test & implement (VectorStoreRepositoryTest): `heading_path` stored as a JSON array in chunk metadata is deserialised back to `List<String>` in `DocumentChunkInfo.headingPath`
- [x] In `VectorStoreRepository.getChunksForFile()`, read `heading_title` (String), `heading_level` (Int), and `heading_path` (List<String>) from each chunk's metadata when present and populate the new `DocumentChunkInfo` fields
- [x] Test & implement (ShowCommandTest): text output for a chunk with `headingTitle` non-null includes `heading: ${"#".repeat(headingLevel)} ${headingTitle}` appended to the per-chunk summary line
- [x] Test & implement (ShowCommandTest): JSON output for a chunk with `headingTitle` non-null includes `headingTitle`, `headingLevel`, and `headingPath` keys in the chunk object
- [x] Test & implement (ShowCommandTest): text and JSON output for a chunk with all heading fields `null` contains no heading-related keys or text
- [x] Update `ShowCommand` text and JSON rendering to conditionally emit heading info when `headingTitle` is non-null

### Acceptance criteria

- [x] `ShowCommand` text output for a Markdown chunk with `headingLevel = 2` and `headingTitle = "Section Name"` includes the string `heading: ## Section Name` in the per-chunk summary line
- [x] `ShowCommand` JSON output for a Markdown chunk includes `"headingTitle"`, `"headingLevel"`, and `"headingPath"` keys in the chunk object when non-null
- [x] `ShowCommand` text output for a non-Markdown chunk contains no substring `heading:`
- [x] `ShowCommand` JSON output for a non-Markdown chunk contains no `headingTitle`, `headingLevel`, or `headingPath` keys
- [x] `DocumentChunkInfo.headingTitle`, `.headingLevel`, and `.headingPath` are `null` for chunks without heading metadata
- [x] `VectorStoreRepository.getChunksForFile()` correctly maps `heading_path` JSON array metadata to `List<String>` in `DocumentChunkInfo.headingPath`

### Quality gates

- [x] No compiler warnings
- [x] All new `VectorStoreRepositoryTest` assertions (heading read-back cases) pass
- [x] All new `ShowCommandTest` assertions (heading display cases) pass
- [x] All pre-existing `VectorStoreRepositoryTest` and `ShowCommandTest` assertions still pass
