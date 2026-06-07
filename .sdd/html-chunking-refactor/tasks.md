# Tasks: HTML Chunking Refactoring

## Task [01-core-converter-and-wiring]

Creates `HtmlToMarkdownConverter` with fundamental element conversions, then refactors `HtmlDocumentReader` to delegate to it together with `MarkdownDocumentReader`. After this task all existing `HtmlDocumentReaderTests` pass through the new pipeline and the private conversion methods are gone from `HtmlDocumentReader`.

Elements covered by the converter in this task:
- `<h1>`–`<h6>` → `#`–`######` prefixed heading lines
- `<p>` → paragraph text followed by two newlines (blank line)
- `<br>` → newline character `\n`
- `<pre>` → triple-backtick fenced code block
- `<img>` → alt attribute text; nothing when alt is blank
- `<ul>` / `<li>` → `- text` bullet items
- `<script>`, `<style>`, `<noscript>` → skipped entirely
- All other elements → recurse transparently into children

`HtmlDocumentReader` refactored to three steps: (1) parse with Jsoup and extract `page_title`, (2) call `HtmlToMarkdownConverter.convert(html)`, (3) pass the resulting Markdown string to `MarkdownDocumentReader` and stamp `page_title` onto every returned `Document`.

### Implementation steps

- [x] Write `HtmlToMarkdownConverterTest` with one test per element rule listed above (h1–h6, p, br, pre, img with alt, img with blank alt, ul/li, script/style/noscript skip, container recursion)
- [x] Create `HtmlToMarkdownConverter` implementing all rules until all converter tests pass
- [x] Refactor `HtmlDocumentReader.read()` to use `HtmlToMarkdownConverter` + `MarkdownDocumentReader`
- [x] Delete private methods `htmlBodyToMarkdown`, `processElement`, `splitByHeadings`, `fallbackTokenSplit` from `HtmlDocumentReader`
- [x] Verify all 8 existing `HtmlDocumentReaderTest` tests pass

### Acceptance criteria

- [x] `HtmlToMarkdownConverterTest`: `<h1>` through `<h6>` each produce the correctly `#`-prefixed heading line
- [x] `HtmlToMarkdownConverterTest`: `<p>Hello</p>` produces `Hello` followed by a blank line (two `\n` characters)
- [x] `HtmlToMarkdownConverterTest`: `<p>Line one<br>Line two</p>` produces "Line one" and "Line two" on separate lines in the output string
- [x] `HtmlToMarkdownConverterTest`: `<pre>code here</pre>` produces output containing ` ``` ` before and after the code text
- [x] `HtmlToMarkdownConverterTest`: `<img alt="A diagram">` produces `A diagram`; `<img alt="">` produces no text
- [x] `HtmlToMarkdownConverterTest`: `<ul><li>Apple</li><li>Banana</li></ul>` produces lines `- Apple` and `- Banana`
- [x] `HtmlToMarkdownConverterTest`: `<script>`, `<style>`, `<noscript>` elements each produce empty output
- [x] `HtmlToMarkdownConverterTest`: `<div><p>text</p></div>` produces the same output as `<p>text</p>` (transparent recursion)
- [x] All 8 existing `HtmlDocumentReaderTest` tests pass
- [x] `HtmlDocumentReader` contains no private methods named `htmlBodyToMarkdown`, `processElement`, `splitByHeadings`, or `fallbackTokenSplit`

### Quality gates

- [x] No compiler warnings or errors (`./gradlew compileKotlin compileTestKotlin`)
- [x] All tests pass (`./gradlew test`)

---

## Task [02-nav-chrome-filtering]

Extends `HtmlToMarkdownConverter` to skip `<nav>`, `<header>`, `<footer>`, and `<aside>` elements and all of their descendants. Content appearing only inside these elements must not appear in any produced chunk.

### Implementation steps

- [x] Add `HtmlToMarkdownConverterTest` scenarios for each of the four skipped elements, using unique sentinel text that appears exclusively inside the element under test
- [x] Add the four tags to the skip list in `HtmlToMarkdownConverter`
- [x] Add `HtmlDocumentReaderTest` integration scenarios: one per element, each asserting the sentinel text is absent from all chunk texts

### Acceptance criteria

- [x] `HtmlToMarkdownConverterTest`: `<nav>` containing unique link text produces no output
- [x] `HtmlToMarkdownConverterTest`: `<header>` containing unique text produces no output
- [x] `HtmlToMarkdownConverterTest`: `<footer>` containing unique text produces no output
- [x] `HtmlToMarkdownConverterTest`: `<aside>` containing unique text produces no output
- [x] `HtmlDocumentReaderTest`: page where navigation text appears only inside `<nav>` — that text is absent from all chunk texts
- [x] `HtmlDocumentReaderTest`: page where footer text appears only inside `<footer>` — that text is absent from all chunk texts

### Quality gates

- [x] No compiler warnings or errors
- [x] All tests pass

---

## Task [03-lists-blockquote-hr]

Extends `HtmlToMarkdownConverter` to support ordered lists with sequential numbering, blockquote prefix lines, and horizontal rules that act as section boundaries.

- `<ol>` items → `1. text`, `2. text`, … with a counter that starts at 1 for each `<ol>` instance and resets between sibling lists
- `<blockquote>` → each non-blank line of child content prefixed with `> ` (child `<p>` elements are rendered before the prefix is applied)
- `<hr>` → `---` (when fed to `MarkdownDocumentReader` this becomes a section boundary and splits the surrounding content into separate chunks)

### Implementation steps

- [x] Add `HtmlToMarkdownConverterTest` scenarios for `<ol>` (single list, two adjacent lists to verify counter reset), `<blockquote>`, and `<hr>`
- [x] Update `HtmlToMarkdownConverter`: make `<ol>` pass a counter to `<li>` processing so items are numbered sequentially; handle `<blockquote>` with `> ` prefix; handle `<hr>` as `---`
- [x] Add `HtmlDocumentReaderTest` integration scenarios: `<ol>` items appear numbered in chunk text; `<blockquote>` content appears with `> ` prefix; content on either side of `<hr>` appears in separate chunks

### Acceptance criteria

- [x] `HtmlToMarkdownConverterTest`: `<ol><li>First</li><li>Second</li><li>Third</li></ol>` produces `1. First`, `2. Second`, `3. Third`
- [x] `HtmlToMarkdownConverterTest`: two adjacent `<ol>` lists each produce items starting from `1.` (counter resets between lists)
- [x] `HtmlToMarkdownConverterTest`: `<blockquote><p>A quote</p></blockquote>` produces a line starting with `> A quote`
- [x] `HtmlToMarkdownConverterTest`: `<hr>` produces `---`
- [x] `HtmlDocumentReaderTest`: `<ol>` items appear as `1.`, `2.`, … in chunk text
- [x] `HtmlDocumentReaderTest`: `<blockquote>` content appears with `> ` prefix in chunk text
- [x] `HtmlDocumentReaderTest`: body content before an `<hr>` and body content after an `<hr>` appear in separate chunks

### Quality gates

- [x] No compiler warnings or errors
- [x] All tests pass

---

## Task [04-table-conversion]

Extends `HtmlToMarkdownConverter` to convert `<table>` elements to Markdown pipe-table syntax. `<th>` cells form the header row; a separator row (`| --- |` per column) follows the header row. `<td>` cells form data rows. For `rowspan` or `colspan` cells the text is rendered at the cell's natural DOM position; spanning layout is not reproduced. Tables are treated as atomic blocks and must not be split mid-row.

### Implementation steps

- [x] Add `HtmlToMarkdownConverterTest` scenarios: simple table with `<th>` header and `<td>` data rows; table with `colspan`; table with `rowspan`
- [x] Implement `<table>` handling in `HtmlToMarkdownConverter` (walk `<tr>` rows, detect `<th>` vs `<td>`, emit pipe syntax with separator after header row)
- [x] Add `HtmlDocumentReaderTest` integration scenarios: pipe-table separator present in chunk text; table with at most 5 rows/3 columns using `chunkSize = 2000` is not split across chunks

### Acceptance criteria

- [x] `HtmlToMarkdownConverterTest`: table with two `<th>` headers and one `<td>` data row produces a pipe table with a `| --- | --- |` separator row between header and data
- [x] `HtmlToMarkdownConverterTest`: table cell with `colspan="2"` — the cell's text value appears in the converter output
- [x] `HtmlToMarkdownConverterTest`: table cell with `rowspan="2"` — the cell's text value appears in the converter output
- [x] `HtmlDocumentReaderTest`: HTML page with a `<table>` produces at least one chunk whose text contains `| --- |`
- [x] `HtmlDocumentReaderTest`: a table with 5 rows and 3 columns, read with `chunkSize = 2000`, produces a single chunk containing all cell values (table is not split)

### Quality gates

- [x] No compiler warnings or errors
- [x] All tests pass

---

## Task [05-css-class-heading-detection]

Extends `HtmlToMarkdownConverter` to recognise heading-level CSS classes on any element. An element whose `class` attribute contains any token matching `h1`–`h6` or `heading-1`–`heading-6` is treated as the corresponding heading level. Elements with unrelated classes are not treated as headings and continue to recurse transparently.

### Implementation steps

- [x] Add `HtmlToMarkdownConverterTest` scenarios: class `h2`, class `heading-3`, class with both a heading token and other tokens, class that does not match any pattern
- [x] Add heading-class detection to `HtmlToMarkdownConverter` element dispatch (checked after explicit tag match, before default recursion)
- [x] Add `HtmlDocumentReaderTest` integration scenario: page with `<div class="h2">Section Title</div><p>Content</p>` produces a chunk with `heading_level` 2 and `heading_title` "Section Title"

### Acceptance criteria

- [x] `HtmlToMarkdownConverterTest`: `<div class="h2">Title</div>` produces `## Title`
- [x] `HtmlToMarkdownConverterTest`: `<span class="heading-3">Sub</span>` produces `### Sub`
- [x] `HtmlToMarkdownConverterTest`: element with class `"h2 extra-class"` produces `## Title` (heading class wins alongside other classes)
- [x] `HtmlToMarkdownConverterTest`: element with an unrelated class does not produce a line starting with `#`
- [x] `HtmlDocumentReaderTest`: `<div class="h2">Section Title</div><p>Content</p>` produces a chunk with `heading_level` 2 and `heading_title` "Section Title"

### Quality gates

- [x] No compiler warnings or errors
- [x] All tests pass
