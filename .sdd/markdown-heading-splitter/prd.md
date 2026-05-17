# PRD: Markdown Heading Splitter & Per-Format Document Reader Registry

## Problem Statement

When ingesting Markdown files, ez-rag treats the entire file as a single document and
then applies token-based chunking. This ignores the semantic structure of the document:
headings define natural section boundaries, and chunks that cross those boundaries lose
the context of which section they belong to. As a result, search results for topic-specific
queries can return fragments that are hard to interpret, and the heading hierarchy that
gives a document its meaning is not preserved in any form.

Additionally, the document loading pipeline (`DocumentLoader` + `DocumentChunker`) is not
extensible: adding a new format-specific reader (e.g. a PDF-to-Markdown converter) requires
modifying `DocumentLoader` and duplicating the chunking orchestration logic.

## Solution

Replace the `DocumentLoader` + `DocumentChunker` pipeline with a `DocumentReaderRegistry`
that maps file extensions to format-specific readers. Each reader is fully responsible for
both loading and chunking its format.

For Markdown files, introduce a `MarkdownDocumentReader` that splits on heading boundaries
(H1–H6), prepends the full ancestor heading path (with `#` Markdown syntax) to each chunk's
content, and emits pre-heading content as a regular chunk. Heading metadata (`heading_title`,
`heading_level`, `heading_path`) is stored on each chunk. The `ShowCommand` displays these
fields when present, allowing users to inspect whether the Markdown reader chunked a document
correctly.

The registry design also makes it straightforward to add a future PDF-to-Markdown reader that
converts a PDF to Markdown and then delegates to `MarkdownDocumentReader`.

## User Stories

1. As a developer ingesting a Markdown documentation site, I want each section under a heading to become its own chunk, so that search results map cleanly to discrete topics.
2. As a developer, I want the heading text to be part of the embedded content, so that queries using the heading keywords match the correct section.
3. As a developer, I want the full ancestor heading hierarchy included in each chunk's content, so that deeply nested sections remain interpretable out of context.
4. As a developer, I want pre-heading content (introductory text before the first heading) to be preserved as a chunk, so that no content is silently discarded.
5. As a developer, I want each chunk to carry `heading_title`, `heading_level`, and `heading_path` metadata, so that I can inspect the structure of ingested Markdown documents.
6. As a developer running `ez-rag show myfile.md`, I want to see the heading title, level, and path for each chunk, so that I can verify the Markdown reader split the document as expected.
7. As a developer running `ez-rag show myfile.md --output json`, I want the heading metadata included in the JSON output when present, so that I can process it programmatically.
8. As a developer, I want `.txt` and `.pdf` files to continue working exactly as before, so that the refactor has no observable impact on existing ingestion workflows.
9. As a developer adding a PDF-to-Markdown reader in the future, I want to register it by adding a single entry to the `DocumentReaderRegistry`, so that I do not need to modify `IngestService` or `DocumentLoader`.
10. As a developer, I want YAML front-matter to be stripped from Markdown files before heading-based splitting, so that front-matter delimiters are not misinterpreted as content.
11. As a developer, I want chunks from a Markdown file with no headings to be produced using token-based chunking as a fallback, so that flat Markdown files are still ingested usefully.
12. As a developer, I want heading-only sections (headings with no body text) to not produce empty chunks, so that the store is not polluted with blank documents.
13. As a developer, I want `source`, `mtime`, and `chunk_index` metadata to still be set uniformly for all formats by `IngestService`, so that deduplication and chunk ordering work identically for Markdown and non-Markdown files.
14. As a developer, I want the `MarkdownDocumentReader` to accept a plain `java.io.File`, so that it integrates directly with the registry without unnecessary `Resource` wrapping.
15. As a developer, I want heading levels preserved accurately (H1 = level 1, H6 = level 6) in the `heading_level` metadata, so that I can filter or display chunks by depth.
16. As a developer, I want `heading_path` to contain the full list of ancestor heading titles from outermost to innermost, so that I can reconstruct the document hierarchy from chunk metadata alone.
17. As a developer, I want sibling headings at the same level to correctly reset the heading stack (e.g. two consecutive `##` sections are independent chunks), so that the hierarchy is never corrupted by same-level siblings.

## Implementation Decisions

### Registry Architecture

A `DocumentReaderRegistry` holds a `Map<String, (File) -> List<Document>>` keyed by lowercase
file extension. It exposes two operations: `read(file: File): List<Document>` (dispatches by
extension, throws on unsupported format) and `supports(extension: String): Boolean`. It is a
plain class with no Spring framework involvement, consistent with the existing manual wiring in
`ProviderConfiguration`.

`IngestService` constructs a `DocumentReaderRegistry` internally, replacing its current
construction of `DocumentLoader` and `DocumentChunker`. The `chunkSize` and `chunkOverlap`
parameters on `IngestService` are forwarded to the token-based readers (plain text and PDF).

### MarkdownDocumentReader

Accepts a `File`. Reads lines, strips YAML front-matter (reusing the current logic), then
processes lines as follows:

- A heading line matching `^(#{1,6})\s+(.*)$` flushes the accumulated content buffer as a
  chunk, then updates the heading stack and current metadata.
- Non-heading lines are appended to the content buffer.
- At EOF, the remaining buffer is flushed.

Each flushed chunk's content is constructed as: the full ancestor heading path with original
`#` markers (one per ancestor, from outermost to innermost), each on its own line, followed by
the body text. Chunks with no heading context (pre-heading content) are emitted with the body
text only and no heading metadata keys set.

Heading metadata keys:
- `heading_title` (String): the immediate heading text
- `heading_level` (Int): 1–6
- `heading_path` (List\<String\>): all ancestor titles from root to current section, inclusive

Empty chunks (heading with no body, or whitespace-only content) are not emitted.

If the Markdown file produces no chunks via heading splitting (i.e. it contains no headings),
the reader falls back to token-based chunking using `TokenTextSplitter` with the configured
`chunkSize` and `chunkOverlap`.

### PlainTextDocumentReader and PdfDocumentReader

These are thin wrappers that absorb the logic currently in `DocumentLoader.loadTxt()`,
`DocumentLoader.loadPdf()`, and `DocumentChunker`. Each takes a `File` and returns
`List<Document>`. They do not set `source` metadata — that remains the responsibility of
`IngestService`.

### Source Metadata Responsibility

`source`, `mtime`, and `chunk_index` are set by `IngestService` after the reader returns,
exactly as today. No reader sets these fields. This keeps readers free of filesystem concerns.

### DocumentChunkInfo Extension

`DocumentChunkInfo` gains three optional fields: `headingTitle: String?`,
`headingLevel: Int?`, `headingPath: List<String>?`. These are populated by
`VectorStoreRepository.getChunksForFile()` from the stored chunk metadata when the keys are
present. Chunks from non-Markdown formats have `null` for all three fields.

### ShowCommand

In both text and JSON output modes, `ShowCommand` conditionally renders heading metadata
when `headingTitle` is non-null:
- Text mode: appended to the per-chunk summary line, e.g. `Chunk 1 — 312 chars, mtime: 1234567890, heading: ## Section 1.1`
- JSON mode: `headingTitle`, `headingLevel`, and `headingPath` keys included in the chunk object when non-null.

### Deletion

`DocumentLoader` and `DocumentChunker` are deleted. Their tests (`DocumentLoaderTest`,
`DocumentChunkerTest`) are replaced by tests for the new modules.

## Testing Decisions

**What makes a good test:** tests assert on externally observable behaviour — the content
and metadata of the returned `List<Document>` — not on internal state or implementation
details like how many lines were read or which regex matched. Tests use real `File` objects
backed by temp directories or classpath resources; no mocking of the reader internals.

### MarkdownDocumentReader

Unit tests covering:
- A file with no headings produces at least one chunk via token-based fallback
- Pre-heading content is emitted as a chunk without heading metadata
- Each heading section produces a separate chunk
- The full `#`-marked heading path is prepended to each chunk's content
- A heading with no body produces no chunk
- Sibling headings at the same level produce independent chunks with correct stacks
- `heading_title`, `heading_level`, and `heading_path` metadata are correct for nested sections
- YAML front-matter is stripped and does not appear in any chunk

### PlainTextDocumentReader

Unit tests covering:
- A `.txt` file produces at least one chunk
- No heading metadata is set on any chunk
- Content is not empty

### PdfDocumentReader

Unit tests covering:
- A `.pdf` file produces at least one chunk
- No heading metadata is set on any chunk

### DocumentReaderRegistry

Unit tests covering:
- Dispatches `.md` to `MarkdownDocumentReader`, `.txt` to `PlainTextDocumentReader`, `.pdf` to `PdfDocumentReader`
- `supports()` returns true for known extensions and false for unknown ones
- Throws `IllegalArgumentException` for unsupported extensions on `read()`

**Prior art:** `DocumentLoaderTest` and `DocumentChunkerTest` (to be deleted), and
`IngestServiceTest` for integration-level ingestion assertions. New unit tests should follow
the same pattern: construct the class under test directly, call its public method, assert on
the result.

## Out of Scope

- A PDF-to-Markdown reader: the registry is designed to accommodate it, but its implementation is a separate feature.
- Configurable chunking strategy per format via CLI flags (e.g. `--markdown-split-headings`): heading-based splitting for `.md` is the default and only mode.
- Parsing YAML front-matter fields as chunk metadata.
- Setext-style headings (`===` / `---` underlines): only ATX-style (`#`) headings are recognised.
- Inline code blocks that contain `#` characters at line start being misidentified as headings: not handled; content inside fenced code blocks is treated as body text.

## Further Notes

- The `heading_path` metadata is a `List<String>` serialised by Jackson as a JSON array in
  `SimpleVectorStore`. This is already supported by the existing serialisation infrastructure.
- The registry pattern is intentionally forward-compatible: a future `PdfToMarkdownDocumentReader`
  that converts a PDF to Markdown text and then delegates to `MarkdownDocumentReader` can be
  registered for `.pdf` in a single line, replacing the current `PdfDocumentReader` entry.
- The `DocumentChunkInfo.headingPath` field is `List<String>?` rather than `String?` to match
  the metadata type and avoid lossy serialisation/deserialisation of the path list.
