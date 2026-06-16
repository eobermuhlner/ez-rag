## Task 01-outputformatter-chunk-format

Add three new overloads of `formatText`, `formatJson`, and `formatXml` to `OutputFormatter`, each accepting `List<Document>` (Spring AI). These overloads are distinct from the existing `SearchResult` overloads by parameter type; they have no `score` or `source` field, and instead render all keys present in each `Document.metadata` as optional key-value pairs alongside the chunk text. This layer has no I/O and can be fully tested in isolation before any command is wired up.

### Implementation steps

- [x] Add `formatText(chunks: List<Document>): String` — header `[N] chunk=X key=value...` (metadata keys in insertion order, omitting absent keys), chunk text, blank line between chunks
- [x] Add `formatJson(chunks: List<Document>): String` — `{"chunks":[{"chunkIndex":N,...optional metadata...,"content":"..."}]}`; optional metadata keys present only when found in `Document.metadata`
- [x] Add `formatXml(chunks: List<Document>): String` — `<results>` root with `<result index="N" chunk="X" ...optional attributes...>text</result>`; optional metadata as additional attributes, XML-escaped
- [x] Extend `OutputFormatterTest` with unit tests for all three methods and edge cases (empty list, missing optional keys, XML escaping, JSON escaping)

### Acceptance criteria

- [x] `formatText` first header line reads `[1] chunk=0` followed by any metadata key-value pairs present in `Document.metadata` (e.g. `[1] chunk=0 heading_path=Introduction > Overview`)
- [x] `formatJson` output parses as valid JSON; root object has `chunks` array; each entry always contains `chunkIndex` and `content`; optional fields (e.g. `headingPath`) present only when in metadata
- [x] `formatXml` output is valid XML; root element is `<results>`; each chunk produces a `<result>` element with `index` and `chunk` attributes; optional metadata appears as additional attributes
- [x] A `Document` with no `heading_path` in metadata produces output with no `heading_path` key in any of the three formats
- [x] Empty `List<Document>` produces `""` for text, `{"chunks":[]}` for JSON, `<results></results>` for XML

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.rag.OutputFormatterTest"` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 02-to-document-command

Replace `ToMarkdownCommand` (`to-markdown`) with a new `ToDocumentCommand` (`to-document`) that supports every file type the ingestion pipeline handles. PDF-specific options are renamed with a `--pdf-` prefix. `ToMarkdownCommand`, its test, and all references to `to-markdown` in existing tests are removed.

### Implementation steps

- [x] Create `ToDocumentCommand` with options `--pdf-mode readable|rag` (default `readable`) and `--pdf-max-pages N` (default `0` = unlimited); accept a single file path or HTTP/HTTPS URL as a positional argument
- [x] Before reading, call `registry.supports(extension)` (or equivalent content-type check for URLs); if the type is unsupported, print an error to stderr and return exit code 1
- [x] Instantiate `DocumentReaderRegistry` with a very large chunk size so the whole document is effectively one block; join all resulting `Document.text` values and print to stdout
- [x] Reject `--pdf-mode` and `--pdf-max-pages` when the input is not a PDF: print an error to stderr and return exit code 1
- [x] Handle HTTP/HTTPS URLs using the same content-type detection logic as the existing `ToMarkdownCommand`
- [x] Register `ToDocumentCommand` in `EzRagCommand`; remove `ToMarkdownCommand` registration; delete `ToMarkdownCommand.kt` and `ToMarkdownCommandTest.kt`
- [x] Update `SubcommandTest`: replace the `assertThat(output).contains("to-markdown")` assertion with assertions for `to-document` and `to-chunks`; replace the `to-markdown help exits 0` test with a `to-document help exits 0` test
- [x] Create `ToDocumentCommandTest`; add RTF and CSV test fixtures to `src/test/resources/fixtures/` if not present

### Acceptance criteria

- [x] `to-document /fixtures/sample.docx` produces non-empty output containing `#` Markdown heading syntax
- [x] `to-document /fixtures/sample.pptx` produces non-empty output
- [x] `to-document /fixtures/sample.xlsx` produces Markdown table syntax (`|`)
- [x] `to-document` on a CSV fixture produces Markdown table syntax (`|`)
- [x] `to-document` on an RTF fixture produces non-empty plain text
- [x] `to-document /documents/sample.pdf --pdf-mode rag` produces different output than `to-document /documents/sample.pdf` (default readable mode)
- [x] `to-document` with `--pdf-mode rag` on a non-PDF file exits non-zero with a message on stderr
- [x] `to-document unknown.xyz` exits non-zero with a message on stderr
- [x] `to-document nonexistent.pdf` exits non-zero with a message on stderr
- [x] `to-markdown` is no longer a recognised subcommand (exits non-zero or help does not list it)

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.ToDocumentCommandTest"` passes with no failures
- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.SubcommandTest"` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 03-to-chunks-command

Add a new `ToChunksCommand` (`to-chunks`) that runs a single file or URL through the full chunking pipeline and prints every chunk with its metadata. Works without an initialised store. Output format is controlled by `--output-format text|json|xml` using the `OutputFormatter` overloads added in Task 01.

### Implementation steps

- [x] Create `ToChunksCommand` with options: `--output-format text|json|xml` (default `text`), `--chunk-size` (default `1000`), `--chunk-overlap` (default `200`), `--pdf-mode readable|rag` (default `readable`), `--pdf-max-pages N` (default `0`)
- [x] Instantiate `DocumentReaderRegistry` with user-supplied `chunkSize` and `chunkOverlap`; call `registry.read(file)` to obtain the chunked `List<Document>`
- [x] Handle HTTP/HTTPS URLs via same content-type detection as `to-document`; call `registry.supports()` and exit non-zero for unsupported types
- [x] Route to `OutputFormatter.formatText/formatJson/formatXml(chunks)` based on `--output-format`; print to stdout; errors to stderr
- [x] Register `ToChunksCommand` in `EzRagCommand`; no `LuceneRepository` or store directory required
- [x] Write `ToChunksCommandTest` covering all output formats, chunk-size effect, heading metadata presence/absence, and error paths

### Acceptance criteria

- [x] Default text output for `/documents/sample.md` contains `[1] chunk=0` in the first header line
- [x] `--output-format xml` output is valid XML with one `<result>` element per chunk
- [x] `--output-format json` output parses as valid JSON with a `chunks` array
- [x] `--chunk-size 50 --chunk-overlap 0` on a multi-paragraph text fixture produces more chunks than running with default `--chunk-size 1000`
- [x] `/documents/sample.md` (which has headings): at least one chunk's output includes `heading_path`
- [x] A plain text fixture (`/documents/sample.txt`): no chunk output includes `heading_path`
- [x] Unsupported extension → non-zero exit, message on stderr
- [x] Non-existent file → non-zero exit, message on stderr
- [x] Command succeeds with no store directory present in the working directory

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.command.ToChunksCommandTest"` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 04-readme-update

Update README.md to document the two new commands (`to-document`, `to-chunks`) and remove the now-deleted `to-markdown` section. No code changes — this is a documentation-only task.

### Implementation steps

- [x] Remove the `to-markdown` section from README (all references to it as an active command)
- [x] Add `to-document` section: describe supported input types, `--pdf-mode`, `--pdf-max-pages`, and at least one example invocation per option
- [x] Add `to-chunks` section: describe `--output-format text|json|xml`, `--chunk-size`, `--chunk-overlap`, `--pdf-mode`, `--pdf-max-pages`, and one example per output format

### Acceptance criteria

- [x] README contains no reference to `to-markdown` as an active command
- [x] README `to-document` section shows an example with `--pdf-mode` and an example with `--pdf-max-pages`
- [x] README `to-chunks` section shows separate examples for `--output-format text`, `--output-format json`, and `--output-format xml`

### Quality gates

- [x] `./gradlew build` passes (ensures no compilation regressions from accompanying changes)
