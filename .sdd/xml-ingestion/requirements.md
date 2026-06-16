## Problem Statement

When a user ingests a directory or knowledge base that contains XML files — Maven POMs, Spring configuration, Android resource files, RSS feeds, SVG diagrams, JUnit result files, and many others — the tool currently processes them as raw plain text, including all markup tags. This means queries like "what version of spring-core is used?" or "what is the database host?" return chunks polluted with XML syntax (`<artifactId>`, `</dependency>`, `xmlns=...`) rather than clean, searchable content. In the worst case, the tag noise dominates the embedding vector and the relevant text is never retrieved.

## Solution

A dedicated XML reader extracts the human-readable content from XML files by walking the element tree and emitting one labelled line per text-bearing node. Each line carries the full path from the document root to the element, making the element name part of the indexed text. Attributes are included alongside the element path. The result is a flat, naturally-searchable text representation that is then chunked with the same token-size settings as all other content types.

## User Stories

1. As a developer, I want `pom.xml` to be ingested with element names preserved, so that I can search for "spring-core version" and get the correct version string from the dependencies block.
2. As a developer, I want Spring XML configuration files to be ingested with bean class names and property values indexed, so that I can search for which bean implements a given interface or holds a given property value.
3. As a developer, I want Android resource XML files (`strings.xml`, `layout/*.xml`) to be ingested with the `name` attribute of each element indexed, so that I can search for string resource keys by name.
4. As a developer, I want Ant `build.xml` files to be ingested with target names and task attributes indexed, so that I can search for build targets and understand what they do.
5. As a developer, I want JUnit XML result files to be ingested so that I can search for failing test names and their error messages.
6. As a developer, I want `.csproj` and similar MSBuild project files to be ingested with package references and property values indexed, so that I can search for NuGet dependencies and build settings.
7. As a developer, I want Kubernetes and other infrastructure XML configuration files to be ingested with full element paths, so that I can search for resource names, namespaces, and configuration values.
8. As a user, I want RSS and Atom feed files to be ingested with item titles, descriptions, and links indexed, so that I can search across feed content.
9. As a user, I want SVG files to be ingested with embedded text labels, titles, and descriptions indexed, so that diagram annotations are searchable.
10. As a user, I want XHTML files to be ingested using the same HTML reader as `.html` files, so that XHTML web pages are chunked by heading structure rather than raw element paths.
11. As a user, I want XML namespace prefixes to be stripped from element names in the indexed text, so that I can search for `bean` rather than needing to know the namespace prefix used in a specific file.
12. As a user, I want `xmlns` namespace declaration attributes to be excluded from indexed text, so that schema URLs do not pollute search results.
13. As a user, I want XML attributes such as `id`, `class`, `name`, and `type` to be indexed alongside the element they belong to, so that I can search for bean IDs, resource names, and type declarations.
14. As a user, I want each chunk produced from an XML file to carry metadata identifying the root element of the document, so that I can understand what kind of XML document a search result came from.
15. As a user, I want XML ingestion to respect the same `--chunk-size` and `--chunk-overlap` settings as all other content types, so that search result granularity is consistent across my knowledge base.
16. As a user, I want XML comments to be excluded from indexed content, so that commented-out configuration does not appear in search results.
17. As a user, I want whitespace-only content (indentation, line breaks between elements) to be excluded from indexed text, so that formatting does not produce empty or noise chunks.
18. As a user, I want CDATA sections within XML elements to be treated as plain text content, so that embedded data blocks are searchable.
19. As a user, I want ingesting a directory that contains a mix of XML and non-XML files to work without any special configuration, so that I can point the tool at a project root and have everything indexed.
20. As a user, I want a clear warning and graceful skip if an XML file is malformed beyond recovery, rather than an error that halts ingestion of the whole batch.

## User Acceptance Tests

1. Given a `pom.xml` containing a dependency with `groupId` `org.springframework` and `version` `6.1.0`, when the file is ingested, then a search for "springframework version" returns a chunk containing both `org.springframework` and `6.1.0`.
2. Given a Spring XML configuration file containing a `<bean id="dataSource" class="com.example.DataSource"/>`, when the file is ingested, then a search for "dataSource" or "DataSource" returns a chunk containing both values.
3. Given an Android `strings.xml` containing `<string name="app_name">Hello World</string>`, when the file is ingested, then a search for "app_name" returns a chunk containing "Hello World".
4. Given an RSS feed containing an `<item>` with `<title>Latest Release</title>`, when the file is ingested, then a search for "Latest Release" returns a result from that feed.
5. Given an SVG file containing `<title>System Architecture</title>`, when the file is ingested, then a search for "System Architecture" returns a chunk from that SVG.
6. Given an XHTML file containing an `<h2>Installation</h2>` heading followed by a paragraph, when the `.xhtml` file is ingested, then chunks carry `heading_title` metadata matching the heading text — the same behaviour as `.html` files.
7. Given an XML file with namespace-prefixed elements such as `<context:component-scan base-package="com.example"/>`, when the file is ingested, then the indexed text contains `component-scan` and `com.example` but does not contain `context:` as a prefix or any `xmlns` URLs.
8. Given an XML file whose root element is `project`, when it is ingested, then every chunk produced from that file carries the metadata field `xml_root` with value `project`.
9. Given an XML file large enough to exceed the configured chunk size, when it is ingested, then multiple chunks are produced and each chunk individually fits within the chunk size limit.
10. Given an XML file containing only XML comments and whitespace, when it is ingested, then zero chunks are produced and no error is thrown.
11. Given a directory containing a `pom.xml`, a `README.md`, and a `data.csv`, when the directory is ingested, then all three files are ingested without error and the total files-ingested count equals three.
12. Given an XML file containing a CDATA section with embedded text, when the file is ingested, then the CDATA content appears in at least one chunk.

## Definition of Done

- All user acceptance tests pass.
- `.xml`, `.svg`, `.rss`, and `.atom` files are ingested via the XML reader; `.xhtml` files are ingested via the HTML reader.
- Element paths are indexed with namespace prefixes stripped and `xmlns` attributes excluded.
- Non-namespace attributes are included in the indexed text alongside their parent element.
- Every chunk produced from an XML file carries the `xml_root` metadata field.
- XML comments and whitespace-only text nodes are excluded from all chunks.
- CDATA content is included as plain text.
- Chunk size and overlap settings are respected.
- No regression in existing ingestion formats (txt, md, pdf, html, htm, csv, rtf, docx, doc, xlsx, xls, pptx, ppt).
- README updated to include the new supported extensions.
- Automated tests cover the XML reader in isolation and registry dispatch for each new extension.

## Out of Scope

- Schema-aware parsing for specific XML vocabularies (DITA, DocBook, OpenDocument, OPML).
- XSLT transformation support.
- Configurable element-path depth limit.
- Record-level chunking (treating repeated sibling elements as chunk boundaries, analogous to CSV rows).
- Namespace-aware disambiguation (two elements with the same local name but different namespaces are treated identically).
- Indexing XML processing instructions (`<?xml-stylesheet ...?>`).
- Support for `.xsl`, `.xslt`, `.wsdl`, `.dtd`, or other XML-based infrastructure formats — these fall through to the existing plain-text fallback.

## Further Notes

The element-path format (`parent > child: value`) is intentionally human-readable and optimised for BM25 keyword search, where element names form part of the query vocabulary. Semantic embedding quality for highly structured XML (many short labelled lines) may be lower than for prose documents; this is an acceptable trade-off for the source-code-project use case where keyword search dominates.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New class: `XmlDocumentReader` (`ch.obermuhlner.ezrag.ingestion`)

A self-contained reader with two constructors mirroring the pattern of `HtmlDocumentReader`:

```kotlin
class XmlDocumentReader(
    private val content: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) :
        this(file.readText(Charsets.UTF_8), chunkSize, chunkOverlap)

    fun read(): List<Document>
}
```

**Parsing:** `Jsoup.parse(content, "", Parser.xmlParser())` — already a transitive dependency (`org.jsoup:jsoup:1.18.3`), no new library needed.

**Tree walk algorithm:**
- Walk every element recursively using depth-first traversal.
- Build the element path as a `List<String>` of local names (no namespace prefix — use `element.tagName()` after stripping `prefix:` with `substringAfter(':')`, or Jsoup's `localName()` if available in the version in use).
- For each element, first emit non-`xmlns` attributes in bracket notation: `path[attr=val]`.
- Then emit `ownText()` (direct text nodes only, excluding children) if non-blank after trimming: `path: text`.
- Recurse into child elements.
- Skip `Comment` nodes entirely.

**Output format examples:**
```
project > groupId: org.springframework
project > dependencies > dependency > artifactId[scope=test]: junit
beans > bean[id=dataSource][class=com.example.DataSource]
svg > title: Architecture Overview
rss > channel > item > title: Latest Release
```

Note: elements with only attributes and no text content emit the bracketed attribute line with no trailing colon or value.

**Metadata:** After flattening to a single text string, pass to `TokenTextSplitter` (same constructor call as `PlainTextDocumentReader`). Add `xml_root` to every chunk's metadata — value is the local name of `doc.root().children().first()` (the document root element, skipping the synthetic Jsoup `#root` wrapper).

**Chunking:** Flatten all path-labelled lines into a single `String`, construct a single `Document`, pass through `TokenTextSplitter` with the configured `chunkSize` and `chunkOverlap`. Strip the `source` metadata key injected by the splitter (same pattern as `PlainTextDocumentReader`).

#### Modified: `DocumentReaderRegistry`

Add to the `readers` map in `DocumentReaderRegistry`:

```kotlin
"xml"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap).read() },
"svg"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap).read() },
"rss"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap).read() },
"atom"  to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap).read() },
"xhtml" to { file -> HtmlDocumentReader(file, chunkSize, chunkOverlap).read() },
```

#### Version bump

This is a `feat:` commit — bump the minor version in `gradle.properties` (`0.11.0` → `0.12.0`).

#### README update

Add `.xml`, `.svg`, `.rss`, `.atom`, `.xhtml` to the supported file types list in the `ingest` section.

### Automated Testing Decisions

**What makes a good test:** Tests should assert on the text content and metadata of the returned `Document` list given a controlled XML string or temp file input. Do not test the internal tree-walk state or intermediate string buffers — only the observable output of `read()`.

**`XmlDocumentReaderTest`** (new, unit — no repository or embedding model required):
- Prior art: `HtmlDocumentReaderTest` (same package, same pattern — construct reader with inline string, call `read()`, assert on `doc.text` and `doc.metadata`)
- Cases to cover:
  - Simple element with text → path-labelled line appears in chunk text
  - Nested elements → full path appears in chunk text
  - Attribute on element → bracket notation appears in chunk text
  - `xmlns` attribute → does not appear in any chunk text
  - Namespace prefix on element → prefix stripped, local name only in path
  - Whitespace-only text node → not emitted as a chunk line
  - XML comment → does not appear in any chunk text
  - CDATA section → content appears in chunk text
  - `xml_root` metadata on all produced chunks
  - Empty XML document (root element only, no content) → empty list returned
  - Large XML producing multiple chunks → chunk count greater than one; each chunk respects size

**`DocumentReaderRegistryTest`** (modify, unit):
- Add `supports()` assertions for `xml`, `svg`, `rss`, `atom`, `xhtml`
- Add dispatch tests: write a minimal valid XML temp file for each extension, call `registry.read(file)`, assert non-empty document list
- Prior art: existing `read dispatches html file and produces at least one chunk` tests in `DocumentReaderRegistryTest`
