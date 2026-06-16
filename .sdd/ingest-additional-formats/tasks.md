# Tasks: ingest-additional-formats

## Task 01-table-chunker

Foundational shared module that encapsulates the row-aware batching strategy used by both CSV and Excel readers. `TableChunker` accepts a header row and data rows, measures token counts via `TokenCounter.countTokens()`, and returns a list of Markdown table strings — each starting with the full header row and separator, followed by as many complete data rows as fit within `chunkSize`. No file-format knowledge; independently testable.

This is a prerequisite for Tasks 04 (CSV) and 05 (Excel refactor). It has no user-visible effect on its own.

### Implementation steps

- [x] Create `TableChunker(chunkSize: Int = 1000)` at `ch.obermuhlner.ezrag.ingestion.TableChunker`
- [x] Implement `fun chunk(header: List<String>, rows: List<List<String>>): List<String>` — batch rows into Markdown table strings using `TokenCounter.countTokens()` to decide how many rows fit per chunk; no row may span two chunks
- [x] Write `TableChunkerTest` covering: single-batch (total tokens ≤ chunkSize → one chunk with header), multi-batch (total tokens > chunkSize → multiple chunks each with header), non-overlapping (no row appears in two chunks), single oversized row (emitted as its own chunk), empty row list (returns empty list)

### Acceptance criteria

- [x] A small input whose total token count is ≤ chunkSize produces exactly one chunk containing the header row and separator
- [x] A large input whose total token count exceeds chunkSize produces multiple chunks, each starting with the full header row and separator
- [x] No data row appears in more than one chunk (strictly non-overlapping)
- [x] Every chunk's token count is ≤ chunkSize, with the sole exception of a single data row that alone exceeds chunkSize (emitted as its own one-row chunk)
- [x] An empty row list returns an empty list

### Quality gates

- [x] `./gradlew test --tests "*.TableChunkerTest"` passes with all test cases green
- [x] `./gradlew build` compiles without errors

---

## Task 02-html-file-ingestion

Extend HTML ingestion end-to-end to support local `.html` and `.htm` files. `HtmlDocumentReader` currently accepts only an HTML string; a `File`-accepting constructor (or companion factory) is added that reads the file content and delegates to the existing string-based path. Both extensions are registered in `DocumentReaderRegistry` and `DirectoryWalker` so that directory ingestion automatically discovers them.

### Implementation steps

- [x] Add a secondary constructor or companion object factory to `HtmlDocumentReader` that accepts a `java.io.File`, reads its content as UTF-8, and delegates to the existing `String` constructor
- [x] Register `"html"` and `"htm"` in `DocumentReaderRegistry.readers`
- [x] Add `"html"` and `"htm"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Add a test to `HtmlDocumentReaderTest` that constructs the reader from a temp `.html` file containing heading tags and verifies at least one chunk is produced
- [x] Extend the `supports returns true` test in `DocumentReaderRegistryTest` to assert `supports("html")` and `supports("htm")` return `true`
- [x] Update `DirectoryWalkerTest` `walk returns only supported extensions` to include `.html` and `.htm` files and assert they appear in results

### Acceptance criteria

- [x] `HtmlDocumentReader` constructed from a temp `.html` file containing heading-structured HTML produces at least one chunk
- [x] `HtmlDocumentReader` constructed from a temp `.htm` file produces at least one chunk
- [x] `DocumentReaderRegistry.supports("html")` returns `true`
- [x] `DocumentReaderRegistry.supports("htm")` returns `true`
- [x] A directory containing `.html` and `.htm` files yields those files in the walk results
- [x] All previously passing `HtmlDocumentReaderTest` string-based tests continue to pass without modification

### Quality gates

- [x] `./gradlew test --tests "*.HtmlDocumentReaderTest"` passes
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes
- [x] `./gradlew test --tests "*.DirectoryWalkerTest"` passes
- [x] `./gradlew test` passes (no regressions)

---

## Task 03-rtf-ingestion

Add RTF (`.rtf`) ingestion end-to-end. `RtfDocumentReader` uses `javax.swing.text.rtf.RTFEditorKit` from the `java.desktop` JVM module — no new library dependency. It reads the RTF file into a `DefaultStyledDocument`, extracts plain text, and applies `TokenTextSplitter` with `chunkSize` and `chunkOverlap`, mirroring `PlainTextDocumentReader`. `"rtf"` is registered in `DocumentReaderRegistry` and `DirectoryWalker`.

Note: `RTFEditorKit` requires the `java.desktop` JVM module, which is always present on Windows and macOS. If CI runs on a minimal headless Linux JRE, add a `@DisabledIf` guard on the test class to avoid `NoClassDefFoundError`.

### Implementation steps

- [x] Create `RtfDocumentReader(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200)` using `RTFEditorKit` + `DefaultStyledDocument` to extract plain text, then `TokenTextSplitter` to produce chunks
- [x] Register `"rtf"` in `DocumentReaderRegistry.readers`
- [x] Add `"rtf"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Create `RtfDocumentReaderTest` using a minimal hand-crafted RTF string (e.g. `{\rtf1\ansi Hello world. Second sentence here.}`) written to a temp file; verify the extracted chunk text contains the prose, not RTF control codes
- [x] Add `supports("rtf")` assertion to `DocumentReaderRegistryTest`
- [x] Update `DirectoryWalkerTest` to include a `.rtf` file in the supported-extensions test and verify it appears in walk results

### Acceptance criteria

- [x] A minimal RTF file containing several words produces at least one chunk whose text contains those words (not RTF control codes)
- [x] `DocumentReaderRegistry.supports("rtf")` returns `true`
- [x] A directory containing a `.rtf` file yields it in the walk results; no warning is emitted for `.rtf` files
- [x] No new JAR entry is added to `build.gradle.kts` (verified by inspection — `RTFEditorKit` ships with the JDK)
- [x] All previously passing tests continue to pass

### Quality gates

- [x] `./gradlew test --tests "*.RtfDocumentReaderTest"` passes
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes
- [x] `./gradlew test --tests "*.DirectoryWalkerTest"` passes
- [x] `./gradlew test` passes (no regressions)

---

## Task 04-csv-ingestion

Add CSV (`.csv`) ingestion end-to-end, using `TableChunker` (Task 01) for row-aware batching. `CsvDocumentReader` parses the file line-by-line with the JVM standard library, treats the first row as the header, and passes header + data rows to `TableChunker`. Each chunk is a Markdown table string beginning with the header row. The `chunkOverlap` parameter is accepted for API consistency with other readers but is not forwarded to `TableChunker` (row overlap is out of scope). Two existing tests assume CSV is unsupported and must be updated as part of this task.

### Implementation steps

- [x] Create `CsvDocumentReader(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200)` that reads lines, splits each line on commas, treats line 0 as the header, calls `TableChunker.chunk(header, dataRows)`, and wraps each result in a `Document`
- [x] Register `"csv"` in `DocumentReaderRegistry.readers`
- [x] Add `"csv"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Update `DocumentReaderRegistryTest`: (a) remove `csv` from the `supports returns false for unknown extensions` assertion; (b) convert the `read throws IllegalArgumentException for unsupported extension` test (which uses `data.csv`) into a positive `read dispatches csv file and produces chunks` test
- [x] Update `DirectoryWalkerTest`: remove `bad2.csv` from the `walk emits a warning for each unsupported file` test; add a separate assertion or extend the existing supported-extensions test to verify `.csv` files are included in walk results and do not emit a warning
- [x] Create `CsvDocumentReaderTest` using an inline CSV string written to a temp file; cover: multi-chunk output for a large input, header present in every chunk, no partial rows, no row in two chunks, header-only CSV (no data rows) returns empty list

### Acceptance criteria

- [x] A CSV file with a header row and enough data rows to exceed chunkSize produces more than one chunk
- [x] Every produced chunk's text contains the header row column names
- [x] No data row is split across two chunks; each row appears fully in exactly one chunk
- [x] No row appears in more than one chunk (non-overlapping)
- [x] A CSV file containing only a header row and no data rows produces an empty list of documents
- [x] `DocumentReaderRegistry.supports("csv")` returns `true`
- [x] A directory containing `.csv` files yields those files in walk results; no unsupported-file warning is emitted for `.csv`

### Quality gates

- [x] `./gradlew test --tests "*.CsvDocumentReaderTest"` passes
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes
- [x] `./gradlew test --tests "*.DirectoryWalkerTest"` passes
- [x] `./gradlew test` passes (no regressions)

---

## Task 05-excel-row-chunking

Refactor Excel ingestion end-to-end to use `TableChunker` (Task 01) for row-complete, header-repeating chunks. `ExcelToMarkdownConverter` gains an `extractSheets()` method returning structured per-sheet data (sheet name, header row, data rows). `ExcelDocumentReader` uses it to call `TableChunker.chunk()` per sheet and emit one `Document` per chunk. Each chunk's text begins with `## SheetName` so retrieval results retain spreadsheet context. The existing `convert(): String` method is retained so all existing `ExcelToMarkdownConverterTest` tests pass without modification. README is updated to list all newly supported file types.

### Implementation steps

- [x] Add `extractSheets(file: File, passwords: List<String> = emptyList()): List<Triple<String, List<String>, List<List<String>>>>` (sheet name, header row, data rows) to `ExcelToMarkdownConverter`; implement for both `.xlsx` (with existing encryption handling) and `.xls`
- [x] Update `ExcelDocumentReader.read()` to call `extractSheets`, then for each sheet prepend `## SheetName\n` and call `TableChunker.chunk(header, dataRows)`, wrapping each result in a `Document`
- [x] Retain the existing `convert(): String` method unchanged so all existing `ExcelToMarkdownConverterTest` tests pass without modification
- [x] Add new tests verifying: a sheet with many rows produces multiple chunks, every chunk contains the sheet's header row column names, no row is split, every chunk contains the sheet name heading
- [x] Update README.md to list `.html`, `.htm`, `.csv`, and `.rtf` as newly supported file types

### Acceptance criteria

- [x] An Excel sheet with enough rows to exceed chunkSize produces more than one chunk
- [x] Every produced chunk's text contains the sheet's header row column names
- [x] No row is split across two chunks; each row appears fully in exactly one chunk
- [x] No row appears in more than one chunk (non-overlapping)
- [x] Every chunk's text begins with `## SheetName` so the spreadsheet source is clear in retrieval results
- [x] All existing `.xls` and `.xlsx` tests — including encrypted-file tests — pass without modification
- [x] README.md lists `.html`, `.htm`, `.csv`, and `.rtf` as supported formats

### Quality gates

- [x] `./gradlew test --tests "*.ExcelToMarkdownConverterTest"` passes
- [x] `./gradlew test` passes (no regressions)
