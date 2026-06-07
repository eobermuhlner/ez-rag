## Task 01-local-pdf-support

Replace the existing `pdf-to-markdown` subcommand end-to-end with a new unified `to-markdown` subcommand, carrying forward all PDF conversion functionality: picocli wiring, extension-based input detection, PDF option handling, all error paths, and full test coverage in `ToMarkdownCommandTest`. The old command and its tests are deleted.

### Implementation steps

- [ ] Write test + implement: `to-markdown <pdf>` → non-empty stdout, exit 0
- [ ] Write test + implement: `to-markdown <pdf> --mode rag` → no `**` in output, exit 0
- [ ] Write test + implement: `to-markdown <pdf> --max-pages 1` → output shorter than full conversion, exit 0
- [ ] Write test + implement: `to-markdown <pdf> --output-format xml` → output starts with `<`, exit 0
- [ ] Write test + implement: `to-markdown <pdf> --mode invalid` → exit 1, non-empty stderr
- [ ] Write test + implement: `to-markdown <pdf> --output-format invalid` → exit 1, non-empty stderr
- [ ] Write test + implement: `to-markdown <pdf> --max-pages -1` → exit 1, non-empty stderr
- [ ] Write test + implement: `to-markdown /nonexistent/file.pdf` → exit 1, non-empty stderr, no "Exception" in stdout
- [ ] Write test + implement: `to-markdown file.docx` → exit 1, non-empty stderr (unknown extension)
- [ ] Register `ToMarkdownCommand` in the root command, replacing `PdfToMarkdownCommand`
- [ ] Delete `PdfToMarkdownCommand.kt` and `PdfToMarkdownCommandTest.kt`
- [ ] Update `SubcommandTest`: replace `pdf-to-markdown` assertions with `to-markdown`

### Acceptance criteria

- [ ] `to-markdown <pdf>` exits 0 and writes non-empty Markdown to stdout
- [ ] `to-markdown <pdf> --mode rag` exits 0 with no `**` in output
- [ ] `to-markdown <pdf> --max-pages 1` exits 0 with output shorter than full conversion
- [ ] `to-markdown <pdf> --output-format xml` exits 0 with output starting with `<`
- [ ] `to-markdown <pdf> --mode invalid` exits 1 with non-empty stderr
- [ ] `to-markdown <pdf> --output-format invalid` exits 1 with non-empty stderr
- [ ] `to-markdown <pdf> --max-pages -1` exits 1 with non-empty stderr
- [ ] `to-markdown /nonexistent/file.pdf` exits 1 with non-empty stderr and no "Exception" in stdout
- [ ] `to-markdown file.docx` exits 1 with non-empty stderr
- [ ] `ez-rag --help` lists `to-markdown` and does not list `pdf-to-markdown`
- [ ] `ez-rag to-markdown --help` exits 0

### Quality gates

- [ ] Project compiles without errors or warnings
- [ ] All tests pass

---

## Task 02-local-html-support

Extend `to-markdown` to also accept local HTML files (`.html`, `.htm`). Introduce PDF-only option validation so that passing `--mode`, `--max-pages`, or `--output-format` explicitly with a non-PDF input produces exit 1 with a clear error message identifying the option as PDF-only. Delegate HTML conversion to the same converter used by the ingestion pipeline.

### Implementation steps

- [ ] Add a minimal HTML test resource to the test resources directory
- [ ] Write test + implement: `to-markdown page.html` → non-empty stdout, exit 0
- [ ] Write test + implement: `to-markdown page.htm` → non-empty stdout, exit 0
- [ ] Write test + implement: `to-markdown page.html --mode readable` → exit 1, stderr identifies `--mode` as PDF-only
- [ ] Write test + implement: `to-markdown page.html --output-format xml` → exit 1, stderr identifies `--output-format` as PDF-only
- [ ] Write test + implement: `to-markdown page.html --max-pages 1` → exit 1, stderr identifies `--max-pages` as PDF-only
- [ ] Extend extension detection to route `.html`/`.htm` to the HTML conversion pipeline
- [ ] Implement PDF-only option validation: track whether each PDF-only option was explicitly supplied (not just defaulted) and reject with exit 1 if supplied on a non-PDF input

### Acceptance criteria

- [ ] `to-markdown page.html` exits 0 and writes non-empty Markdown to stdout
- [ ] `to-markdown page.htm` exits 0 and writes non-empty Markdown to stdout
- [ ] `to-markdown page.html --mode readable` exits 1 with stderr containing a message that identifies `--mode` as PDF-only
- [ ] `to-markdown page.html --output-format xml` exits 1 with stderr containing a message that identifies `--output-format` as PDF-only
- [ ] `to-markdown page.html --max-pages 1` exits 1 with stderr containing a message that identifies `--max-pages` as PDF-only
- [ ] `to-markdown page.htm --mode readable` exits 1 with non-empty stderr

### Quality gates

- [ ] Project compiles without errors or warnings
- [ ] All tests (including those from task 01) pass

---

## Task 03-url-support

Extend `to-markdown` to accept HTTP/HTTPS URLs, routing to the HTML or PDF pipeline based on the `Content-Type` header. For PDF URLs, write fetched bytes to a temp file, convert, then delete the temp file unconditionally in a `finally` block. Reject unsupported content types with exit 1. Inject `UrlFetcher` through the constructor so tests can supply a stub without network access.

### Implementation steps

- [ ] Write test + implement: stub URL returning `text/html` → non-empty stdout, exit 0
- [ ] Write test + implement: stub URL returning `application/pdf` → non-empty stdout, exit 0
- [ ] Write test + implement: stub URL returning `application/zip` → exit 1, non-empty stderr
- [ ] Write test: stub URL returning `text/html` with `--mode readable` explicitly set → exit 1, stderr identifies `--mode` as PDF-only (validates that PDF-only guard applies to URL HTML paths)
- [ ] Implement URL detection: input starting with `http://` or `https://` takes the URL path
- [ ] Implement HTML URL path: decode fetched bytes as UTF-8, pass to HTML converter
- [ ] Implement PDF URL path: write bytes to temp file via `File.createTempFile`, convert via PDF pipeline, delete temp file in `finally`
- [ ] Implement unsupported content-type error path with a message that includes the received content type

### Acceptance criteria

- [ ] `to-markdown https://...` returning `text/html` exits 0 with non-empty stdout
- [ ] `to-markdown https://...` returning `application/pdf` exits 0 with non-empty stdout
- [ ] `to-markdown https://...` returning an unsupported content type exits 1 with non-empty stderr
- [ ] `to-markdown https://...` returning `text/html` with `--mode readable` explicitly set exits 1 with stderr containing a PDF-only message
- [ ] The PDF temp file is not present on disk after the command completes, including when conversion throws an exception

### Quality gates

- [ ] Project compiles without errors or warnings
- [ ] All tests (including those from tasks 01 and 02) pass

---

## Task 04-readme-update

Update the README to document `to-markdown` replacing `pdf-to-markdown`, covering all supported input types and noting which options are PDF-only.

### Implementation steps

- [ ] Replace all occurrences of `pdf-to-markdown` with `to-markdown` in README.md
- [ ] Document that `to-markdown` accepts local `.pdf` files, local `.html`/`.htm` files, and HTTP/HTTPS URLs
- [ ] Add a usage example for each supported input type
- [ ] Document `--mode`, `--max-pages`, and `--output-format` options and note they are PDF-only
- [ ] Note that HTML output from `to-markdown` mirrors what the ingestion pipeline produces

### Acceptance criteria

- [ ] README.md contains no references to `pdf-to-markdown`
- [ ] README.md includes a usage example for a local PDF file
- [ ] README.md includes a usage example for a local HTML file
- [ ] README.md includes a usage example for an HTTP/HTTPS URL
- [ ] README.md documents which options are PDF-only

### Quality gates

- [ ] README.md is syntactically valid Markdown (no unclosed fences or broken link syntax)
