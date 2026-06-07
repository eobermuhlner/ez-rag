# PRD: Structure-Aware PDF Ingestion via pdf-markdown

## Problem Statement

When ingesting PDF files, ez-rag currently uses Spring AI's `PagePdfDocumentReader`, which performs plain text extraction and discards all structural information (headings, tables, lists, bold/italic formatting). Chunks produced this way have no semantic boundaries — a chunk may begin mid-sentence, span two unrelated topics, or fragment a table across multiple documents. This degrades both retrieval precision and the quality of LLM answers generated from retrieved context.

## Solution

Replace the plain-text PDF extraction pipeline with a deterministic, rule-based PDF-to-Markdown converter sourced from the sibling `pdf-markdown` project. The converter uses per-glyph positional and typographic metadata to reconstruct document structure (headings, lists, tables, bold/italic, code blocks). The resulting Markdown is then fed through the existing `MarkdownDocumentReader`, which already splits on heading boundaries and preserves `heading_title`, `heading_level`, and `heading_path` metadata per chunk. The outcome is semantically coherent chunks aligned with document structure, with no new configuration knobs required.

## User Stories

1. As a user ingesting a PDF with chapter headings, I want each chunk to correspond to a logical section, so that retrieved context is coherent and not fragmented mid-topic.
2. As a user ingesting a PDF with nested headings (`##`, `###`), I want each chunk's metadata to reflect its full heading path, so that I can understand where in the document a retrieved chunk comes from.
3. As a user ingesting a PDF with tables, I want table content to be preserved in Markdown table syntax within a chunk, so that the LLM can reason over tabular data correctly.
4. As a user ingesting a PDF with bold or italic emphasis, I want that formatting to be preserved in chunk text, so that the LLM can identify emphasis when answering questions.
5. As a user ingesting a PDF, I want the ingestion to succeed without any API keys, network access, or LLM calls, so that PDF ingestion is fully offline and deterministic.
6. As a user ingesting a PDF that has no detectable headings, I want the ingestion to fall back to token-based chunking, so that plain-text PDFs are handled gracefully without errors.
7. As a user ingesting a PDF with repeated headers or footers (page numbers, document titles), I want those elements stripped from chunk content, so that repeated boilerplate does not pollute retrieved context.
8. As a user ingesting a multi-column PDF layout, I want columns to be read in the correct reading order, so that chunk text is not garbled by interleaved column content.
9. As a user, I want no breaking change to the `ez-rag ingest` CLI interface, so that my existing ingestion workflows continue to work unchanged.
10. As a developer, I want the new PDF pipeline to be tested using the existing `sample.pdf` test resource, so that regressions are caught automatically.
11. As a developer, I want `MarkdownDocumentReader` to also accept a Markdown string (not only a file), so that the PDF reader can convert in memory and reuse the existing heading-aware splitting logic without a temp file.
12. As a developer, I want the pdf-markdown conversion classes to live in a clearly separated sub-package, so that copied code is easily identifiable and does not pollute the main ingestion namespace.

## Implementation Decisions

### Modules to build or modify

**1. `ingestion.pdf` sub-package (new)**
Copy the following source files from the `pdf-markdown` project, adjusting their package declarations to `ch.obermuhlner.ezrag.ingestion.pdf`:
- `PositionalTextStripper` — PDFBox subclass that extracts per-glyph positional and typographic metadata
- `TextElement` — data class representing a single text run with position, font size, weight, and style
- `DeterministicMarkdownConverter` — rule-based converter from `TextElement` lists to Markdown
- `ConversionOptions` — conversion presets (only `RAG` preset is used; `READABLE` is retained as it is part of the copied file)
- `RuleTuning` — fine-tuning knobs for the conversion rules
- `PdfMarkdown` — public API facade; copy only `toMarkdown()` and its required internal helpers (`extractFilteredPageElements`, `upgradeFont`, `detectRepeatedElements`, `mergeElements`). Omit `toXml`, `toXmlRaw`, `toImages`, `toImageFiles`, and any methods that reference `PdfImageConverter` or `ConfigLoader`.

**2. `MarkdownDocumentReader` (modified)**
Add a secondary constructor (or overloaded `read` method) that accepts a Markdown `String` directly rather than a `File`. Extract the core parsing logic into a private method shared by both entry points. The `File`-based constructor is kept as-is for backward compatibility with `.md` file ingestion.

**3. `PdfDocumentReader` (replaced)**
Replace the Spring AI `PagePdfDocumentReader`-based implementation entirely with:
```
PdfMarkdown.toMarkdown(file, options = ConversionOptions.RAG)
  → Markdown String
  → MarkdownDocumentReader(markdownString, chunkSize, chunkOverlap).read()
  → List<Document>
```
Constructor signature stays the same (`file`, `chunkSize`, `chunkOverlap`). `ConversionOptions.RAG` is hardcoded; no new CLI flag is introduced.

**4. `build.gradle.kts` (modified)**
- Add an explicit dependency on `org.apache.pdfbox:pdfbox:3.0.3`.
- Remove the `spring-ai-pdf-document-reader` dependency once `PdfDocumentReader` no longer imports from it.

### Conversion mode
`ConversionOptions.RAG` is used unconditionally. This preset strips decorative elements, normalises whitespace, and is specifically designed for downstream vector-store ingestion. No per-project YAML tuning (`ConfigLoader`) is included.

### No new metadata fields
The chunks produced by the new pipeline carry the same metadata schema already defined by `MarkdownDocumentReader` (`heading_title`, `heading_level`, `heading_path`). The `IngestService` continues to add `source`, `mtime`, and `chunk_index` on top of these, unchanged.

### Fallback behaviour
If a PDF produces Markdown with no detectable headings (e.g. a scanned document where OCR yields plain prose), `MarkdownDocumentReader`'s existing `fallbackTokenSplit` path handles chunking via `TokenTextSplitter`. No additional fallback logic is needed.

## Testing Decisions

**What makes a good test:** Tests verify the externally observable behaviour of a module through its public interface. They do not inspect internal state, assert on specific class names used internally, or couple to the number of sub-steps in a pipeline. A test is good if it still passes after a valid refactoring of internals.

**Modules to test:**

- **`MarkdownDocumentReader` (String constructor)**: Add tests that call `MarkdownDocumentReader` with a Markdown string directly, asserting that heading-aware splitting, metadata, and fallback behaviour work identically to the file-based path. Prior art: the existing `MarkdownDocumentReaderTest`, which uses `@TempDir` and string literals written to temp files — the new tests will be structurally identical but pass the string directly.

- **`PdfDocumentReader`**: The existing `PdfDocumentReaderTest` uses `sample.pdf` from test resources and asserts that at least one non-empty chunk is produced and no `source` metadata is present. Extend these tests to also assert that at least one chunk carries `heading_title` metadata (since `sample.pdf` contains headings), confirming that structure is now preserved. The existing sample PDF test resources (`sample.pdf`, plus eval PDFs) serve as integration fixtures without mocking.

**Not tested in isolation**: The copied `ingestion.pdf` classes (`PositionalTextStripper`, `DeterministicMarkdownConverter`, etc.) are tested indirectly via `PdfDocumentReaderTest`. They are mature, tested code in their source project; unit-testing them again in ez-rag would be redundant.

## Out of Scope

- YAML-based `RuleTuning` configuration via `.pdf-markdown.yaml` files.
- PDF image extraction or rendering.
- Exposing `ConversionOptions` (RAG vs READABLE) as a CLI flag.
- Updating the `re-ingest` command to re-extract previously ingested PDFs with the new pipeline (though it will benefit automatically for any PDF re-ingested after this change).
- Publishing `pdf-markdown` as a standalone Maven artifact and depending on it by coordinates.
- Any changes to non-PDF ingestion paths (HTML, plain text, Markdown files).

## Further Notes

- Both projects are authored by the same developer; there is no licensing concern with copying source files.
- `PdfMarkdown.kt` contains references to `PdfImageConverter` (for `toImages`/`toImageFiles`) which is not copied. The trimmed copy of `PdfMarkdown` must omit those methods to avoid a compilation error.
- PDFBox 3.0.3 may already be on the runtime classpath transitively via `spring-ai-pdf-document-reader`. Making it an explicit dependency in `build.gradle.kts` is still correct practice once we own the PDFBox usage directly.
- `mergeElements` is an internal helper referenced by `PdfMarkdown.kt`; its definition must be located (likely in `PositionalTextStripper.kt` or as a top-level function in the `pdfmarkdown` package) and included in the copy.
