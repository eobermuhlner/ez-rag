## Problem Statement

Users store knowledge in Microsoft Office documents — Word reports, Excel spreadsheets, and PowerPoint presentations — but ez-rag can only ingest plain text, Markdown, PDF, and HTML. This means large bodies of existing content are inaccessible to RAG-powered assistants without manual conversion.

## Solution

Add support for ingesting Microsoft Office files (`.docx`, `.doc`, `.xlsx`, `.xls`, `.pptx`, `.ppt`) using Apache POI. Each format is converted to structured Markdown before chunking, reusing the existing heading-aware pipeline. Password-protected files can be unlocked by supplying one or more passwords at ingest time; the tool tries each password in order and skips with a warning if none succeed.

## User Stories

1. As a user, I want to ingest a `.docx` Word document, so that its content is available for RAG search without manual conversion.
2. As a user, I want to ingest a `.doc` legacy Word document, so that older files are also searchable.
3. As a user, I want to ingest a `.xlsx` Excel spreadsheet, so that tabular data in workbooks is searchable.
4. As a user, I want to ingest a `.xls` legacy Excel spreadsheet, so that older workbooks are also searchable.
5. As a user, I want to ingest a `.pptx` PowerPoint presentation, so that slide content is available for RAG search.
6. As a user, I want to ingest a `.ppt` legacy PowerPoint presentation, so that older presentations are also searchable.
7. As a user, I want headings in Word documents to be preserved as Markdown headings, so that the chunker can split content by section boundaries.
8. As a user, I want tables in Word documents to be represented as Markdown tables in the extracted text, so that tabular data is retained in chunks.
9. As a user, I want footnotes and endnotes from Word documents to be included in the extracted text, so that supplementary content is not lost.
10. As a user, I want comments (review annotations) in Word documents to be excluded, so that reviewer remarks do not pollute retrieved chunks.
11. As a user, I want each PowerPoint slide title to become a Markdown heading, so that slides are chunked as named sections.
12. As a user, I want speaker notes from PowerPoint slides to be included in the extracted text, so that presenter content is also searchable.
13. As a user, I want each Excel sheet to appear as a named Markdown section, so that chunks can be attributed to the correct sheet.
14. As a user, I want Excel rows to be represented as Markdown table rows, so that the structure of tabular data is preserved in chunks.
15. As a user, I want to pass `--password` to the `ingest` CLI command, so that I can unlock a password-protected Office file.
16. As a user, I want to pass `--password` multiple times on a single `ingest` call, so that I can ingest a directory containing files with different passwords in one command.
17. As a user, I want password-protected files that cannot be opened with any supplied password to be skipped with a warning, so that a single locked file does not abort an entire directory ingest.
18. As a user, I want password-protected files to be skipped with a warning when no `--password` is supplied, so that an ingestion batch is not aborted by an unexpected locked file.
19. As a user, I want the MCP `ingest` tool to accept a `passwords` list parameter, so that MCP clients can unlock protected Office files.
20. As a user, I want the MCP `reingest` tool to accept a `passwords` list parameter, so that previously ingested protected files can be re-ingested after their content changes.
21. As a user, I want to ingest a directory that contains a mix of Office files, PDFs, and plain text, so that all supported formats are processed in a single command.
22. As a user, I want Office files in a directory to be discovered automatically during directory ingest, so that I do not need to list each file individually.
23. As a user, I want unchanged Office files to be skipped on re-ingest (same content-hash and mtime), so that repeat ingestion is as fast as for other formats.

## User Acceptance Tests

1. Given a `.docx` file containing multiple heading levels and paragraphs, when it is ingested, then the resulting chunks preserve the heading hierarchy and each chunk is attributed to its section.
2. Given a `.docx` file containing a table, when it is ingested, then the table content appears in at least one retrieved chunk.
3. Given a `.docx` file containing footnotes, when it is ingested, then the footnote text appears in the retrieved chunks.
4. Given a `.docx` file containing review comments, when it is ingested, then the comment text does not appear in any retrieved chunk.
5. Given a `.pptx` file with three slides each having a title and body, when it is ingested, then each slide's title and body text appears in the retrieved chunks.
6. Given a `.pptx` file where one slide has speaker notes, when it is ingested, then the speaker notes text appears in the retrieved chunks.
7. Given a `.xlsx` file with two sheets named "Summary" and "Details", when it is ingested, then chunks from the "Summary" sheet and chunks from the "Details" sheet can both be retrieved.
8. Given a `.xlsx` file where a sheet contains a header row and data rows, when it is ingested, then the row values appear in the retrieved chunks.
9. Given a password-protected `.docx` file and a correct `--password` value, when the file is ingested, then its content is indexed and retrievable.
10. Given a password-protected `.docx` file and two `--password` values where only the second is correct, when the file is ingested, then the file is successfully unlocked and its content is indexed.
11. Given a password-protected `.docx` file and an incorrect `--password` value, when the file is ingested, then the file is skipped and a warning is printed; no error is thrown and other files in the batch are unaffected.
12. Given a password-protected `.docx` file and no `--password` option, when the file is ingested, then the file is skipped with a warning.
13. Given a directory containing `.docx`, `.xlsx`, `.pptx`, and `.pdf` files, when the directory is ingested, then all files are processed and their content is retrievable.
14. Given a `.docx` file that has already been ingested and has not changed, when it is ingested again, then it is skipped and the chunk count does not increase.
15. Given a `.doc`, `.xls`, and `.ppt` legacy file, when each is ingested, then their content is retrievable (legacy formats are supported).

## Definition of Done

- All user acceptance tests pass.
- All six Office extensions (`.docx`, `.doc`, `.xlsx`, `.xls`, `.pptx`, `.ppt`) are indexed when a containing directory is ingested.
- The `--password` option is documented in `ez-rag ingest --help`.
- The MCP `ingest` and `reingest` tools list `passwords` as an optional parameter in their tool descriptions.
- Password-protected files that cannot be unlocked are skipped with a warning and do not abort a batch.
- No regression in ingestion of existing formats (plain text, Markdown, PDF, HTML).
- README updated to document supported Office formats and `--password` usage.
- All new automated tests pass.
- The version in `gradle.properties` is bumped (minor bump: new feature).

## Out of Scope

- Per-file password specification (all passwords apply to every file in the batch).
- Extraction of embedded OLE objects or charts within Office files.
- Ingestion of Office files fetched from HTTP/HTTPS URLs (Office content types are not handled by the URL fetcher).
- Password-protected Office files in the `reingest` CLI command (only via MCP `reingest`).
- Support for OpenDocument formats (`.odt`, `.ods`, `.odp`).
- Support for older binary formats beyond what Apache POI's HWPF/HSSF/HSLF APIs provide.

## Further Notes

- Apache POI's XWPF (`.docx`), XSSF (`.xlsx`), and XSLF (`.pptx`) APIs handle the modern Open XML formats. The legacy HWPF (`.doc`), HSSF (`.xls`), and HSLF (`.ppt`) APIs handle the older binary formats. Both are included in the `poi-ooxml` artifact.
- POI carries a significant transitive dependency footprint (Commons Codec, XMLBeans, etc.). Startup-time impact should be measured after implementation.
- Excel sheets with more than a few thousand rows will produce large Markdown sections; the existing `SectionSplitter` token budget will handle oversized sections naturally.

---

## Technical Annex
> Written against codebase as of: 2026-06-15

### Architectural Decisions

#### New converters (deep modules)

Three new converter classes, each following the pattern established by `PdfMarkdown.toMarkdown()`:

```kotlin
// ch.obermuhlner.ezrag.ingestion.office

class WordToMarkdownConverter {
    fun convert(file: File, passwords: List<String> = emptyList()): String
}

class PowerPointToMarkdownConverter {
    fun convert(file: File, passwords: List<String> = emptyList()): String
}

class ExcelToMarkdownConverter {
    fun convert(file: File, passwords: List<String> = emptyList()): String
}
```

Password unlocking logic (shared across all three): iterate `passwords`, attempt to open the file with each; if none succeed, throw `EncryptedDocumentException` (or an `IllegalArgumentException` wrapping it) so the caller can skip with a warning.

**Word extraction rules:**
- Paragraph style names matching `Heading 1`–`Heading 6` (or POI's `HWPF` outline level) → `#`–`######`
- Normal paragraphs → plain text
- `XWPFTable` / `HWPFTable` → Markdown table (`| col | col |` with header separator)
- Bullet/numbered lists → `-` / `1.` items
- `XWPFFootnote` / `XWPFEndnote` → appended after the paragraph they annotate (or at end of section)
- `XWPFComment` → skipped entirely

**PowerPoint extraction rules:**
- Each slide → Markdown section: slide title text → `## Slide N: <title>` (or just `## <title>` if non-empty); body placeholder text → paragraphs; `XSLFNotes` (speaker notes) → appended after body
- Legacy `.ppt`: `HSLFSlide` title shape → heading; text shapes → body; `HSLFNotes` → appended

**Excel extraction rules:**
- Each sheet → `## <sheet name>` heading followed by a Markdown table
- First row treated as header row (separated by `|---|` row)
- Subsequent rows → data rows
- Empty rows skipped
- Cell values formatted as strings (dates, numbers use POI's `DataFormatter`)

#### New reader classes (thin wrappers)

Following the pattern of `PdfDocumentReader` and `HtmlDocumentReader`:

```kotlin
class WordDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val passwords: List<String> = emptyList(),
) {
    fun read(): List<Document> {
        val markdown = WordToMarkdownConverter().convert(file, passwords)
        return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
    }
}
// PowerPointDocumentReader and ExcelDocumentReader follow identical structure
```

#### `DocumentReaderRegistry` changes

- Register `"docx"` and `"doc"` → `WordDocumentReader`
- Register `"xlsx"` and `"xls"` → `ExcelDocumentReader`
- Register `"pptx"` and `"ppt"` → `PowerPointDocumentReader`
- Add `passwords: List<String> = emptyList()` constructor parameter; pass it into each Office reader factory lambda

#### `DirectoryWalker` changes

Add the six new extensions to `SUPPORTED_EXTENSIONS`:

```kotlin
val SUPPORTED_EXTENSIONS = setOf("txt", "pdf", "md", "docx", "doc", "xlsx", "xls", "pptx", "ppt")
```

#### `IngestService` changes

Add `passwords: List<String> = emptyList()` constructor parameter. Pass it when constructing `DocumentReaderRegistry`:

```kotlin
val registry = DocumentReaderRegistry(chunkSize, chunkOverlap, passwords)
```

#### `IngestCommand` changes

Add a repeatable picocli option:

```kotlin
@Option(names = ["--password"], description = ["Password for encrypted Office files. Repeat for multiple passwords."])
var passwords: List<String> = emptyList()
```

Pass `passwords` when constructing `IngestService`.

#### `McpIngestTool` changes

Add optional `passwords` tool parameter:

```kotlin
@ToolParam(required = false, description = "Passwords to try when opening encrypted Office files.")
passwords: List<String>?
```

Pass to `IngestService` constructor.

#### `McpReIngestTool` changes

Add optional `passwords` tool parameter; pass to `ReIngestService` constructor (which in turn passes to `DocumentReaderRegistry`).

#### Dependency addition (`build.gradle.kts`)

```kotlin
implementation("org.apache.poi:poi-ooxml:5.3.0")
```

`poi-ooxml` includes both the OOXML (`.docx`/`.xlsx`/`.pptx`) and legacy binary (`.doc`/`.xls`/`.ppt`) APIs.

### Automated Testing Decisions

**What makes a good test here:** test the Markdown output of each converter against a known Office test file — assert that specific headings, table rows, or slide titles appear in the output. Do not assert on exact whitespace or ordering beyond what the feature requires.

**Modules with automated tests:**

| Module | Test type | Notes |
|---|---|---|
| `WordToMarkdownConverter` | Unit | Real `.docx` test fixture containing headings, paragraphs, table, footnote, comment. Assert heading markers appear, table content appears, footnote text appears, comment text absent. |
| `WordToMarkdownConverter` (password) | Unit | Real password-protected `.docx` fixture. Assert correct password opens file, wrong password skips, no password skips. |
| `PowerPointToMarkdownConverter` | Unit | Real `.pptx` test fixture with slide titles, body text, speaker notes. Assert all three appear in output. |
| `ExcelToMarkdownConverter` | Unit | Real `.xlsx` test fixture with two sheets and data rows. Assert sheet headings and cell values appear. |
| `IngestCommand` (password option) | Unit | Use existing `IngestCommandTest` pattern; pass `--password` and verify a protected fixture is ingested. |

**Prior art:** `IngestCommandTest`, `MarkdownDocumentReaderTest`, `PdfDocumentReaderTest` (if present) — use their fixture/assertion patterns.

Test fixtures (small Office files) live under `src/test/resources/fixtures/`.
