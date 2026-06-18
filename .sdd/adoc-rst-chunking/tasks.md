# Tasks: adoc-rst-chunking

## Task [01-adoc-reader]

Add `AsciiDocDocumentReader` and wire it into `DocumentReaderRegistry` so `.adoc` and `.asciidoc` files are chunked by heading hierarchy end-to-end.

The reader parses AsciiDoc heading syntax (`= Title`, `== Subtitle`, etc.), detects `----`-delimited code blocks and converts them to Markdown fenced blocks before passing body text to `SectionSplitter`, and falls back to `TokenTextSplitter` when no headings are found. Metadata keys `heading_title`, `heading_level`, and `heading_path` are identical to the Markdown format.

### Implementation steps

- [x] Write a failing `AsciiDocDocumentReaderTest` covering: single heading with correct `heading_title` and `heading_level`, sibling headings, nested `heading_path`, code block preservation (`----` delimiter), no-heading fallback, absence of `source` key in metadata, large section producing multiple chunks. Use the string constructor for content-based tests and `@TempDir`/file constructor for file-based tests, mirroring `MarkdownDocumentReaderTest`.
- [x] Verify the test compiles but fails (`./gradlew test --tests "*AsciiDocDocumentReaderTest"`).
- [x] Implement `AsciiDocDocumentReader` to make all tests pass. Pre-process `----`-delimited blocks by converting them to Markdown fenced code blocks (` ``` `) so `LayoutBlockParser` treats them as `FencedCodeBlock` units rather than horizontal rules.
- [x] Add `"adoc"` and `"asciidoc"` entries to `DocumentReaderRegistry`.
- [x] Add registry dispatch tests to `DocumentReaderRegistryTest` for `adoc` and `asciidoc` support and dispatch.

### Acceptance criteria

- [x] Given an AsciiDoc string with two level-1 headings (`= Heading One` / `= Heading Two`), two chunks are produced, each with `heading_title` matching its respective heading.
- [x] Given `= Level One`, `heading_level` is 1; given `== Level Two`, `heading_level` is 2.
- [x] Given an AsciiDoc string with a level-1 and a nested level-2 heading, the chunk under the level-2 heading carries `heading_path` containing both heading titles in order.
- [x] Given an AsciiDoc string containing a `----`-delimited code block, the code block content is contained within one chunk and not split.
- [x] Given an AsciiDoc string with no headings, at least one chunk is produced without `heading_title` in its metadata.
- [x] No chunk produced from an AsciiDoc file has `source` in its metadata.
- [x] Given an AsciiDoc section whose body exceeds the configured chunk size, multiple chunks are produced, each with correct `heading_title` and `heading_level` metadata.
- [x] `DocumentReaderRegistry.supports("adoc")` returns `true`.
- [x] `DocumentReaderRegistry.supports("asciidoc")` returns `true`.
- [x] `DocumentReaderRegistry.read(file)` on an `.adoc` file returns at least one chunk.
- [x] `DocumentReaderRegistry.read(file)` on an `.asciidoc` file returns at least one chunk.
- [x] Given a registry constructed with `chunkSize=50`, reading an `.adoc` file with a section body larger than 50 tokens produces more than one chunk for that section.

### Quality gates

- [x] `./gradlew test` passes with zero failures and zero errors.
- [x] `./gradlew build` completes without compile errors on the new files.

---

## Task [02-rst-reader]

Add `RstDocumentReader` and wire it into `DocumentReaderRegistry` so `.rst` files are chunked by heading hierarchy end-to-end.

RST heading detection identifies pairs of consecutive lines where the second is a run of a single punctuation character at least as long as the first. Heading level is assigned by first-seen underline character order across the file (not by character identity). Optional matching overlines are also supported. Code blocks via `.. code-block::` directive and via `::` paragraph-ending shorthand are preserved intact.

### Implementation steps

- [x] Write a failing `RstDocumentReaderTest` covering: single heading with `heading_title` and `heading_level`, sibling headings, nested `heading_path`, first-seen underline level order (e.g., `-` before `=` → `-` is level 1, `=` is level 2), overline+underline heading, `.. code-block::` directive preservation, `::` shorthand preservation, no-heading fallback, absence of `source` key, large section split.
- [x] Verify the test compiles but fails.
- [x] Implement `RstDocumentReader` to make all tests pass.
- [x] Add `"rst"` entry to `DocumentReaderRegistry`.
- [x] Add registry dispatch tests to `DocumentReaderRegistryTest` for `rst` support and dispatch.

### Acceptance criteria

- [x] Given an RST string with two sections separated by different underline characters, two chunks are produced each with correct `heading_title`.
- [x] Given an RST string where `-` underline appears before `=` underline, sections underlined with `-` have `heading_level` 1 and sections underlined with `=` have `heading_level` 2.
- [x] Given an RST string with an overline+underline heading (same punctuation character above and below the title text), that section is detected as a heading and produces a chunk with `heading_title`.
- [x] Given an RST string with a `.. code-block::` directive, the indented block content is contained within one chunk and not split.
- [x] Given an RST string with a `::` paragraph-ending shorthand, the following indented block content is contained within one chunk and not split.
- [x] Given an RST string with no headings, at least one chunk is produced without `heading_title` in its metadata.
- [x] No chunk produced from an RST file has `source` in its metadata.
- [x] Given an RST section whose body exceeds the configured chunk size, multiple chunks are produced for that section, each with correct `heading_title` metadata.
- [x] `DocumentReaderRegistry.supports("rst")` returns `true`.
- [x] `DocumentReaderRegistry.read(file)` on an `.rst` file returns at least one chunk.
- [x] Given a registry constructed with `chunkSize=50`, reading an `.rst` file with a section body larger than 50 tokens produces more than one chunk for that section.

### Quality gates

- [x] `./gradlew test` passes with zero failures and zero errors.
- [x] `./gradlew build` completes without compile errors on the new files.

---

## Task [03-mixed-dir-readme]

Verify end-to-end ingestion of a mixed-format directory and update documentation. This task validates that all four formats interoperate when ingested together, that `.asc` is explicitly excluded, and that the README and CHUNKING.md reflect the new supported formats.

### Implementation steps

- [x] Add a test (e.g., in `DocumentReaderRegistryTest` or a new `MixedFormatDirectoryIngestTest`) that writes one file per extension (`.adoc`, `.asciidoc`, `.rst`, `.md`) to a `@TempDir`, calls the ingestion pipeline on the directory, and asserts at least one chunk was produced from each file.
- [x] Add or extend a test asserting `DocumentReaderRegistry.supports("asc")` returns `false`.
- [x] Update `README.md`: add `.adoc`, `.asciidoc`, and `.rst` to the supported file types list.
- [x] Update `CHUNKING.md`: add AsciiDoc and RST sections documenting the heading-aware chunking strategy, consistent with the existing Markdown section format.

### Acceptance criteria

- [x] A directory containing exactly one `.adoc`, one `.asciidoc`, one `.rst`, and one `.md` file, when ingested, produces at least one chunk sourced from each of the four files.
- [x] `DocumentReaderRegistry.supports("asc")` returns `false`.
- [x] README.md lists `.adoc` (AsciiDoc) and `.rst` (reStructuredText) in the supported file formats section.
- [x] CHUNKING.md contains sections for AsciiDoc and RST describing the heading-aware chunking strategy.

### Quality gates

- [x] `./gradlew test` passes with zero failures and zero errors.
- [x] `./gradlew build` completes without compile errors.
