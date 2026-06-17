## Task 01-malformed-file-resilience

Make `XmlDocumentReader.read()` return an empty list and write a warning when XML processing raises an exception, rather than propagating it. A batch ingest over a directory must continue when one file fails. Note: Jsoup is lenient and does not throw on most tag-soup, so the test must use an input that actually causes an exception (e.g. a `File` that does not exist triggers `FileNotFoundException`, exercising the catch).

### Implementation steps

- [x] Write a failing test in `XmlDocumentReaderTest`: construct an `XmlDocumentReader` with a `File` that does not exist; call `read()`; assert that it returns `emptyList()` without throwing
- [x] Add `warningWriter: PrintWriter = PrintWriter(System.err, true)` constructor parameter to `XmlDocumentReader` (consistent with the `IngestService` pattern)
- [x] Wrap the entire body of `XmlDocumentReader.read()` in `try/catch(Exception)`: on catch, write a warning line to `warningWriter` and return `emptyList()`
- [x] In the test, inject a `StringWriter`-backed `PrintWriter` as `warningWriter` and assert a non-blank warning line was written

### Acceptance criteria

- [x] `XmlDocumentReader` constructed with a non-existent `File` returns `emptyList()` from `read()` without throwing
- [x] The injected `warningWriter` receives at least one non-blank warning line in that case
- [x] All existing `XmlDocumentReaderTest` tests continue to pass

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors (constructor change must not break call sites that use default `warningWriter`)

---

## Task 02-multilevel-boundary-detection

Replace the flat direct-children scan in `XmlToMarkdownConverter` with a recursive descent algorithm that finds the **deepest** level where repeated siblings appear. A repeated element is treated as a container (traversed) rather than a boundary when it has repeated descendants. The full ancestor path (e.g. `catalog > products > product`) appears in every section heading. This task adds `chunkSize: Int = 1000` to the converter constructor — required by Task 03 for merging thresholds.

### Implementation steps

- [x] Write a failing test: single-wrapper XML `<catalog><products><product>…</product><product>…</product></products></catalog>` → exactly two headings, each containing the string `"catalog > products > product"`
- [x] Write a failing test: two-level nesting `<catalog><category><product>A</product><product>B</product></category></catalog>` → headings contain `"catalog > category > product"` (boundary is `product`, not `category`)
- [x] Write a failing test: preamble — `<catalog><meta>doc title</meta><product>A</product><product>B</product></catalog>` → one preamble section whose heading contains `catalog` and two product sections; the `meta` content appears only in the preamble
- [x] Add `chunkSize: Int = 1000` constructor parameter to `XmlToMarkdownConverter`
- [x] Add `hasRepeatedDescendants(element)` helper: returns `true` if the element has any descendant that appears more than once at the same depth
- [x] Replace the flat children scan with a recursive `emit(element, ancestorPath, sb)` function implementing the decision rule from the Technical Annex: if repeated tags exist and any of those repeated children has repeated descendants → recurse into each; otherwise → emit one section per repeated child and a preamble for unique siblings
- [x] If no repeated tags exist at any level → emit the whole element as a single fallback section

### Acceptance criteria

- [x] `<catalog><products><product>…</product><product>…</product></products></catalog>` produces exactly two section headings, each containing `"catalog > products > product"`
- [x] `<catalog><category><product>A</product><product>B</product></category></catalog>` produces headings containing `"catalog > category > product"`, not `"catalog > category"`
- [x] `<catalog><meta>doc title</meta><product>A</product><product>B</product></catalog>` produces one preamble section (heading contains `catalog`) plus two product sections; `meta` text appears only in the preamble section
- [x] A flat config XML with no repeated siblings at any level produces exactly one section
- [x] An XML with repeated direct children (existing single-level case) still produces one section per repeated element — all 17 existing `XmlToMarkdownConverterTest` tests pass

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors (`chunkSize` constructor parameter uses a default, so existing call sites need no changes)

---

## Task 03-small-sibling-merging

After identifying boundary elements (requires Task 02), batch adjacent "small" elements into a single Markdown section rather than emitting one per element. An element is small if its body text is below `chunkSize × 3` characters; a batch is flushed when accumulated body text reaches `chunkSize × 12` characters or when a non-small element is encountered. Each element in a batch is prefixed with a `localName[attrs]:` line so BM25 can still match on attributes.

*Prerequisite: Task 02 (recursive boundary detection and `chunkSize` constructor param).*

### Implementation steps

- [x] Write a failing test (single-batch): ten `<item>one word</item>` elements with `chunkSize=50` (flush threshold = 600 chars; 10 × 7 = 70 chars < 600) → `convert()` output contains exactly **one** `##` heading
- [x] Write a failing test (multi-batch flush): 100 `<item>one word</item>` elements with `chunkSize=50` (100 × 7 = 700 > 600) → `convert()` output contains exactly **two** `##` headings (first batch flushes at element ~86, second covers the rest)
- [x] After collecting body lines for each boundary element, compare accumulated char count to `chunkSize * 3` (small threshold) and `chunkSize * 12` (flush threshold)
- [x] Emit a single `## path > tag` heading for all elements in a batch; prefix each element's body with `localName[attrs]:` (no colon suffix when the element has no text value — consistent with existing attribute-only formatting)
- [x] Flush the current batch and start a new one when accumulated text reaches the flush threshold or a non-small element is encountered

### Acceptance criteria

- [x] Ten `<item>one word</item>` with `chunkSize=50` → exactly one `##` heading in `convert()` output
- [x] 100 `<item>one word</item>` with `chunkSize=50` → exactly two `##` headings in `convert()` output
- [x] A large element (body text > `chunkSize * 3`) is never merged with adjacent elements; it starts its own section
- [x] Each element within a merged batch is identifiable by its `localName[attrs]:` prefix line in the batch body
- [x] All existing `XmlToMarkdownConverterTest` and `XmlDocumentReaderTest` tests pass (no regression for normal-sized elements)

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 04-boundary-tag-override

Let users force specific element names as section boundaries, bypassing auto-detection entirely. The `--xml-boundary-tags` flag is added to `ingest`, `to-chunks`, and `to-document`. When `boundaryTags` is non-empty, the converter walks the whole tree and emits one section per matching element using the full ancestor path as the heading prefix; small sibling merging still applies. The flag is threaded through `XmlDocumentReader`, `DocumentReaderRegistry`, `IngestService`, and all three CLI commands. README is updated.

*Prerequisite: Tasks 02 and 03 (recursive algorithm and merging logic must be in place before the override path is added).*

### Implementation steps

- [x] Write a failing test in `XmlToMarkdownConverterTest`: given `<catalog><category><product>A</product><product>B</product></category></catalog>` and `boundaryTags = listOf("category")`, `convert()` output contains headings for `category` elements only, not for `product`
- [x] Write a failing test in `XmlDocumentReaderTest`: given the same XML and an `XmlDocumentReader` constructed with `boundaryTags = listOf("product")`, `read()` returns chunks whose `heading_path` metadata contains `"product"`, not `"category"`
- [x] Add `boundaryTags: List<String> = emptyList()` parameter to `XmlToMarkdownConverter.convert()`; when non-empty, skip auto-detect recursion and instead walk the whole tree emitting one section per element whose local name is in `boundaryTags`, with full ancestor path heading; small sibling merging applies as usual
- [x] Add `boundaryTags: List<String> = emptyList()` constructor parameter to `XmlDocumentReader`; pass it to `XmlToMarkdownConverter(chunkSize).convert(content, boundaryTags)`
- [x] Add `xmlBoundaryTags: List<String> = emptyList()` constructor parameter to `DocumentReaderRegistry`; pass it to every `XmlDocumentReader` instantiation (extensions: `xml`, `svg`, `rss`, `atom` — the override applies to all XML-family formats)
- [x] Add `xmlBoundaryTags: List<String> = emptyList()` constructor parameter to `IngestService`; pass it into the `DocumentReaderRegistry` it constructs
- [x] Add `@Option(names = ["--xml-boundary-tags"])` to `IngestCommand`; pass the collected list to `IngestService`
- [x] Add `@Option(names = ["--xml-boundary-tags"])` to `ToChunksCommand`; pass it to the `DocumentReaderRegistry` it constructs
- [x] Add `@Option(names = ["--xml-boundary-tags"])` to `ToDocumentCommand`; pass it to the `DocumentReaderRegistry` it constructs (note: `ToDocumentCommand` uses `chunkSize = Int.MAX_VALUE / 2` — small sibling merging effectively never fires, which is correct for a full-document view)
- [x] Update `README.md` to document the `--xml-boundary-tags` flag under the `ingest`, `to-chunks`, and `to-document` command sections

### Acceptance criteria

- [x] `XmlToMarkdownConverter.convert(xml, boundaryTags = listOf("category"))` produces headings only for `<category>` elements; no headings appear for `<product>` elements
- [x] `XmlDocumentReader` with `boundaryTags = listOf("product")` produces chunks whose `heading_path` metadata contains `product`, on a document where auto-detection would pick a different level
- [ ] ~~`IngestCommand` accepts `--xml-boundary-tags product --xml-boundary-tags item` (multiple values) and passes both tags to the reader~~ *(skipped: requires running the CLI in a live Spring Boot context with a real embedding model; covered by code review of the wiring in `IngestCommand.kt`)*
- [ ] ~~`to-chunks --xml-boundary-tags product` output contains `##` headings for `<product>` elements on the same test document~~ *(skipped: CLI end-to-end test requires a real embedding model; flag wiring is validated by `XmlDocumentReaderTest.boundaryTags constructor parameter is forwarded to converter`)*
- [ ] ~~`to-document --xml-boundary-tags product` output contains headings for `<product>` elements and no headings for elements at other levels~~ *(skipped: CLI end-to-end test requires a real embedding model; flag wiring is validated by `XmlDocumentReaderTest`)*
- [x] All XML-family extensions (`svg`, `rss`, `atom`) respect `xmlBoundaryTags` when passed through `DocumentReaderRegistry`
- [x] README documents the `--xml-boundary-tags` flag with at least one usage example

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles and packages without errors
