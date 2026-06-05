# Tasks: pdf-markdown-ingestion

## Task 01-string-markdown-reader

Extend `MarkdownDocumentReader` to accept a Markdown string directly — in addition to a `File` — so that callers can pass in-memory Markdown content without a temp file. Heading-aware splitting, heading metadata (`heading_title`, `heading_level`, `heading_path`), and token-based fallback must behave identically for string input as for file input.

### Implementation steps

- [x] Write failing tests: construct `MarkdownDocumentReader` with a Markdown string and `chunkSize`/`chunkOverlap`, assert heading chunks with correct metadata are produced
- [x] Extract the core parsing logic from the `File`-based path into a shared private method that accepts raw Markdown content as a `String`
- [x] Add a String-based constructor/overload that delegates to the shared method

### Acceptance criteria

- [x] `MarkdownDocumentReader` can be instantiated with a Markdown string plus `chunkSize`/`chunkOverlap`
- [x] A string with headings produces chunks whose `heading_title`, `heading_level`, and `heading_path` metadata are correct
- [x] A string with no headings falls back to token-based splitting and produces at least one non-empty chunk with no `heading_title` in metadata
- [x] All existing `File`-based `MarkdownDocumentReaderTest` tests pass unchanged

### Quality gates

- [x] `./gradlew compileKotlin` produces no errors or warnings
- [x] `./gradlew test` passes

---

## Task 02-pdf-markdown-classes

Copy the five pdf-markdown source files into a new `ingestion.pdf` sub-package with adjusted package declarations, add PDFBox 3.0.3 as an explicit dependency, and verify that all copied classes compile as a unit within ez-rag. No production code uses these classes yet — the task's only goal is a green `compileKotlin`.

### Implementation steps

- [x] Add `org.apache.pdfbox:pdfbox:3.0.3` to `build.gradle.kts` as an explicit dependency
- [x] Create the `ingestion.pdf` sub-package directory
- [x] Copy `PositionalTextStripper.kt` (contains the `mergeElements` top-level function and several additional top-level helpers used by the converter), `TextElement.kt`, `DeterministicMarkdownConverter.kt`, `ConversionOptions.kt` (contains both `ConversionOptions` and `RuleTuning`) — adjust package declarations to the new sub-package
- [x] Copy a trimmed `PdfMarkdown.kt` into the sub-package containing only `toMarkdown()` and its required internal helpers (`extractFilteredPageElements`, `upgradeFont`, `detectRepeatedElements`); omit `toXml`, `toXmlRaw`, `toImages`, `toImageFiles` and their internal helpers (`convertPdfToXml`, `fontSizeToCssKeyword`, `escapeXml`) since they reference `PdfImageConverter` which is not copied

### Acceptance criteria

- [x] `./gradlew compileKotlin` passes with the 5 copied files in place
- [x] `PdfMarkdown.toMarkdown(file, options = ConversionOptions.RAG)` resolves and compiles when called from `ingestion` package code

### Quality gates

- [x] No unresolved references in any of the 5 copied files
- [x] `./gradlew compileKotlin` produces no errors or warnings

---

## Task 03-structure-preserving-pdf-ingestion

Replace `PdfDocumentReader`'s Spring AI `PagePdfDocumentReader` implementation with a pipeline that converts the PDF to Markdown via `PdfMarkdown.toMarkdown(file, ConversionOptions.RAG)` and then splits it using the String-based `MarkdownDocumentReader`. Chunks produced from PDFs with headings now carry `heading_title`, `heading_level`, and `heading_path` metadata. Remove the `spring-ai-pdf-document-reader` dependency.

Note: `sample.pdf` in test resources contains only a single body-text run with no font-size variation — heading detection will never fire on it and is not a useful fixture for structure assertions. The eval PDF `eval/complex-pdf/machine_learning.pdf` contains real heading structure and must be used for the heading-metadata assertion.

### Implementation steps

- [x] Write a failing test: load `eval/complex-pdf/machine_learning.pdf`, run `PdfDocumentReader.read()`, and assert at least one chunk carries a non-blank `heading_title` in metadata
- [x] Replace the body of `PdfDocumentReader.read()` with: call `PdfMarkdown.toMarkdown(file, ConversionOptions.RAG)` then delegate to `MarkdownDocumentReader(markdownString, chunkSize, chunkOverlap).read()`
- [x] Remove `spring-ai-pdf-document-reader` from `build.gradle.kts`

### Acceptance criteria

- [x] `PdfDocumentReader.read()` on `sample.pdf` produces at least one non-empty chunk
- [x] `PdfDocumentReader.read()` on `machine_learning.pdf` produces at least one chunk with a non-blank `heading_title` in metadata
- [x] All existing `PdfDocumentReaderTest` tests pass
- [x] `spring-ai-pdf-document-reader` is absent from `build.gradle.kts`

### Quality gates

- [x] `./gradlew compileKotlin` produces no errors or warnings
- [x] `./gradlew test` passes
