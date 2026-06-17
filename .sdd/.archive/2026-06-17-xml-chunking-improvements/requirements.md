## Problem Statement

XML files that use a single wrapper element around their list of records — such as a `<products>` container holding many `<product>` elements, or a `<suite>` holding many `<testcase>` elements — are not split at the correct boundaries. The converter only scans the document root's direct children for repeated siblings; any repeating element that is one or more levels deeper is missed, causing all records to be lumped into one large section instead of one section per record. Additionally, very small repeated elements each produce their own chunk, resulting in many tiny chunks that embed poorly. There is no way for a user to override the automatic boundary detection when the heuristic picks the wrong element type, and a malformed XML file can abort the entire ingest batch instead of being skipped with a warning.

## Solution

The XML-to-Markdown conversion step is improved in four targeted areas, all within the existing pipeline (XML → Markdown → heading-aware chunker):

1. **Multi-level boundary detection**: The converter recursively descends the XML tree to find the deepest level at which repeated siblings appear, rather than stopping at the root's direct children. A repeated element that has repeated children of its own is treated as a container, not a boundary, so the boundary is placed at the innermost repeated level.

2. **Small sibling merging**: Adjacent repeated elements whose text content is below a size threshold are batched together under a single Markdown section rather than emitted as individual sections. This prevents many tiny chunks from being stored and ensures the embedding for each chunk covers a meaningful amount of content.

3. **Boundary tag override**: A `--xml-boundary-tags` flag (and matching config key) lets the user specify which element names should be used as section boundaries, bypassing auto-detection entirely. This is the escape hatch for documents where the heuristic picks the wrong level.

4. **Malformed-file resilience**: If an XML file cannot be parsed, the reader logs a warning and returns an empty result rather than raising an exception that would abort the entire ingest batch.

## User Stories

1. As a developer ingesting a product catalog XML with a single `<products>` wrapper around many `<product>` elements, I want each product to become its own chunk, so that a search for a specific product name returns only that product's chunk.
2. As a developer ingesting a JUnit XML report with a `<testsuites>` root containing `<testsuite>` containers each holding many `<testcase>` elements, I want each test case to become its own chunk, so that a search for a failing test returns the relevant test case chunk.
3. As a developer ingesting a Maven POM where `<dependencies>` wraps repeated `<dependency>` elements, I want each dependency to become its own chunk even though dependencies are two levels below the root, so that queries about specific libraries return focused results.
4. As a developer ingesting a feed with many short `<item>` elements (such as an Android `strings.xml` where each string is a few words), I want small items to be batched into a single chunk rather than stored as dozens of near-empty chunks, so that retrieval quality is not degraded by sparse embeddings.
5. As a developer ingesting an XML schema where auto-detection picks the wrong boundary level, I want to pass `--xml-boundary-tags product` to the `ingest` command to force chunking at `<product>` elements, so that I can get correct chunking without changing the file.
6. As a developer previewing XML chunking with `to-chunks`, I want to pass the same `--xml-boundary-tags` flag that I will use at ingest time, so that the preview matches what will actually be stored.
7. As a developer previewing the converted text with `to-document`, I want to pass `--xml-boundary-tags` to see how the document text will look with my chosen boundary, so that I can verify the conversion before ingesting.
8. As a developer running a batch ingest over a directory containing both valid and malformed XML files, I want the malformed files to be skipped with a warning rather than aborting the batch, so that all valid files are still ingested.
9. As a developer, I want the ancestor path of a boundary element (e.g. `catalog > products > product`) to appear in every chunk's heading, so that retrieved chunks carry context about where in the document they came from.
10. As a developer, I want unique sibling elements at the same level as the boundary elements to be emitted as a preamble section, so that document-level metadata is still discoverable alongside the per-record chunks.
11. As a developer, I want the `--xml-boundary-tags` flag to accept multiple tag names, so that I can specify boundaries for documents that use more than one record element type.
12. As a developer, I want the existing XML formatting rules (attribute notation, namespace stripping, CDATA handling, comment exclusion) to remain unchanged, so that my existing ingested XML documents are not affected by this change.

## User Acceptance Tests

1. Given a catalog XML with a single `<products>` wrapper containing five `<product>` elements, when the file is ingested, then five separate chunks are produced, each with a heading that contains `products > product`.
2. Given a JUnit XML report where `<testsuites>` contains `<testsuite>` containers each holding `<testcase>` elements, when the file is ingested, then each `<testcase>` becomes its own chunk, and the heading contains the full path including `testsuite`.
3. Given an XML file containing fifty short `<string>` elements (each under ten words), when the file is ingested with default chunk size, then the number of chunks is substantially fewer than fifty.
4. Given an XML file where auto-detection would chunk at `<category>` level, when `--xml-boundary-tags product` is passed to `ingest`, then each `<product>` element becomes a chunk rather than each `<category>`.
5. Given the same XML file and `--xml-boundary-tags` flag, when the flag is passed to `to-chunks`, then the preview shows the same section boundaries that `ingest` would produce.
6. Given a directory containing one valid XML file and one malformed XML file, when `ingest` is run on the directory, then the valid file is ingested successfully, a warning is printed for the malformed file, and the command exits without error.
7. Given a flat config XML with no repeated siblings (e.g. `<config><host>…</host><port>…</port></config>`), when the file is ingested, then all content appears in a single chunk and existing behaviour is unchanged.
8. Given an XML file with a `<meta>` unique sibling and many `<record>` repeated siblings, when the file is ingested, then there is one preamble chunk containing the `<meta>` content and one chunk per `<record>`.

## Definition of Done

- All user acceptance tests pass manually.
- Multi-level boundary detection finds the deepest repeated level for nested XML structures.
- Small sibling merging reduces chunk count for documents with many small records.
- `--xml-boundary-tags` flag is accepted by `ingest`, `to-chunks`, and `to-document`; passing it overrides auto-detection.
- A malformed XML file in a batch ingest produces a warning message and is skipped; the batch continues.
- All new and existing automated tests pass (`./gradlew check`).
- No regression in existing XML ingestion behaviour for flat or single-level repeated structures.
- README updated to document the `--xml-boundary-tags` flag.

## Out of Scope

- StAX (streaming XML parser) migration — Jsoup is already a dependency and sufficient for current file sizes.
- Size-bounded recursive splitting of oversized individual records.
- Stable chunk identity derived from element XPath (chunk index is sufficient for re-ingest idempotency via content hash).
- Parent-link metadata for small-to-big retrieval expansion.
- Eval corpus additions.
- XSLT or schema-driven configuration.

## Further Notes

The merge threshold for small sibling batching is proportional to `chunkSize` (the token budget configured via `--chunk-size`). Elements whose estimated body text is below `chunkSize × 3` characters are candidates for merging; a batch is flushed once its accumulated text reaches `chunkSize × 12` characters. These multipliers approximate quarter-token and full-token budgets respectively without introducing a tokenizer dependency into the converter.

The `--xml-boundary-tags` flag supports both the kebab-case CLI spelling and the camelCase config-file spelling (`xmlBoundaryTags`), consistent with how other multi-word options are handled in this project.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

**Pipeline unchanged**: the existing XML → Markdown → `MarkdownDocumentReader` pipeline is preserved. All four improvements are confined to `XmlToMarkdownConverter` and `XmlDocumentReader`; no new chunker class is introduced.

**`XmlToMarkdownConverter` — interface addition**

Add `chunkSize: Int = 1000` constructor parameter and `boundaryTags: List<String> = emptyList()` parameter to the `convert()` method signature:

```kotlin
class XmlToMarkdownConverter(private val chunkSize: Int = 1000) {
    fun convert(xmlContent: String, boundaryTags: List<String> = emptyList()): String
}
```

**Multi-level detection algorithm**

Replace the flat direct-children scan with a recursive `emit(element, ancestorPath, sb)` function. Decision rule at each level:

1. Count direct children by local tag name; identify repeated tags (count ≥ 2).
2. If repeated tags exist: check whether any repeated child itself has repeated descendants (recursive `hasRepeatedDescendants()`). If yes → recurse into each repeated child (they are containers, not boundaries). If no → this is the boundary level; emit one section per repeated element and apply small sibling merging.
3. If no repeated tags exist: recurse into each child, passing the accumulated ancestor path. If no child has repeated descendants, emit the current element as a single fallback section.

Preamble (unique siblings at the boundary level's parent) is emitted only at the level where boundary elements are found.

**Small sibling merging**

After collecting body lines for each boundary element, compare accumulated character count to thresholds:
- Element is "small" if its body text is below `chunkSize * 3` characters.
- Batch is flushed when accumulated body text reaches `chunkSize * 12` characters, or when a non-small element is encountered.

Merged-batch Markdown format (Option A — flat body, shared heading):
```
## catalog > product
product[id=a]: text a
  name: Widget A
product[id=b]: text b
  name: Widget B
```
Each element in the batch starts with a `localName[attrs]` line so BM25 can still match on attributes.

**Boundary tag override**

When `boundaryTags` is non-empty, skip the auto-detect recursion entirely. Walk the whole tree and emit one section per element whose local name appears in `boundaryTags`, using the full ancestor path as the heading prefix. Small sibling merging still applies.

**`XmlDocumentReader` — malformed-file handling**

Wrap the entire `read()` body in `try/catch(Exception)`. On any exception, write a warning to `System.err` and return `emptyList()`. This mirrors the pattern used for encrypted Office files in `WordDocumentReader`.

Add `boundaryTags: List<String> = emptyList()` constructor parameter, forwarded to `XmlToMarkdownConverter(chunkSize).convert(content, boundaryTags)`.

**`DocumentReaderRegistry`**

Add `xmlBoundaryTags: List<String> = emptyList()` constructor parameter. Pass it to every `XmlDocumentReader(file, chunkSize, chunkOverlap, xmlBoundaryTags)` instantiation (extensions: `xml`, `svg`, `rss`, `atom`).

**`IngestService`**

Add `xmlBoundaryTags: List<String> = emptyList()` constructor parameter. Pass it into `DocumentReaderRegistry(chunkSize, chunkOverlap, passwords, xmlBoundaryTags)`.

**`IngestCommand`**

Add picocli option:
```kotlin
@Option(names = ["--xml-boundary-tags"], description = ["Element tag names to use as XML chunk boundaries. Repeat for multiple: --xml-boundary-tags product --xml-boundary-tags item. Overrides auto-detection."])
var xmlBoundaryTagsOption: List<String> = emptyList()
```
Resolve and pass to `IngestService`.

**`ToChunksCommand` and `ToDocumentCommand`**

Add the same `--xml-boundary-tags` option and pass it into the `DocumentReaderRegistry` they construct locally.

### Automated Testing Decisions

**What makes a good test here**: test the Markdown text output of `XmlToMarkdownConverter.convert()` and the `Document` list returned by `XmlDocumentReader.read()`. Do not assert on internal state or intermediate data structures. Assert on heading strings, body line strings, chunk count, and metadata keys — these are the externally observable outputs.

**`XmlToMarkdownConverter` — unit tests** (extend `XmlToMarkdownConverterTest`):
- Single wrapper: `<catalog><products><product>…</product><product>…</product></products></catalog>` → two headings containing `catalog > products > product`.
- Multi-category: `<catalog><category>…<product>…<product>…</category><category>…</category></catalog>` → boundary at `product`, heading contains `catalog > category > product`.
- Small sibling merge: ten `<item>one word</item>` elements with a small `chunkSize` → fewer headings than ten.
- Boundary tag override: `boundaryTags = ["product"]` on a mixed document → only `<product>` elements produce headings.
- Existing tests must continue to pass unchanged (no regression in flat/single-level cases).

Prior art: existing `XmlToMarkdownConverterTest` in `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/XmlToMarkdownConverterTest.kt`.

**`XmlDocumentReader` — unit tests** (extend `XmlDocumentReaderTest`):
- Malformed/unparseable content (e.g. a string that is not XML) → `read()` returns `emptyList()` without throwing.
- `boundaryTags` parameter is forwarded: a document where auto-detection would give one heading, but `boundaryTags` forces a different element → the resulting chunk headings reflect the override.

Prior art: existing `XmlDocumentReaderTest` in `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/XmlDocumentReaderTest.kt`.

**CLI wiring** — no new automated tests. The flag presence and correct plumbing is validated by the `XmlDocumentReader` unit tests above; end-to-end CLI testing is covered by the existing integration test infrastructure.
