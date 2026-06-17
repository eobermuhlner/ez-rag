## Task [01-xml-converter-fallback]

Create `XmlToMarkdownConverter` covering the fallback path: any XML document with no repeated siblings produces a single markdown section headed by the root element's local name. This task also establishes all XML-specific formatting rules — attribute notation, namespace handling, comment exclusion, CDATA inclusion — that all subsequent tasks build on.

### Implementation steps

- [x] Write failing test in `XmlToMarkdownConverterTest`: flat `<config><host>localhost</host></config>` → output contains `## config` and body line `host: localhost`
- [x] Create `XmlToMarkdownConverter` with `fun convert(xmlContent: String): String`; parse with Jsoup `Parser.xmlParser()`, skip Jsoup's synthetic `#root` wrapper
- [x] Implement fallback body walk: emit descendant lines with paths relative to root element (e.g. `host: localhost`, `db > host: localhost`)
- [x] Write and pass tests: attributes in `[key=value]` format on body lines; element with only attributes has no trailing `: `
- [x] Write and pass tests: `xmlns`/`xmlns:*` excluded; namespace prefixes stripped from element names
- [x] Write and pass tests: XML comment content excluded; CDATA text content included; whitespace-only text nodes skipped
- [x] Write and pass test: empty root element (no text, attributes, or children) → `convert()` returns empty string

### Acceptance criteria

- [x] Flat XML with only unique elements → output starts with `## <rootLocalName>` (namespace prefix stripped)
- [x] Body lines use paths relative to root: `host: localhost` not `config > host: localhost`; nested elements produce `db > host: localhost`
- [x] Attributes formatted as `[key=value]` appended to element name; element with only attributes produces no trailing `: ` suffix
- [x] `xmlns` and `xmlns:*` attributes do not appear anywhere in output; namespace URI strings absent
- [x] XML comment content does not appear in output; CDATA text content does appear
- [x] Empty root element (no text, no attributes, no children with content) → `convert()` returns empty string

### Quality gates

- [x] `./gradlew test --tests "*.XmlToMarkdownConverterTest"` passes
- [x] `./gradlew build` compiles without errors or warnings

---

## Task [02-xml-converter-sections]

Extend `XmlToMarkdownConverter` with repeated-sibling detection and preamble emission. When the root element has two or more direct children sharing the same tag name, each becomes its own `##` section. Unique siblings (appearing exactly once) are collected into a preamble section. All repeated groups at the same level are processed.

### Implementation steps

- [x] Write failing test: XML with two `<dependency>` children → output contains two `## project > dependencies > dependency` sections
- [x] Implement repeated-sibling detection on root's direct children: group by local tag name; any group with count ≥ 2 is repeated
- [x] Emit preamble `## <rootName>` section with unique children's descendant content as body (using root-relative paths), when at least one unique sibling exists
- [x] For each repeated group, emit one `## <fullPathFromRoot>[attrs]` section per element; body = descendant lines relative to that repeated element (heading path prefix stripped)
- [x] Write and pass test: preamble absent when all root children belong to repeated groups
- [x] Write and pass test: two distinct repeated groups at the same level (e.g. `<dependency>` and `<plugin>`) both produce sections
- [x] Write and pass test: attributes of a repeated element appear in its heading in `[key=value]` format

### Acceptance criteria

- [x] XML with ≥ 2 same-tag direct children of root → one `## <fullPath>[attrs]` section per repeated element
- [x] Body lines under a repeated section are relative to that element (`groupId: org.springframework`, not the full root-to-leaf path)
- [x] Unique siblings (appearing exactly once among root's children) appear in a single `## <rootName>` preamble section emitted before repeated sections
- [x] Preamble section is omitted when every root child belongs to a repeated group
- [x] Multiple distinct repeated groups at the same level all produce their own sets of sections
- [x] Attributes of a repeated element appear in its `##` heading in `[key=value]` format

### Quality gates

- [x] `./gradlew test --tests "*.XmlToMarkdownConverterTest"` passes with all task-01 tests still green
- [x] `./gradlew build` compiles without errors or warnings

---

## Task [03-xml-reader-rewrite]

Rewrite `XmlDocumentReader` to delegate entirely to `XmlToMarkdownConverter` → `MarkdownDocumentReader`, removing all internal walker logic. Update `XmlDocumentReaderTest` to assert the new section-heading output format and `heading_path`/`heading_title` metadata instead of the removed `xml_root` field.

### Implementation steps

- [x] Update `XmlDocumentReaderTest`: replace flat `path > element: text` assertions with section-heading and relative-body assertions; replace `xml_root` metadata assertions with `heading_path` / `heading_title`
- [x] Simplify `XmlDocumentReader.read()`: call `XmlToMarkdownConverter().convert(content)`, return empty list if blank, otherwise return `MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()`
- [x] Delete `walkElement`, `buildAttributeSuffix`, `localName` helpers and remove `TokenTextSplitter` import from `XmlDocumentReader`
- [x] Add new test: XML with repeated siblings → at least one chunk carries `heading_path` metadata containing the element path
- [x] Add new test: XML with no repeated siblings → at least one chunk with all leaf content present
- [x] Run full test suite; fix any regressions

### Acceptance criteria

- [x] `XmlDocumentReader` contains no XML walker or splitter logic — delegates entirely to `XmlToMarkdownConverter` and `MarkdownDocumentReader`
- [x] Chunks carry `heading_title` and `heading_path` metadata; `xml_root` key is absent from all chunk metadata
- [x] `source` metadata key absent from chunks returned by `XmlDocumentReader.read()` (existing contract preserved)
- [x] Empty XML document (`<root/>`) returns empty list
- [x] Large XML with 200 repeated same-tag elements and a small chunk size produces more than one chunk
- [x] `./gradlew test` passes with no failures across any test class

### Quality gates

- [x] `./gradlew test --tests "*.XmlDocumentReaderTest"` passes
- [x] `./gradlew test` passes with zero failures (no regressions in other formats)
- [x] `./gradlew build` compiles without errors or warnings
