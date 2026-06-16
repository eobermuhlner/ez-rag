# ingest-additional-formats

## Problem Statement

Users who work with HTML files saved locally, CSV data files, and RTF documents cannot ingest those files into ez-rag — they are silently skipped when ingesting a directory. Additionally, users who ingest large Excel spreadsheets get poor retrieval quality because the current ingestion splits table content at arbitrary token boundaries, breaking rows mid-way and losing column context.

## Solution

Extend the ingestion pipeline to support three new file types — HTML (`.html`, `.htm`), CSV (`.csv`), and RTF (`.rtf`) — and fix the chunking strategy for tabular data (Excel and CSV) so that row boundaries are always respected and column headers are repeated in every chunk.

## User Stories

1. As a user, I want to ingest `.html` and `.htm` files from my local filesystem, so that I can search their content the same way I search web pages I have fetched by URL.
2. As a user, I want HTML files to be chunked by heading structure, so that retrieved chunks map to coherent sections of the page.
3. As a user, I want to ingest `.csv` files, so that I can search tabular data stored in CSV format.
4. As a user, I want each CSV chunk to contain the column header row, so that the meaning of each cell value is clear without needing surrounding context.
5. As a user, I want CSV chunks to contain as many complete rows as fit within the token limit, so that related rows are kept together and I do not receive chunks that cut a row in half.
6. As a user, I want to ingest `.rtf` files, so that legacy documents in Rich Text Format can be included in my knowledge base.
7. As a user, I want RTF text content to be searchable after ingestion, so that queries match prose from RTF documents even without heading structure.
8. As a user, I want large Excel sheets to be chunked so that each chunk contains only complete rows, so that I never receive a chunk with a partial row or missing column values.
9. As a user, I want each Excel chunk to repeat the header row of the sheet, so that column names are always present in the retrieved chunk regardless of which batch of rows it contains.
10. As a user, I want the `ingest` command to process `.html`, `.htm`, `.csv`, and `.rtf` files when ingesting a directory, so that I do not need to name each file individually.
11. As a user, I want unsupported file types in a directory to still produce a warning, so that I am aware of files that were skipped.
12. As a user, I want all existing supported file types to continue working unchanged after this change, so that there is no regression in my current workflow.

## User Acceptance Tests

1. Given a local `.html` file containing heading tags and paragraphs, when the file is ingested, then searching for text from the file returns relevant chunks that correspond to heading-bounded sections.
2. Given a local `.htm` file, when it is ingested, then its content is retrievable by search.
3. Given an HTML file and a URL pointing to the same page content, when both are ingested, then the chunks produced are equivalent in structure.
4. Given a `.csv` file with a header row and ten data rows, when the file is ingested, then every chunk returned by search contains the header row.
5. Given a `.csv` file large enough to produce multiple chunks, when the chunks are inspected, then no row is split across two chunks.
6. Given a `.csv` file large enough to produce multiple chunks, when the chunks are inspected, then batches do not overlap (no row appears in more than one chunk).
7. Given a `.rtf` file containing several paragraphs of text, when the file is ingested, then searching for phrases from those paragraphs returns matching chunks.
8. Given an `.xlsx` file with a sheet containing many rows, when the file is ingested, then every retrieved chunk includes the sheet's header row.
9. Given an `.xlsx` file with a sheet containing many rows, when the chunks are inspected, then no row is split across two chunks.
10. Given a directory containing `.html`, `.csv`, `.rtf`, `.xlsx`, and `.txt` files, when the directory is ingested, then all five file types are ingested without error.
11. Given a directory containing a file with an unsupported extension, when the directory is ingested, then a warning is printed for the unsupported file and ingestion of supported files continues normally.
12. Given an existing store with previously ingested `.pdf` and `.docx` files, when the new formats are added and ingestion is re-run, then the existing documents remain retrievable and no data is lost.

## Definition of Done

- `.html` and `.htm` files are ingested and searchable.
- `.csv` files are ingested; every chunk contains the full header row; no chunk contains a partial row.
- `.rtf` files are ingested and their text content is searchable.
- Large Excel sheets are chunked at row boundaries; every chunk contains the sheet header row; no chunk contains a partial row.
- Directory ingestion automatically discovers and processes all four new extensions.
- All existing user acceptance tests for previously supported formats continue to pass.
- No new runtime dependencies are introduced.
- README updated to reflect the new supported file types.
- All automated tests pass.

## Out of Scope

- Source code files (`.py`, `.java`, `.kt`, etc.) — deferred; a dedicated code-aware chunking strategy is planned separately.
- OpenDocument formats (`.odt`, `.ods`, `.odp`) — not included in this iteration.
- EPUB (`.epub`) — not included in this iteration.
- RTF heading structure extraction — RTF style parsing requires additional dependencies and is deferred; plain text extraction is sufficient for this iteration.
- Row overlap between tabular chunks — deliberately excluded; rows are discrete records and overlap adds noise without quality benefit.
- Apache Tika — not used; existing libraries (Apache POI, PDFBox, Jsoup) already cover all formats in scope.

## Further Notes

The row-aware batching fix for Excel is a quality improvement, not a breaking change. Existing ingested Excel documents may produce different chunk boundaries after re-ingestion, which is expected and desirable.

CSV and Excel share the same row-batching strategy. The implementation should extract this into a shared module so the two readers stay consistent.

---

## Technical Annex
> Written against codebase as of: 2026-06-15

### Architectural Decisions

#### New module: `TableChunker`

A new deep module at `ch.obermuhlner.ezrag.ingestion.TableChunker` encapsulates the row-aware batching strategy shared by both CSV and Excel readers.

Interface (sketch):
```kotlin
class TableChunker(private val chunkSize: Int = 1000) {
    // Returns a list of Markdown table strings.
    // Each string begins with the header row + separator, followed by N complete data rows.
    // N is as large as possible without exceeding chunkSize tokens.
    // Uses TokenCounter.countTokens() for measurement.
    // Batches are strictly non-overlapping.
    fun chunk(header: List<String>, rows: List<List<String>>): List<String>
}
```

`TableChunker` has no dependency on file format — it operates purely on in-memory row lists, making it independently testable.

#### `HtmlDocumentReader` — file support

`HtmlDocumentReader` currently accepts a raw HTML `String`. A secondary constructor (or a companion factory) accepting a `File` will read the file content and delegate to the existing string-based constructor. Behaviour is identical to the URL ingest path.

Register `"html"` and `"htm"` in both `DocumentReaderRegistry.readers` and `DirectoryWalker.SUPPORTED_EXTENSIONS`.

#### `CsvDocumentReader` (new)

`ch.obermuhlner.ezrag.ingestion.CsvDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int)`

- Parses the CSV using the JVM standard library or a minimal inline parser (no new dependency).
- Treats the first row as the header.
- Passes header + data rows to `TableChunker`.
- Wraps each resulting Markdown table string in a `Document`.

Register `"csv"` in `DocumentReaderRegistry` and `DirectoryWalker`.

#### `ExcelDocumentReader` / `ExcelToMarkdownConverter` — refactor

`ExcelToMarkdownConverter` gains a method that returns structured row data per sheet:

```kotlin
fun extractSheets(file: File, passwords: List<String>): List<Pair<String, SheetData>>
// SheetData = Pair<List<String>, List<List<String>>> (header, data rows)
```

`ExcelDocumentReader.read()` is updated to call `extractSheets`, then for each sheet:
1. Emits a heading document (`## SheetName`) if desired, or prefixes each chunk with the sheet name.
2. Passes sheet header + rows to `TableChunker`.
3. Wraps each resulting table string in a `Document`.

The existing `convert(): String` method on `ExcelToMarkdownConverter` may be retained for backward compatibility with existing tests, or the existing tests may be updated to cover the new row-based output.

#### `RtfDocumentReader` (new)

`ch.obermuhlner.ezrag.ingestion.RtfDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int)`

- Uses `javax.swing.text.rtf.RTFEditorKit` (zero new dependencies — included in the JDK).
- Reads the RTF file into a `DefaultStyledDocument`, extracts plain text via `document.getText(0, document.length)`.
- Applies `TokenTextSplitter` with `chunkSize` and `chunkOverlap`, mirroring `PlainTextDocumentReader`.
- No heading structure is attempted.

Register `"rtf"` in `DocumentReaderRegistry` and `DirectoryWalker`.

#### `DocumentReaderRegistry` + `DirectoryWalker`

Add to `DocumentReaderRegistry.readers`:
- `"html"` → `HtmlDocumentReader(file).read()`
- `"htm"` → `HtmlDocumentReader(file).read()`
- `"csv"` → `CsvDocumentReader(file, chunkSize, chunkOverlap).read()`
- `"rtf"` → `RtfDocumentReader(file, chunkSize, chunkOverlap).read()`

Add `"html"`, `"htm"`, `"csv"`, `"rtf"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`.

### Automated Testing Decisions

**What makes a good test here**: test the reader's output (chunk text content, chunk count, metadata keys) against known fixture inputs. Do not assert on internal converter classes or intermediate Markdown strings unless the converter is itself the unit under test.

#### `TableChunkerTest` (unit)
- Verify that a single-batch input (few rows) produces one chunk containing the header.
- Verify that a large input produces multiple chunks, each starting with the header row.
- Verify that no row appears in more than one chunk (non-overlapping).
- Verify that no chunk exceeds `chunkSize` tokens (unless a single row already exceeds it).
- Prior art: `SectionSplitterTest` — pure logic, no file I/O, fast.

#### `CsvDocumentReaderTest` (unit)
- Use an inline CSV string written to a temp file as the fixture.
- Verify chunk count > 1 for a multi-row CSV that exceeds `chunkSize`.
- Verify header appears in every chunk.
- Verify no partial rows.
- Prior art: `PlainTextDocumentReaderTest`, `MarkdownDocumentReaderTest`.

#### `RtfDocumentReaderTest` (unit)
- Use a minimal RTF string written to a temp file as the fixture (RTF can be hand-crafted without a library).
- Verify text content is extracted and appears in at least one chunk.
- Prior art: `PlainTextDocumentReaderTest`.

#### `HtmlDocumentReaderTest` — file path (unit)
- Add one test to the existing `HtmlDocumentReaderTest` that constructs a reader from a `File` and verifies chunks are produced.
- Prior art: existing `HtmlDocumentReaderTest` string-based tests.

#### `ExcelToMarkdownConverterTest` — batching (unit)
- Add tests verifying that a sheet with many rows produces multiple chunks when `chunkSize` is small.
- Verify header is present in every chunk.
- Prior art: existing `ExcelToMarkdownConverterTest`.

#### `DocumentReaderRegistryTest` — new extensions (unit)
- Verify `supports("html")`, `supports("htm")`, `supports("csv")`, `supports("rtf")` all return `true`.
- Prior art: existing `DocumentReaderRegistryTest`.

#### `DirectoryWalkerTest` — new extensions (unit)
- Verify files with `.html`, `.htm`, `.csv`, `.rtf` extensions are included in the walk results.
- Prior art: existing `DirectoryWalkerTest`.
