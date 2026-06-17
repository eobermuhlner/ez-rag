## Problem Statement

XML files ingested into the knowledge base are currently processed by a flat-line walker that emits one `path > element: text` line per text-bearing node, then splits the result with a token-based splitter that has no awareness of the XML document's structure. This means a chunk may span content from multiple unrelated elements — for example, mixing `<dependency>` entries from a Maven POM, or mixing `<bean>` definitions from a Spring configuration — making retrieval less precise and reducing the usefulness of the stored chunks.

## Solution

The XML ingestion pipeline is restructured so that structurally significant boundaries in the XML document drive chunk boundaries. A repeated-sibling detection heuristic identifies the natural list elements of the document (e.g. `<dependency>`, `<bean>`, `<item>`, `<testcase>`) and converts each one into a self-contained markdown section. The resulting markdown is then chunked using the same heading-aware `SectionSplitter` pipeline already used for Markdown, Word, PDF, and HTML documents, giving XML the same retrieval quality as other formats.

## User Stories

1. As a developer, I want each Maven POM `<dependency>` to be stored in its own chunk, so that a query for "spring-core version" retrieves a chunk that contains exactly the spring-core dependency block.
2. As a developer, I want each Spring XML `<bean>` definition to be stored in its own chunk, so that a query for a bean's class or property value retrieves a focused, relevant result.
3. As a developer, I want each JUnit XML `<testcase>` to be stored in its own chunk, so that I can search for a specific test by name and retrieve its result without unrelated test entries.
4. As a developer, I want each RSS `<item>` or Atom `<entry>` to be stored in its own chunk, so that a query about a specific article returns only that article's content.
5. As a developer, I want each Android `strings.xml` `<string>` element to be stored with its `name` attribute visible in the chunk, so that a query for a resource key name retrieves the correct string value.
6. As a developer, I want document-level metadata from a POM (such as `<groupId>`, `<artifactId>`, `<version>`) to be stored in a separate preamble chunk, so that queries about the project identity return the correct values.
7. As a developer, I want flat XML files with no repeated siblings (such as a simple config file) to be ingested as a single section, so that they are still searchable without requiring list-like structure.
8. As a developer, I want chunk metadata to identify which XML section a chunk came from, so that I can understand the context of a retrieved result.
9. As a developer, I want namespace prefixes stripped from element names in ingested content, so that queries use the local element name rather than a namespace-qualified form.
10. As a developer, I want `xmlns` namespace declarations excluded from ingested content, so that namespace URI strings do not pollute the index.
11. As a developer, I want XML comments excluded from ingested content, so that commented-out configuration does not appear in search results.
12. As a developer, I want CDATA section content included in ingested content, so that embedded text data is searchable.
13. As a developer, I want SVG, RSS, and Atom files to benefit from the same structured chunking as `.xml` files, since they are all XML-based formats.
14. As a developer, I want element attributes to appear in the section heading, so that querying an element's identity attribute (such as `name`, `id`, or `artifactId`) retrieves the correct section.

## User Acceptance Tests

1. Given a Maven `pom.xml` with multiple `<dependency>` entries, when the file is ingested, then each dependency appears in a separate chunk and no chunk mixes content from two different dependencies.
2. Given a Maven `pom.xml` with project-level metadata (`<groupId>`, `<artifactId>`, `<version>`), when the file is ingested, then a preamble chunk containing those fields exists and is retrievable by querying the project name or version.
3. Given a Spring XML configuration file with multiple `<bean>` definitions, when the file is ingested, then each bean's class name and property values appear together in a single chunk.
4. Given an Android `strings.xml` with multiple `<string name="...">` entries, when the file is ingested, then searching for a resource key name retrieves the chunk containing that key's value.
5. Given a JUnit XML result file with multiple `<testcase>` entries, when the file is ingested, then each test case appears in a separate chunk and is retrievable by test name.
6. Given an RSS feed with multiple `<item>` elements, when the file is ingested, then each item appears in a separate chunk and is retrievable by its title or description content.
7. Given a flat XML config file with no repeated siblings, when the file is ingested, then all its content is ingested as a single section and is searchable.
8. Given an XML file with namespace-prefixed elements such as `<context:component-scan>`, when the file is ingested, then the chunk text contains `component-scan` and does not contain `context:component-scan`.
9. Given an XML file with `xmlns` declarations, when the file is ingested, then no chunk text contains `xmlns=` or any namespace URI.
10. Given an XML file containing XML comments, when the file is ingested, then no chunk text contains the comment content.
11. Given an XML file with CDATA sections, when the file is ingested, then the CDATA text content is present in the ingested chunks.
12. Given an XML file where a `<dependency>` element has an `artifactId` attribute, when the file is ingested, then the chunk heading contains `[artifactId=...]`.

## Definition of Done

- All user acceptance tests pass.
- All pre-existing XML ingestion tests continue to pass (updated where necessary to reflect new chunk structure and metadata).
- No regression in ingestion of any other supported file format.
- SVG, RSS, and Atom files ingested via `XmlDocumentReader` benefit from the same structured chunking.
- Documentation updated if user-facing ingestion behaviour has changed.

## Out of Scope

- Recursive repeated-sibling detection: elements nested inside a repeated element are treated as flat body content, not further sectioned.
- Configurable heading depth or user-selectable section boundary.
- Generating markdown tables or bullet lists from XML content (body content uses plain `relative-path: value` lines).
- Changes to any non-XML ingestion pipeline.

## Further Notes

The repeated-sibling heuristic is intentionally simple and document-type-agnostic: it requires no knowledge of POM, Spring, RSS, or any other schema. Any XML element whose parent contains two or more siblings with the same tag name is treated as a section boundary. This covers the common list-of-items pattern shared by all major XML document types targeted by this tool.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New module: `XmlToMarkdownConverter`

A new class `XmlToMarkdownConverter` in package `ch.obermuhlner.ezrag.ingestion` is the deep module for this feature. It encapsulates all XML structure analysis and markdown generation logic. Its interface is intentionally simple:

```kotlin
class XmlToMarkdownConverter {
    fun convert(xmlContent: String): String
}
```

The converter performs the following steps:

1. Parse the XML string with Jsoup using `Parser.xmlParser()`.
2. Identify the document root element (skip Jsoup's synthetic `#root` wrapper).
3. Walk the root's direct children, partitioning them into:
   - **Repeated groups**: child tag names appearing ≥ 2 times (all groups are processed).
   - **Unique children**: tag names appearing exactly once.
4. Emit a **preamble section** (if any unique children exist) with the root element local name as heading:
   ```
   ## <rootName>
   <childLocalName>: <text>
   <childLocalName>[attr=val]: <text>
   ```
5. For each repeated group, emit one section per element with the full path + attributes as the heading and relative-path body lines:
   ```
   ## <rootName> > <parentName> > <childName>[attr=val]
   <grandchildLocalName>: <text>
   <grandchildLocalName>[attr=val]: <text>
   ```
   Body lines use paths **relative to the repeated element** (heading prefix is stripped). Nested repeated siblings are **not** detected recursively — all descendant content is flattened.
6. **Fallback** (no repeated siblings anywhere): emit a single section with the root element as heading and all descendant content as body.
7. Attribute format: `[key=value]` per attribute, `xmlns` and `xmlns:*` excluded. Namespace prefixes stripped from element names (`localName`). These rules are identical to the existing `XmlDocumentReader` conventions.

#### Modified module: `XmlDocumentReader`

`XmlDocumentReader` is simplified to a thin orchestrator:

```kotlin
class XmlDocumentReader(
    private val content: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    fun read(): List<Document> {
        val markdown = XmlToMarkdownConverter().convert(content)
        if (markdown.isBlank()) return emptyList()
        return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
    }
}
```

The `xml_root` metadata field that the current implementation adds to every chunk is **removed**. Chunks will instead carry `heading_title`, `heading_level`, and `heading_path` metadata from `MarkdownDocumentReader`, which provides richer structural context. All existing tests that assert on `xml_root` must be updated.

#### Integration point: `DocumentReaderRegistry`

No changes required. The registry already maps `xml`, `svg`, `rss`, and `atom` extensions to `XmlDocumentReader`. These mappings remain unchanged.

#### Markdown heading format

All section headings use `##` level. Since the full XML path is embedded in the heading text, there is no need for heading hierarchy in the generated markdown. `MarkdownDocumentReader` will split at each `##` boundary and prepend the heading to each sub-chunk produced by `SectionSplitter`.

### Automated Testing Decisions

**What makes a good test here:** tests should assert on the text and metadata of the returned `Document` list — never on internal state of the converter or the structure of intermediate strings. Tests should use inline XML strings rather than fixture files wherever possible, to make the intent of each test immediately visible.

#### `XmlToMarkdownConverterTest` (new, unit tests)

The deep module to test. Tests should cover:
- Single repeated group (e.g. two `<dependency>` elements) → correct number of `##` sections in output.
- Preamble section: unique siblings at root level appear under the root heading before the repeated sections.
- Fallback: no repeated siblings → single `##` section with root element as heading.
- Attributes appear in heading with `[key=value]` format.
- `xmlns` attributes excluded from heading.
- Namespace prefixes stripped from element names in both heading and body.
- Body lines use relative paths (heading prefix stripped).
- XML comments do not appear in output.
- CDATA content appears in output.
- Multiple repeated groups at the same level all produce sections.
- Whitespace-only text nodes do not produce body lines.

Prior art: `XmlDocumentReaderTest` for the attribute/namespace/comment/CDATA conventions; `MarkdownDocumentReaderTest` for the section/heading testing style.

#### `XmlDocumentReaderTest` (updated, unit tests)

Most existing tests need to be updated to reflect the new output format:
- Tests asserting `path > element: text` flat-line format → updated to assert the section heading and relative body line formats.
- Tests asserting `xml_root` metadata → updated to assert `heading_path` or `heading_title` metadata.
- The large-XML chunking test and the empty-document test remain structurally valid with minor assertion updates.
- New test: a document with repeated siblings produces one chunk per repeated element (or group of elements fitting within the token budget).
- New test: a document with no repeated siblings produces at least one chunk containing all leaf content.
