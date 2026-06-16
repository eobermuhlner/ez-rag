# Tasks: xml-ingestion

## Task 01-xml-reader-complete

Create `XmlDocumentReader` — the full, production-ready reader. Parses XML using Jsoup's XML parser, walks the element tree depth-first, and emits one path-labelled line per meaningful node. All output and filtering decisions are implemented in this single task because `XmlDocumentReader` has no deployable intermediate state; every test cycle (red → green) is contained within this task.

**Output format:**
- Text-bearing element: `parent > child: text`
- Attributes (inline on path): `parent > child[attr=val]: text` or `parent > child[attr=val]` (no text)
- Namespace prefixes stripped from element names; `xmlns` / `xmlns:*` attributes excluded
- XML comments skipped; CDATA content treated as own text; whitespace-only text nodes skipped

**Chunking:** Flatten all labelled lines into a single string, pass through `TokenTextSplitter` with the configured `chunkSize` and `chunkOverlap`, strip the `source` metadata key injected by the splitter (same pattern as `PlainTextDocumentReader`). Add `xml_root` metadata to every chunk — value is the local name of the document's root element.

Tests are written one at a time (TDD: verify fail → implement → verify pass).

### Implementation steps

- [x] Write failing test: simple element with text produces `element: text` in chunk
- [x] Make test pass
- [x] Write failing test: nested elements produce full `parent > child: text` path in chunk
- [x] Make test pass
- [x] Write failing test: whitespace-only text node produces no chunk line
- [x] Write failing test: no chunk contains the `source` metadata key
- [x] Make both tests pass
- [x] Write failing test: element with attribute produces `element[attr=val]: text` notation
- [x] Write failing test: element with only attributes and no text produces `element[attr=val]` (no `: value` suffix)
- [x] Make both tests pass
- [x] Write failing test: `xmlns` attributes do not appear in any chunk text
- [x] Write failing test: namespace-prefixed element appears as local name only in chunk text
- [x] Make both tests pass
- [x] Write failing test: XML comment content does not appear in any chunk text
- [x] Write failing test: CDATA section content appears in chunk text
- [x] Make both tests pass
- [x] Write failing test: every chunk carries `xml_root` metadata equal to the document's root element local name
- [x] Make test pass
- [x] Write failing test: XML with only an empty root element returns empty list
- [x] Write failing test: large XML (many elements) produces at least 2 chunks
- [x] Make both tests pass
- [x] Run full test suite to confirm no regressions

### Acceptance criteria

- [x] `<project><name>myapp</name></project>` produces a chunk containing `project > name: myapp`
- [x] `<a><b><c>hello</c></b></a>` produces a chunk containing `a > b > c: hello`
- [x] `<bean id="ds" class="DS"/>` produces chunk text containing `bean[id=ds][class=DS]`
- [x] `<string name="key">Hello</string>` produces chunk text containing `string[name=key]: Hello`
- [x] `<context:component-scan base-package="com.example"/>` produces chunk text containing `component-scan[base-package=com.example]`; no chunk text contains `xmlns=`
- [x] XML comment content and whitespace-only text nodes do not appear in any chunk; CDATA section content does appear in a chunk
- [x] Every chunk produced from any XML document carries `metadata["xml_root"]` equal to the local name of the document's root element
- [x] `<root/>` (empty element, no text, no attributes) returns an empty list from `read()`; no chunk contains the `source` metadata key

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.XmlDocumentReaderTest"` passes with all tests green
- [x] `./gradlew test` (full suite) passes with zero failures

---

## Task 02-xml-registry-extensions

Extend `DocumentReaderRegistry` with five new file extensions:
- `xml`, `svg`, `rss`, `atom` → `XmlDocumentReader`
- `xhtml` → `HtmlDocumentReader` (same behavior as `.html`/`.htm`)

Add `supports()` and dispatch tests to `DocumentReaderRegistryTest` for each extension. Tests are written before the registry entries (TDD).

### Implementation steps

- [x] Write failing tests in `DocumentReaderRegistryTest`: `supports()` returns true for `xml`, `svg`, `rss`, `atom`, `xhtml`
- [x] Write failing dispatch tests: `registry.read(file)` returns non-empty list for a minimal temp file with text content for each of the five extensions
- [x] For xhtml: write failing test that `registry.read(file)` on an xhtml file with an `<h1>` heading returns at least one chunk carrying `heading_title` metadata
- [x] Add five entries to the `readers` map in `DocumentReaderRegistry`
- [x] Verify new tests pass
- [x] Run full test suite to confirm no regressions in existing formats

### Acceptance criteria

- [x] `registry.supports("xml")`, `registry.supports("svg")`, `registry.supports("rss")`, `registry.supports("atom")`, `registry.supports("xhtml")` all return `true`
- [x] `registry.read(file)` on a `.xml` file containing `<root><item>content</item></root>` returns a non-empty list
- [x] `registry.read(file)` on `.svg`, `.rss`, and `.atom` temp files with equivalent minimal content each return a non-empty list
- [x] `registry.read(file)` on an `.xhtml` file containing an `<h1>` heading returns at least one chunk with `heading_title` metadata matching the heading text
- [x] Full test suite passes — no regressions in txt, md, pdf, html, htm, csv, rtf, docx, doc, xlsx, xls, pptx, ppt

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.DocumentReaderRegistryTest"` passes with all tests green
- [x] `./gradlew test` (full suite) passes with zero failures

---

## Task 03-xml-docs-and-version

Update user-facing documentation and bump the project version. No logic changes.

### Implementation steps

- [x] Add `.xml`, `.svg`, `.rss`, `.atom`, `.xhtml` to the supported file types list in the `ingest` section of `README.md`
- [x] Bump `version` in `gradle.properties` from `0.11.0` to `0.12.0`

### Acceptance criteria

- [x] `gradle.properties` contains `version=0.12.0`
- [x] `README.md` lists `.xml`, `.svg`, `.rss`, `.atom`, `.xhtml` as supported ingest formats

### Quality gates

- [x] `./gradlew build` passes with zero test failures
