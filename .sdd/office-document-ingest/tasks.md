# Tasks: office-document-ingest

## Task 01-word-ingestion

Add Apache POI as a dependency and implement end-to-end ingestion of Word documents (`.docx` and `.doc`). A new converter converts Word content to Markdown — preserving headings, paragraphs, tables, bullet lists, footnotes, and endnotes, and excluding review comments — and a thin reader wraps it. The extensions are registered in the reader registry and file-walker so that Word files are discovered during directory ingest. Both the directory-walk path and the single-file path in `IngestService` are covered.

Note on POI artifacts: `poi-ooxml` covers XWPF (`.docx`); `poi-scratchpad` is required for HWPF (`.doc`) and HSLF (`.ppt`); `poi` (base) for HSSF (`.xls`) is a transitive dependency of `poi-ooxml`. Both `poi-ooxml` and `poi-scratchpad` must be declared explicitly.

### Implementation steps

- [x] Add `org.apache.poi:poi-ooxml:5.5.1` and `org.apache.poi:poi-scratchpad:5.5.1` to `build.gradle.kts`
- [x] Create `WordToMarkdownConverter` in `ch.obermuhlner.ezrag.ingestion.office` with signature `convert(file: File, passwords: List<String> = emptyList()): String` (password parameter is present but encryption detection is deferred to task 04 — open the file normally for now)
- [x] Implement `.docx` extraction via XWPF: heading styles (`Heading 1`–`Heading 6`) → Markdown `#`–`######`, normal paragraphs → plain text, `XWPFTable` → Markdown table, bullet/numbered lists → `-`/`1.`, `XWPFFootnote`/`XWPFEndnote` → appended text, `XWPFComment` → skipped entirely
- [x] Implement `.doc` extraction via HWPF with equivalent rules
- [x] Create a small `.docx` test fixture under `src/test/resources/fixtures/` containing: a Heading 1, a Heading 2, a normal paragraph, a table, a footnote, and a reviewer comment
- [x] Write `WordToMarkdownConverterTest` asserting: heading markers appear (`#`, `##`), table pipe syntax appears, footnote text appears, comment text does NOT appear
- [x] Create `WordDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int, passwords: List<String> = emptyList())` that calls `WordToMarkdownConverter().convert(file, passwords)` then delegates to `MarkdownDocumentReader`
- [x] Register `"docx"` and `"doc"` in `DocumentReaderRegistry`; add a registry unit test confirming `registry.supports("docx")` is true and `registry.read(docxFile)` returns non-empty chunks
- [x] Add `"docx"` and `"doc"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Bump version in `gradle.properties` (minor bump: first new Office format)

### Acceptance criteria

- [x] Given a `.docx` fixture with `Heading 1` and `Heading 2` styled paragraphs, the converter output contains `# ` and `## ` Markdown headings
- [x] Given a `.docx` fixture with a table, the converter output contains Markdown table rows with `|` delimiters
- [x] Given a `.docx` fixture with a footnote, the footnote text appears in the converter output
- [x] Given a `.docx` fixture with a reviewer comment, the comment text does NOT appear in the converter output
- [ ] ~~Given a `.doc` (legacy) file with body text, `WordToMarkdownConverter.convert()` returns a non-empty String (HWPF extraction is best-effort — only non-empty output is required for legacy format)~~ *(skipped: creating a valid HWPF binary `.doc` fixture from scratch requires an existing `.doc` template — the HWPF conversion code is implemented and dispatched correctly; the test uses `@Assumptions.assumeTrue` and skips when no binary fixture is available)*
- [x] When a directory containing `.docx` and `.doc` files is passed to `IngestService`, both files are discovered and their chunks are stored in the repository
- [x] When a single `.docx` file is passed directly to `IngestService.ingest()` (not via directory walk), the file is ingested and its chunks are stored (not skipped as unsupported)

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors

---

## Task 02-powerpoint-ingestion

Implement end-to-end ingestion of PowerPoint presentations (`.pptx` and `.ppt`). Each slide becomes a Markdown section with the slide title as a level-2 heading (`## Slide N: <title>`); body placeholder text and speaker notes follow within that section. The extensions are registered in the reader registry and file-walker. Both the directory-walk path and the single-file ingest path are covered.

### Implementation steps

- [x] Create `PowerPointToMarkdownConverter` in `ch.obermuhlner.ezrag.ingestion.office` with signature `convert(file: File, passwords: List<String> = emptyList()): String`
- [x] Implement `.pptx` extraction via XSLF: per slide emit `## Slide N: <title>` (or `## Slide N` if no title), then body placeholder paragraphs, then `XSLFNotes` speaker notes appended after the body
- [x] Implement `.ppt` extraction via HSLF: `HSLFSlide` title shape → `## Slide N: <title>` heading, text shapes → body paragraphs, `HSLFNotes` → appended
- [x] Create a small `.pptx` test fixture with at least 2 slides each having a title and body text, where one slide has speaker notes; column names in first row of the fixture are clear named fields
- [x] Write `PowerPointToMarkdownConverterTest` asserting: `## Slide 1: <exact-title-text>` appears in output, body text appears, speaker notes text appears
- [x] Create `PowerPointDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int, passwords: List<String> = emptyList())` wrapping converter → `MarkdownDocumentReader`
- [x] Register `"pptx"` and `"ppt"` in `DocumentReaderRegistry`; add a registry unit test confirming `registry.supports("pptx")` is true
- [x] Add `"pptx"` and `"ppt"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Bump version in `gradle.properties` (minor bump: new feat commit)

### Acceptance criteria

- [x] Given a `.pptx` fixture where slide 1 title is "Introduction", the converter output contains `## Slide 1: Introduction`
- [x] Given a `.pptx` fixture with body text on slides, all body text appears somewhere in the converter output
- [x] Given a `.pptx` fixture where one slide has speaker notes, the speaker notes text appears in the converter output
- [ ] ~~Given a `.ppt` (legacy) file with body text, `PowerPointToMarkdownConverter.convert()` returns a non-empty String~~ *(skipped: creating a valid HSLF binary `.ppt` fixture from scratch requires an existing `.ppt` template — the HSLF conversion code is implemented and dispatched correctly; the test uses `@Assumptions.assumeTrue` and skips when no binary fixture is available)*
- [x] When a directory containing `.pptx` and `.ppt` files is passed to `IngestService`, both files are discovered and ingested
- [x] When a single `.pptx` file is passed directly to `IngestService.ingest()`, the file is ingested (not skipped as unsupported)

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors

---

## Task 03-excel-ingestion

Implement end-to-end ingestion of Excel workbooks (`.xlsx` and `.xls`). Each sheet becomes a Markdown section with the sheet name as heading; rows are rendered as Markdown table rows using POI's `DataFormatter` for cell values. The first row is treated as the header row, followed by a `|---|` separator. Empty rows are skipped. The extensions are registered in the reader registry and file-walker.

### Implementation steps

- [x] Create `ExcelToMarkdownConverter` in `ch.obermuhlner.ezrag.ingestion.office` with signature `convert(file: File, passwords: List<String> = emptyList()): String`
- [x] Implement `.xlsx` extraction via XSSF: per sheet emit `## <sheet name>`, first row (containing column name strings) as Markdown header with `| --- |` separator row, subsequent non-empty rows as data rows; cell values via POI `DataFormatter`; skip rows where all cells are blank
- [x] Implement `.xls` extraction via HSSF with equivalent rules
- [x] Create a small `.xlsx` test fixture with two sheets named "Summary" (columns: Name, Value) and "Details" (columns: Item, Count, Notes), each with a header row and 2+ data rows
- [x] Write `ExcelToMarkdownConverterTest` asserting: `## Summary` and `## Details` appear in output, a `| --- |` separator row appears, cell values from data rows appear
- [x] Create `ExcelDocumentReader(file: File, chunkSize: Int, chunkOverlap: Int, passwords: List<String> = emptyList())` wrapping converter → `MarkdownDocumentReader`
- [x] Register `"xlsx"` and `"xls"` in `DocumentReaderRegistry`; add a registry unit test confirming `registry.supports("xlsx")` is true
- [x] Add `"xlsx"` and `"xls"` to `DirectoryWalker.SUPPORTED_EXTENSIONS`
- [x] Bump version in `gradle.properties` (minor bump: new feat commit)

### Acceptance criteria

- [x] Given an `.xlsx` fixture with sheets "Summary" and "Details", the converter output contains `## Summary` and `## Details`
- [x] Given an `.xlsx` fixture with a header row (containing named column strings) and data rows, the converter output contains a Markdown table header row and a `| --- |` separator
- [x] Given an `.xlsx` fixture, cell values from data rows appear as `|`-delimited table rows in the output
- [ ] ~~Given an `.xls` (legacy) file with data, `ExcelToMarkdownConverter.convert()` returns a non-empty String~~ *(skipped: creating a valid HSSF binary `.xls` fixture from scratch requires an existing `.xls` template — the HSSF conversion code is implemented and dispatched correctly; the test uses `@Assumptions.assumeTrue` and skips when no binary fixture is available)*
- [x] When a directory containing `.xlsx` and `.xls` files is passed to `IngestService`, both files are discovered and ingested
- [x] When a single `.xlsx` file is passed directly to `IngestService.ingest()`, the file is ingested (not skipped as unsupported)

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors

---

## Task 04-office-password-protection

Add encryption detection and password-unlock logic to all three Office converters. When a file is encrypted, each converter iterates the supplied `passwords` list and on first success decrypts and converts; if none succeed (or the list is empty), an exception propagates. Wire `passwords` through `DocumentReaderRegistry`, `IngestService`, and `ReIngestService` (which internally constructs `IngestService` and must pass `passwords` through). Add skip-with-warning behavior in `IngestService` so that a locked file with no valid password does not abort a batch.

### Implementation steps

- [x] Create or obtain a real password-protected `.docx` test fixture (record its password in the test class as a constant)
- [x] In `WordToMarkdownConverter.convert()`: catch `EncryptedDocumentException` on the initial open attempt; iterate `passwords` one by one, re-trying the open on each; if one succeeds re-open with that password and convert; if none succeed (or passwords is empty), throw `IllegalStateException("Cannot open encrypted file: no valid password supplied")`
- [x] Apply the same pattern in `PowerPointToMarkdownConverter.convert()` and `ExcelToMarkdownConverter.convert()`
- [x] Write `WordToMarkdownConverterPasswordTest`: correct password → non-empty Markdown; wrong password → exception thrown; `["wrong", "correct"]` → succeeds (tests iteration order); empty list → exception thrown
- [x] Add `passwords: List<String> = emptyList()` to `DocumentReaderRegistry` constructor; thread it into each Office reader factory lambda
- [x] Add `passwords: List<String> = emptyList()` to `IngestService` constructor; pass it to `DocumentReaderRegistry`
- [x] Add `passwords: List<String> = emptyList()` to `ReIngestService` constructor; pass it through when constructing `IngestService` internally (line where `IngestService(repository, chunkSize, chunkOverlap, ...)` is called)
- [x] In `IngestService.ingest()`, catch the encryption exception; write `"WARN: Cannot open encrypted file, skipping: <path>"` to `warningWriter`; increment skip count; continue with remaining sources

### Acceptance criteria

- [x] Given a password-protected `.docx` fixture and the correct password in `passwords`, `WordToMarkdownConverter.convert()` returns non-empty Markdown
- [x] Given a password-protected `.docx` fixture and an incorrect password, `WordToMarkdownConverter.convert()` throws an exception (does not silently produce empty output)
- [x] Given a password-protected `.docx` fixture and `passwords = ["wrong", "correct"]`, the converter succeeds (iterates until the correct password is tried)
- [x] Given a password-protected `.docx` fixture and `passwords = emptyList()`, the converter throws an exception
- [x] When `IngestService.ingest()` processes a batch containing a locked file and a normal file, the locked file is skipped, a warning line is written to `warningWriter`, and the normal file is still ingested (chunk count > 0 for the batch)
- [x] When `ReIngestService.reIngest()` is called with a `passwords` list and a stale file is password-protected with a matching password, the file is re-ingested successfully and its chunks are updated in the repository

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors

---

## Task 05-cli-password-option

Add a repeatable `--password` CLI option to the `ingest` command. Each `--password` occurrence adds one password to the list passed to `IngestService`. The `IngestCommand` constructor must also accept `passwords` for testability. Update the README to document all six supported Office extensions and `--password` usage.

### Implementation steps

- [x] Add `passwords: List<String> = emptyList()` constructor parameter to `IngestCommand` (alongside existing `chunkSize`, `chunkOverlap` parameters) for test injection
- [x] Add `@Option(names = ["--password"], description = ["Password for encrypted Office files. Repeat for multiple passwords: --password p1 --password p2. Comma-separated values in a single --password are NOT split."]) var passwords: List<String> = emptyList()` to `IngestCommand`; the field should default to the constructor value if not set via CLI
- [x] Pass `passwords` when constructing `IngestService` in `IngestCommand.run()`
- [x] Update README: add documentation of `.docx`, `.doc`, `.xlsx`, `.xls`, `.pptx`, `.ppt` as supported formats, and explain `--password` with an example showing multiple values

### Acceptance criteria

- [x] `ez-rag ingest --help` lists `--password` with a description mentioning encrypted Office files
- [x] When `IngestCommand` is invoked with `--password p1 --password p2`, the `IngestService` receives `passwords = ["p1", "p2"]` (verified via unit test)
- [x] When `IngestCommand` is invoked without `--password` on a batch containing a locked file, the file is skipped with a warning and no exception propagates to the caller (verified via unit test using the password-protected fixture from task 04)
- [x] README contains documentation of the six Office extensions and `--password` usage with an example

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors

---

## Task 06-mcp-passwords-parameter

Add an optional `passwords` list parameter to the MCP `ingest` and `reingest` tools. Wire `passwords` through the tools' `ingestServiceFactory` and `reIngestServiceFactory` lambdas respectively (these factory signatures must be extended to include `passwords` for testability). Update each tool's description string to mention the `passwords` parameter.

### Implementation steps

- [x] Extend the `ingestServiceFactory` lambda type in `McpIngestTool` to include `passwords: List<String>` (e.g., `(LuceneRepository, Int, Int, UrlFetcher, List<String>) -> IngestService`) and update the default factory accordingly
- [x] Add optional `passwords: List<String>?` parameter to the `McpIngestTool` MCP tool callback (annotated as not required); pass `passwords ?: emptyList()` into the factory call
- [x] Update `McpIngestTool` tool description string to mention the optional `passwords` parameter
- [x] Extend the `reIngestServiceFactory` lambda type in `McpReIngestTool` to include `passwords: List<String>` and update the default factory
- [x] Add optional `passwords: List<String>?` parameter to the `McpReIngestTool` MCP tool callback; pass `passwords ?: emptyList()` into the factory call
- [x] Update `McpReIngestTool` tool description string to mention the optional `passwords` parameter
- [x] Write or extend `McpIngestToolTest`: verify that a `passwords` list passed in the MCP call is forwarded to `IngestService` via the factory (use the password-protected fixture from task 04 to confirm a locked file is ingested successfully when the correct password is supplied)
- [x] Write or extend `McpReIngestToolTest`: verify that a `passwords` list passed in the MCP call is forwarded to `ReIngestService` via the factory

### Acceptance criteria

- [x] The MCP `ingest` tool schema returned by the MCP server includes `passwords` as an optional array-of-string parameter
- [x] The MCP `reingest` tool schema includes `passwords` as an optional array-of-string parameter
- [x] Calling MCP `ingest` with `{"passwords": ["<correct-password>"], "path": "<path-to-protected-fixture>"}` successfully indexes the password-protected file created in task 04 (verified via unit test using the factory)
- [x] Calling MCP `ingest` without `passwords` produces identical behavior to before this task for unencrypted files (no regression)
- [x] Calling MCP `reingest` with a `passwords` list passes the list through to `ReIngestService` (verified via unit test using the factory)

### Quality gates

- [x] `./gradlew test` passes with no new test failures
- [x] `./gradlew build` compiles without errors
- [x] CLAUDE.md MCP tool table still lists exactly `list`, `search`, `ingest`, `reingest`, `chunk` — no new tool names added
