## Task 01-local-pdf-support

Replace the existing `pdf-to-markdown` subcommand end-to-end with a new unified `to-markdown` subcommand, carrying forward all PDF conversion functionality: picocli wiring, extension-based input detection, PDF option handling, all error paths, and full test coverage in `ToMarkdownCommandTest`. The old command and its tests are deleted.

### Implementation steps

- [x] Write test + implement: `to-markdown <pdf>` → non-empty stdout, exit 0
- [x] Write test + implement: `to-markdown <pdf> --mode rag` → no `**` in output, exit 0
- [x] Write test + implement: `to-markdown <pdf> --max-pages 1` → output shorter than full conversion, exit 0
- [x] Write test + implement: `to-markdown <pdf> --output-format xml` → output starts with `<`, exit 0
- [x] Write test + implement: `to-markdown <pdf> --mode invalid` → exit 1, non-empty stderr
- [x] Write test + implement: `to-markdown <pdf> --output-format invalid` → exit 1, non-empty stderr
- [x] Write test + implement: `to-markdown <pdf> --max-pages -1` → exit 1, non-empty stderr
- [x] Write test + implement: `to-markdown /nonexistent/file.pdf` → exit 1, non-empty stderr, no "Exception" in stdout
- [x] Write test + implement: `to-markdown file.docx` → exit 1, non-empty stderr (unknown extension)
- [x] Register `ToMarkdownCommand` in the root command, replacing `PdfToMarkdownCommand`
- [x] Delete `PdfToMarkdownCommand.kt` and `PdfToMarkdownCommandTest.kt`
- [x] Update `SubcommandTest`: replace `pdf-to-markdown` assertions with `to-markdown`

### Acceptance criteria

- [x] `to-markdown <pdf>` exits 0 and writes non-empty Markdown to stdout
- [x] `to-markdown <pdf> --mode rag` exits 0 with no `**` in output
- [x] `to-markdown <pdf> --max-pages 1` exits 0 with output shorter than full conversion
- [x] `to-markdown <pdf> --output-format xml` exits 0 with output starting with `<`
- [x] `to-markdown <pdf> --mode invalid` exits 1 with non-empty stderr
- [x] `to-markdown <pdf> --output-format invalid` exits 1 with non-empty stderr
- [x] `to-markdown <pdf> --max-pages -1` exits 1 with non-empty stderr
- [x] `to-markdown /nonexistent/file.pdf` exits 1 with non-empty stderr and no "Exception" in stdout
- [x] `to-markdown file.docx` exits 1 with non-empty stderr
- [x] `ez-rag --help` lists `to-markdown` and does not list `pdf-to-markdown`
- [x] `ez-rag to-markdown --help` exits 0

### Quality gates

- [x] Project compiles without errors or warnings
- [x] All tests pass

---

## Task 02-local-html-support

Extend `to-markdown` to also accept local HTML files (`.html`, `.htm`). Introduce PDF-only option validation so that passing `--mode`, `--max-pages`, or `--output-format` explicitly with a non-PDF input produces exit 1 with a clear error message identifying the option as PDF-only. Delegate HTML conversion to the same converter used by the ingestion pipeline.

### Implementation steps

- [x] Add a minimal HTML test resource to the test resources directory
- [x] Write test + implement: `to-markdown page.html` → non-empty stdout, exit 0
- [x] Write test + implement: `to-markdown page.htm` → non-empty stdout, exit 0
- [x] Write test + implement: `to-markdown page.html --mode readable` → exit 1, stderr identifies `--mode` as PDF-only
- [x] Write test + implement: `to-markdown page.html --output-format xml` → exit 1, stderr identifies `--output-format` as PDF-only
- [x] Write test + implement: `to-markdown page.html --max-pages 1` → exit 1, stderr identifies `--max-pages` as PDF-only
- [x] Extend extension detection to route `.html`/`.htm` to the HTML conversion pipeline
- [x] Implement PDF-only option validation: track whether each PDF-only option was explicitly supplied (not just defaulted) and reject with exit 1 if supplied on a non-PDF input

### Acceptance criteria

- [x] `to-markdown page.html` exits 0 and writes non-empty Markdown to stdout
- [x] `to-markdown page.htm` exits 0 and writes non-empty Markdown to stdout
- [x] `to-markdown page.html --mode readable` exits 1 with stderr containing a message that identifies `--mode` as PDF-only
- [x] `to-markdown page.html --output-format xml` exits 1 with stderr containing a message that identifies `--output-format` as PDF-only
- [x] `to-markdown page.html --max-pages 1` exits 1 with stderr containing a message that identifies `--max-pages` as PDF-only
- [x] `to-markdown page.htm --mode readable` exits 1 with non-empty stderr

### Quality gates

- [x] Project compiles without errors or warnings
- [x] All tests (including those from task 01) pass

---

## Task 03-url-support

Extend `to-markdown` to accept HTTP/HTTPS URLs, routing to the HTML or PDF pipeline based on the `Content-Type` header. For PDF URLs, write fetched bytes to a temp file, convert, then delete the temp file unconditionally in a `finally` block. Reject unsupported content types with exit 1. Inject `UrlFetcher` through the constructor so tests can supply a stub without network access.

### Implementation steps

- [x] Write test + implement: stub URL returning `text/html` → non-empty stdout, exit 0
- [x] Write test + implement: stub URL returning `application/pdf` → non-empty stdout, exit 0
- [x] Write test + implement: stub URL returning `application/zip` → exit 1, non-empty stderr
- [x] Write test: stub URL returning `text/html` with `--mode readable` explicitly set → exit 1, stderr identifies `--mode` as PDF-only (validates that PDF-only guard applies to URL HTML paths)
- [x] Implement URL detection: input starting with `http://` or `https://` takes the URL path
- [x] Implement HTML URL path: decode fetched bytes as UTF-8, pass to HTML converter
- [x] Implement PDF URL path: write bytes to temp file via `File.createTempFile`, convert via PDF pipeline, delete temp file in `finally`
- [x] Implement unsupported content-type error path with a message that includes the received content type

### Acceptance criteria

- [x] `to-markdown https://...` returning `text/html` exits 0 with non-empty stdout
- [x] `to-markdown https://...` returning `application/pdf` exits 0 with non-empty stdout
- [x] `to-markdown https://...` returning an unsupported content type exits 1 with non-empty stderr
- [x] `to-markdown https://...` returning `text/html` with `--mode readable` explicitly set exits 1 with stderr containing a PDF-only message
- [x] The PDF temp file is not present on disk after the command completes, including when conversion throws an exception

### Quality gates

- [x] Project compiles without errors or warnings
- [x] All tests (including those from tasks 01 and 02) pass

---

## Task 04-readme-update

Update the README to document `to-markdown` replacing `pdf-to-markdown`, covering all supported input types and noting which options are PDF-only.

### Implementation steps

- [x] Replace all occurrences of `pdf-to-markdown` with `to-markdown` in README.md
- [x] Document that `to-markdown` accepts local `.pdf` files, local `.html`/`.htm` files, and HTTP/HTTPS URLs
- [x] Add a usage example for each supported input type
- [x] Document `--mode`, `--max-pages`, and `--output-format` options and note they are PDF-only
- [x] Note that HTML output from `to-markdown` mirrors what the ingestion pipeline produces

### Acceptance criteria

- [x] README.md contains no references to `pdf-to-markdown`
- [x] README.md includes a usage example for a local PDF file
- [x] README.md includes a usage example for a local HTML file
- [x] README.md includes a usage example for an HTTP/HTTPS URL
- [x] README.md documents which options are PDF-only

### Quality gates

- [x] README.md is syntactically valid Markdown (no unclosed fences or broken link syntax)
