# Requirements: HTML Chunking Refactoring

## Problem Statement

HTML documents are chunked inconsistently compared to Markdown documents. The heading-based splitting logic is duplicated between the HTML and Markdown ingestion paths, so any improvement to chunking must be applied in two places. Several HTML constructs are also handled incorrectly or not at all: tables produce garbled text, navigation chrome (`<nav>`, `<footer>`, `<header>`, `<aside>`) pollutes chunk content, ordered list items are rendered as unordered bullets, `<blockquote>` and `<hr>` are silently dropped, and `<br>` collapses to a space rather than preserving the line break.

## Solution

Extract a dedicated HTML-to-Markdown converter that turns an HTML document into a well-formed Markdown string, then simplify the HTML document reader to delegate all chunking to the existing Markdown document reader. HTML and Markdown documents will then produce identically structured chunks through the same pipeline, and all conversion correctness fixes are applied in one place.

## User Stories

1. As a developer ingesting HTML documentation, I want `<h1>`–`<h6>` elements converted to the corresponding `#`-prefixed Markdown heading lines, so that retrieved chunks carry correct hierarchical context.
2. As a developer ingesting HTML documentation, I want any element whose CSS class contains `h1`–`h6` or `heading-1`–`heading-6` to be treated as the corresponding heading level, so that heading-like `<div>` and `<span>` elements in CMS-generated HTML are chunked correctly.
3. As a developer ingesting HTML documentation, I want `<table>` elements converted to Markdown pipe-table syntax, so that tables are treated as atomic blocks and never split mid-row.
4. As a developer ingesting HTML documentation, I want `<th>` cells to form the header row followed by a separator row, so that the Markdown table is syntactically valid.
5. As a developer ingesting HTML documentation, I want cells with `rowspan` or `colspan` attributes to have their text content rendered in their natural DOM position, so that all cell content is preserved for embedding even when the visual layout cannot be perfectly reproduced.
6. As a developer ingesting HTML documentation, I want `<nav>`, `<header>`, `<footer>`, and `<aside>` elements skipped entirely, so that navigation menus, breadcrumbs, and page footers do not pollute chunk content.
7. As a developer ingesting HTML documentation, I want `<ul>` list items rendered as `- text` bullets, so that unordered list semantics are preserved in the Markdown output.
8. As a developer ingesting HTML documentation, I want `<ol>` list items rendered as `1. text`, `2. text`, … with sequential numbering, so that ordered list semantics are preserved in the Markdown output.
9. As a developer ingesting HTML documentation, I want `<blockquote>` converted to `> `-prefixed lines, so that quoted content is structurally distinguishable in chunks.
10. As a developer ingesting HTML documentation, I want `<hr>` converted to `---`, so that horizontal rules trigger section boundaries in the Markdown chunker.
11. As a developer ingesting HTML documentation, I want `<br>` to produce a newline character rather than collapsing to a space, so that deliberate line breaks within paragraphs are preserved.
12. As a developer ingesting HTML documentation, I want `<pre>` elements wrapped in fenced code blocks, so that code content is preserved as an atomic block and not broken up mid-statement.
13. As a developer ingesting HTML documentation, I want `<img>` alt text emitted as plain text and omitted when the alt attribute is blank, so that image descriptions are available for embedding and retrieval without cluttering output with empty references.
14. As a developer ingesting HTML documentation, I want `<script>`, `<style>`, and `<noscript>` elements skipped, so that code and styling artefacts do not appear in chunks.
15. As a developer ingesting HTML documentation, I want `<main>`, `<div>`, `<section>`, `<span>`, and other container elements recursed into transparently, so that content nested inside structural wrappers is not lost.
16. As a developer ingesting HTML documentation, I want `<p>` elements to produce a paragraph followed by a blank line, so that paragraphs remain separated in the Markdown output.
17. As a developer ingesting HTML documentation, I want all produced chunks to carry a `page_title` metadata key matching the HTML `<title>` element, so that the source page is identifiable from any chunk.
18. As a developer ingesting HTML documentation, I want heading chunks to carry `heading_title`, `heading_level`, and `heading_path` metadata, so that chunk retrieval and display are consistent with Markdown-sourced chunks.
19. As a developer ingesting HTML documentation, I want HTML documents with no headings to fall back to token-based splitting, so that heading-free pages still produce usable chunks.
20. As a developer ingesting HTML documentation, I want the HTML ingestion path to produce chunks with the same structure and metadata as the Markdown ingestion path, so that downstream retrieval logic does not need to treat HTML and Markdown differently.

## User Acceptance Tests

1. Given an HTML page with `<h1>Introduction</h1><p>Some text</p>`, when the page is ingested, then one chunk is produced whose text begins with the word "Introduction" and whose `heading_title` metadata is "Introduction" and `heading_level` is 1.
2. Given an HTML page with `<div class="h2">Section Title</div><p>Content</p>`, when the page is ingested, then a chunk is produced with `heading_level` 2 and `heading_title` "Section Title".
3. Given an HTML page with `<div class="heading-3">Sub-section</div><p>Content</p>`, when the page is ingested, then a chunk is produced with `heading_level` 3.
4. Given an HTML page containing a `<table>` with header and data rows, when the page is ingested, then the chunk text contains a Markdown pipe table with a separator row, and the table is not split across multiple chunks.
5. Given an HTML page with a table containing `colspan` or `rowspan` attributes, when the page is ingested, then all cell text values appear somewhere in the chunk text.
6. Given an HTML page containing a `<nav>` element with navigation links, when the page is ingested, then none of the navigation link text appears in any chunk.
7. Given an HTML page containing a `<header>` element, when the page is ingested, then no header element text appears in any chunk.
8. Given an HTML page containing a `<footer>` element, when the page is ingested, then no footer element text appears in any chunk.
9. Given an HTML page containing an `<aside>` element, when the page is ingested, then no aside element text appears in any chunk.
10. Given an HTML page with `<ul><li>Apple</li><li>Banana</li></ul>`, when the page is ingested, then the chunk text contains `- Apple` and `- Banana`.
11. Given an HTML page with `<ol><li>First</li><li>Second</li><li>Third</li></ol>`, when the page is ingested, then the chunk text contains `1. First`, `2. Second`, and `3. Third`.
12. Given an HTML page with `<blockquote><p>A quote</p></blockquote>`, when the page is ingested, then the chunk text contains a line starting with `> A quote`.
13. Given an HTML page with an `<hr>` element between two sections, when the page is ingested, then the content before and after the rule appears in separate chunks.
14. Given an HTML page with `<p>Line one<br>Line two</p>`, when the page is ingested, then the chunk text contains "Line one" and "Line two" on separate lines.
15. Given an HTML page with a `<pre>` block containing code, when the page is ingested, then the chunk text contains a fenced code block (triple-backtick delimiters) wrapping the code.
16. Given an HTML page with `<img alt="A diagram of the system">`, when the page is ingested, then the chunk text contains "A diagram of the system".
17. Given an HTML page with `<img alt="">`, when the page is ingested, then no empty image placeholder appears in the chunk text.
18. Given an HTML page containing `<script>` or `<style>` elements, when the page is ingested, then the script or style source text does not appear in any chunk.
19. Given an HTML page where the `<title>` is "Getting Started", when the page is ingested, then every chunk carries a `page_title` metadata value of "Getting Started".
20. Given an HTML page containing no heading elements, when the page is ingested, then at least one chunk is produced containing the body text.
21. Given an HTML page where body content is nested inside `<div>` and `<section>` wrappers, when the page is ingested, then all body text appears in the produced chunks.

## Out of Scope

- Producing visually correct merged-cell tables for `rowspan`/`colspan` — cell text is preserved, but spanning layout is not reproduced.
- CSS class patterns beyond `h1`–`h6` and `heading-1`–`heading-6` (e.g. Bootstrap `.display-1`, Confluence-specific heading classes).
- Inline formatting: `<strong>`, `<em>`, `<b>`, `<i>` text is preserved but Markdown bold/italic syntax is not emitted.
- Link URLs from `<a>` tags — only link text is preserved.
- Definition lists (`<dl>`, `<dt>`, `<dd>`).
- `<figure>` / `<figcaption>` special handling.
- `<details>` / `<summary>` special handling.
- Changes to `MarkdownDocumentReader`, `SectionSplitter`, or `LayoutBlockParser`.

## Further Notes

- Jsoup is already declared as a project dependency (version 1.18.3); no new library needs to be added.
- The `page_title` metadata key is already produced by the existing `HtmlDocumentReader` and is preserved without change.
- After this refactoring, `HtmlDocumentReader` and `PdfDocumentReader` follow the same pattern: convert source format to Markdown, then delegate to `MarkdownDocumentReader`.

---

## Technical Annex
> Written against codebase as of: 2026-06-06

### Architectural Decisions

#### New class: `HtmlToMarkdownConverter`

Location: `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/HtmlToMarkdownConverter.kt`

Single-responsibility converter with one public entry point:

```kotlin
class HtmlToMarkdownConverter {
    fun convert(html: String): String
}
```

Internally it parses the HTML with Jsoup and recursively walks the DOM, emitting Markdown. All conversion rules live here; no conversion logic remains in `HtmlDocumentReader`.

**Element dispatch table (matched in priority order):**

| HTML element / condition | Markdown output |
|---|---|
| `<h1>`–`<h6>` | `# Title` … `###### Title` |
| Any element whose `class` attribute contains `h1`–`h6` or `heading-1`–`heading-6` | corresponding `#`-prefixed heading |
| `<table>` | pipe table; `<th>` cells → header row + `| --- |` separator row; `<td>` cells → data rows; `rowspan`/`colspan` ignored |
| `<ul>` | recurse children; each `<li>` → `- text` |
| `<ol>` | recurse children; each `<li>` → `1. text`, `2. text`, … (counter per list) |
| `<blockquote>` | prefix each non-blank child text line with `> ` |
| `<hr>` | `---` |
| `<pre>` | ` ``` `…` ``` ` fenced block |
| `<br>` | newline `\n` |
| `<img>` | alt attribute text; nothing when alt is blank |
| `<p>` | paragraph text + blank line |
| `<script>`, `<style>`, `<noscript>`, `<nav>`, `<header>`, `<footer>`, `<aside>` | skipped entirely |
| All other elements (`<div>`, `<section>`, `<main>`, `<span>`, …) | recurse into children |

#### Modified class: `HtmlDocumentReader`

Location: `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/HtmlDocumentReader.kt`

Refactored to a three-step pipeline:

1. Parse HTML with Jsoup; extract `<title>` text for the `page_title` metadata value.
2. Call `HtmlToMarkdownConverter().convert(html)` to obtain a Markdown string.
3. Construct `MarkdownDocumentReader(markdown, chunkSize, chunkOverlap)` and call `read()`.
4. Stamp `page_title` onto every `Document` returned by `MarkdownDocumentReader`.

The private methods `htmlBodyToMarkdown`, `processElement`, `splitByHeadings`, and `fallbackTokenSplit` are deleted from `HtmlDocumentReader`.

Constructor signature is unchanged:

```kotlin
class HtmlDocumentReader(
    private val html: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
)
```

#### No changes to downstream pipeline

`MarkdownDocumentReader`, `SectionSplitter`, and `LayoutBlockParser` are not modified.

### Automated Testing Decisions

**What makes a good test:** tests assert the observable output — the Markdown string produced by `HtmlToMarkdownConverter`, or the chunk text and metadata produced by `HtmlDocumentReader` — not internal implementation details such as which Jsoup methods are called or how the recursive walk is structured.

#### New test class: `HtmlToMarkdownConverterTest`

Location: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/HtmlToMarkdownConverterTest.kt`

Unit tests, one scenario per conversion rule plus combination scenarios. Prior art: `MarkdownDocumentReaderTest` for structure and naming conventions.

Scenarios to cover:
- `<h1>`–`<h6>` produce correct `#`-prefix lines
- Element with class `h2` produces `## heading`
- Element with class `heading-3` produces `### heading`
- Unknown class does not produce a heading line
- Simple `<table>` with `<th>` header and `<td>` data rows produces correct pipe table with separator row
- Table with `colspan`/`rowspan` — all cell text appears somewhere in output
- `<ul>` produces `- ` items
- `<ol>` produces numbered items (`1.`, `2.`, …)
- `<blockquote>` produces `> `-prefixed lines
- `<hr>` produces `---`
- `<pre>` produces fenced code block
- `<br>` inside `<p>` produces a newline character in the output
- `<img alt="description">` produces `description`; `<img alt="">` produces nothing
- `<nav>`, `<header>`, `<footer>`, `<aside>` produce no output
- `<script>` and `<style>` produce no output
- `<div>` and `<section>` pass through child content without adding markup

#### Extended test class: `HtmlDocumentReaderTest`

Location: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/HtmlDocumentReaderTest.kt`

Existing 11 tests remain and must continue to pass. New integration scenarios to add:

- Table in HTML body produces pipe-table text in a chunk, and the table is not split across chunks
- `<nav>` content does not appear in any chunk text
- `<ol>` items appear numbered in chunk text
- `<blockquote>` content appears with `> ` prefix in chunk text
- Element with class `h2` produces a chunk with `heading_level` 2
- `<img alt="…">` alt text appears in chunk text

Prior art: existing `HtmlDocumentReaderTest` at `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/HtmlDocumentReaderTest.kt`.
