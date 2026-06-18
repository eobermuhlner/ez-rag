# AsciiDoc and reStructuredText Chunking

## Problem Statement

Users who maintain documentation in AsciiDoc (`.adoc`, `.asciidoc`) or reStructuredText (`.rst`) cannot ingest those files into ez-rag. Only Markdown is supported for structured document formats. This means that documentation corpora written in the Python ecosystem (where RST is standard) or in technical writing toolchains (where AsciiDoc is common) cannot be searched via the RAG tool without first converting all files to Markdown.

## Solution

Extend ez-rag's ingestion pipeline to support AsciiDoc and reStructuredText files, applying the same heading-aware section chunking strategy already used for Markdown. Each file is split into chunks that follow the document's heading hierarchy, so retrieved chunks carry heading path metadata that preserves context for the LLM.

## User Stories

1. As a developer, I want to ingest an AsciiDoc file so that its content is available for RAG search without manual conversion.
2. As a developer, I want to ingest a reStructuredText file so that its content is available for RAG search without manual conversion.
3. As a documentation author, I want AsciiDoc files with nested headings to produce chunks that carry the full heading path, so that retrieved chunks are self-contained and contextually meaningful.
4. As a documentation author, I want RST files with nested headings to produce chunks that carry the full heading path, so that retrieved chunks are self-contained and contextually meaningful.
5. As a developer, I want code blocks in AsciiDoc files to remain intact as single chunks, so that code examples are not split mid-block.
6. As a developer, I want code blocks in RST files to remain intact as single chunks, so that code examples are not split mid-block.
7. As a developer, I want AsciiDoc files with no headings to still produce chunks, so that flat documents are not silently skipped.
8. As a developer, I want RST files with no headings to still produce chunks, so that flat documents are not silently skipped.
9. As a developer, I want to ingest a directory containing a mix of `.adoc`, `.asciidoc`, `.rst`, and `.md` files so that all structured documents are ingested in one operation.
10. As a developer, I want `.asc` files to not be treated as AsciiDoc, so that PGP ASCII-armor files are not misprocessed.
11. As a developer, I want chunk size and overlap settings to apply consistently to AsciiDoc and RST files, so that the same tuning parameters govern all structured document formats.
12. As a developer, I want RST headings determined by underline-character order (not by which specific character is used), so that documents using any underline convention are chunked correctly.

## User Acceptance Tests

1. Given an AsciiDoc file with two level-1 headings each containing body text, when the file is ingested, then each chunk's metadata contains a `heading_title` matching its respective heading.
2. Given an AsciiDoc file with a level-1 heading containing a level-2 heading, when the file is ingested, then chunks under the level-2 heading carry a `heading_path` that includes both the level-1 and level-2 heading titles.
3. Given a reStructuredText file with two sections separated by distinct underline characters, when the file is ingested, then each chunk's metadata carries the correct `heading_title` for its section.
4. Given a reStructuredText file where the first underline character encountered is `-` and the second is `=`, when the file is ingested, then sections underlined with `-` are level 1 and sections underlined with `=` are level 2 (first-seen order, not character identity).
5. Given an AsciiDoc file containing a delimited code block (surrounded by `----` lines), when the file is ingested, then the code block content appears in a single chunk and is not split across multiple chunks.
6. Given an RST file containing a `.. code-block::` directive, when the file is ingested, then the code block content appears in a single chunk and is not split across multiple chunks.
7. Given an AsciiDoc file with no headings, when the file is ingested, then at least one chunk is produced containing the file's text content.
8. Given an RST file with no headings, when the file is ingested, then at least one chunk is produced containing the file's text content.
9. Given a directory containing `.adoc`, `.asciidoc`, `.rst`, and `.md` files, when the directory is ingested, then documents from all four file types appear in the search index.
10. Given a file named `key.asc`, when the directory is ingested, then the file is not processed as AsciiDoc (it falls through to binary detection or plain-text fallback).
11. Given any ingested AsciiDoc or RST chunk, when its metadata is inspected, then no `source` key is present.
12. Given an AsciiDoc or RST file whose single section exceeds the configured chunk size, when the file is ingested, then multiple chunks are produced for that section.

## Definition of Done

- AsciiDoc files with extensions `.adoc` and `.asciidoc` are ingested and chunked by heading hierarchy.
- RST files with extension `.rst` are ingested and chunked by heading hierarchy using first-seen underline character order for level assignment.
- Chunks carry `heading_title`, `heading_level`, and `heading_path` metadata consistent with the Markdown format.
- Code blocks in both formats are preserved as intact chunks (not split mid-block).
- Files with no headings fall back to token-based splitting and still produce chunks.
- The `.asc` extension is not registered as AsciiDoc.
- All user acceptance tests pass.
- Automated unit tests exist for `AsciiDocDocumentReader` and `RstDocumentReader`, covering heading hierarchy, metadata correctness, code block preservation, no-heading fallback, and absence of `source` metadata.
- No regression in existing Markdown, plain-text, or other supported format ingestion.
- README updated to reflect the two new supported file formats.

## Out of Scope

- Textile, Org-mode, MediaWiki, and other lightweight markup formats.
- Full block-type parity with the Markdown reader (tables, bullet lists, admonitions, block quotes as distinct block types).
- Rendering or converting AsciiDoc/RST to HTML or any other output format.
- Extracting AsciiDoc document attributes (`:author:`, `:doctype:`, etc.) as chunk metadata.
- The `.asc` extension (ambiguous with PGP ASCII-armor).
- Library-based (AsciidoctorJ) parsing.

## Further Notes

RST heading level assignment is order-dependent, not character-dependent. The first underline character seen in the file becomes level 1, regardless of which punctuation character it is. This is consistent with the RST specification and allows documents using any convention to be chunked correctly without configuration.

AsciiDoc's document title (`= Document Title`, level 0) is treated as a level-1 heading for chunking purposes, consistent with how Markdown's `#` is treated.

---

## Technical Annex
> Written against codebase as of: 2026-06-18

### Architectural Decisions

**New classes (both in package `ch.obermuhlner.ezrag.ingestion`):**

`AsciiDocDocumentReader`
- Constructors mirror `MarkdownDocumentReader`: `(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200)` and `(content: String, chunkSize: Int, chunkOverlap: Int)`.
- Heading regex: `^(=+)\s+(.+)$` — level = number of `=` characters.
- Code block detection: lines matching `^-{4,}$` toggle a fenced-block state; content between delimiters is emitted as a single `LayoutBlock`-equivalent unit before being passed to `SectionSplitter.splitSection(bodyText, headingPrefix)`.
- No front-matter stripping needed (AsciiDoc attribute lines start with `:` and are not confused with headings).
- Fallback when no headings found: delegate to `TokenTextSplitter` (same pattern as `MarkdownDocumentReader.fallbackTokenSplit`).
- Metadata keys: `heading_title` (String), `heading_level` (Int), `heading_path` (List<String>) — identical to Markdown output.

`RstDocumentReader`
- Constructors mirror `MarkdownDocumentReader`.
- Heading detection: two consecutive lines where line N is non-empty text and line N+1 is a run of a single punctuation character with length ≥ len(line N). Also supports optional overlines (same character above and below). Level is assigned by first-seen order of underline characters encountered top-to-bottom.
- Code block detection: lines matching `^\.\. code-block::` or a paragraph ending with `::` followed by an indented block; content is emitted as a single intact unit.
- Fallback when no headings found: delegate to `TokenTextSplitter`.
- Metadata keys: same as above.

**Modified class:**

`DocumentReaderRegistry` — add three entries to the `readers` map:
```kotlin
"adoc"     to { file -> AsciiDocDocumentReader(file, chunkSize, chunkOverlap).read() },
"asciidoc" to { file -> AsciiDocDocumentReader(file, chunkSize, chunkOverlap).read() },
"rst"      to { file -> RstDocumentReader(file, chunkSize, chunkOverlap).read() },
```

**`SectionSplitter` is unchanged.** It already accepts `bodyText: String` and `headingPrefix: String` with no format coupling; both new readers use it directly.

**Block parsing for the minimal first version:** Rather than going through `LayoutBlockParser` (which is Markdown-specific), each new reader pre-processes its content before calling `SectionSplitter`:
- Detects delimited code blocks and emits them as opaque text units.
- All other content is passed through as-is; `SectionSplitter` will treat it as paragraphs via `LayoutBlockParser.Paragraph`.

Alternatively, the new readers may pass the body text directly to `SectionSplitter` after replacing format-specific code-block delimiters with Markdown-style fenced blocks (` ``` `), leveraging the existing `LayoutBlockParser.FencedCodeBlock` detection. This is an implementation-level decision left to the developer.

### Automated Testing Decisions

**What makes a good test here:** Tests should assert on the externally observable outputs — chunk text content and metadata — not on internal parsing state. Specifically: number of chunks produced, `heading_title`/`heading_level`/`heading_path` values, absence of `source` key, and whether code block text is split or intact.

**Test classes:**

`AsciiDocDocumentReaderTest` (new, mirrors `MarkdownDocumentReaderTest`):
- File and string constructors both tested.
- Heading detection: single heading, sibling headings, nested headings, `heading_path` correctness.
- Code block preservation: a `----`-delimited block must not be split.
- No-heading fallback: produces at least one chunk.
- `source` key absent from all chunk metadata.
- Large section split: a section exceeding chunk size produces multiple chunks.

`RstDocumentReaderTest` (new):
- Same coverage as above, adapted for RST syntax.
- First-seen heading level assignment: a document where `-` appears before `=` must assign level 1 to `-` sections and level 2 to `=` sections.
- Overline+underline heading: a section with matching overline and underline is treated as a heading.
- `.. code-block::` directive: content is not split mid-block.

**Prior art:** `MarkdownDocumentReaderTest` at `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/MarkdownDocumentReaderTest.kt` — follow its structure (JUnit 5, AssertJ, `@TempDir` for file-based tests, string constructor for content-based tests).
