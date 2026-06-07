## Problem Statement

Users need to inspect what the ingestion pipeline actually produces from a document before committing to an ingest run. The existing `pdf-to-markdown` subcommand partially covers this for local PDF files, but leaves HTML files and web URLs completely unsupported. Having a PDF-only tool also forces users to remember the format-specific command name, and gives no inspection path for the majority of web-sourced content ingested as HTML.

## Solution

Replace `pdf-to-markdown` with a single unified `to-markdown` subcommand that accepts a local PDF file, a local HTML file, or an HTTP/HTTPS URL, detects the input format automatically, and prints the converted Markdown to stdout. PDF-specific options (`--mode`, `--max-pages`, `--output-format`) remain available and are validated at runtime — passing them with non-PDF input produces a clear error rather than silently ignoring them. This gives users one tool that faithfully mirrors what the ingestion pipeline will see, regardless of input format.

## User Stories

1. As a developer, I want to run `ez-rag to-markdown report.pdf` so that I can see the Markdown that will be fed into the chunker before ingesting a local PDF.
2. As a developer, I want to run `ez-rag to-markdown page.html` so that I can verify that a local HTML file is correctly converted to Markdown before ingesting it.
3. As a developer, I want to run `ez-rag to-markdown https://example.com/docs` so that I can preview the Markdown that would be produced from a live web page before ingesting it.
4. As a developer, I want to run `ez-rag to-markdown https://example.com/report.pdf` so that I can preview the Markdown from a PDF file served over HTTP.
5. As a developer, I want to use `--mode rag` with a PDF input so that I can see the RAG-optimised conversion with bold markers stripped.
6. As a developer, I want to use `--mode readable` with a PDF input so that I can see the human-readable conversion with formatting preserved.
7. As a developer, I want to use `--max-pages N` with a PDF input so that I can limit conversion to the first N pages for a quick inspection.
8. As a developer, I want to use `--output-format xml` with a PDF input so that I can inspect the raw positional layout data for debugging purposes.
9. As a developer, I want a clear error message when I pass `--mode` with an HTML file or URL so that I understand this option is PDF-only.
10. As a developer, I want a clear error message when I pass `--max-pages` with an HTML file or URL so that I understand this option is PDF-only.
11. As a developer, I want a clear error message when I pass `--output-format` with an HTML file or URL so that I understand this option is PDF-only.
12. As a developer, I want a clear error message when I pass a URL whose content type is neither `text/html` nor `application/pdf` so that I understand the input format is unsupported.
13. As a developer, I want a clear error message when I pass a local file whose extension is not `.pdf`, `.html`, or `.htm` so that I understand what formats are supported.
14. As a developer, I want a clear error message when I pass a local file that does not exist so that I can correct the path before re-running.
15. As a developer, I want `--max-pages -1` to be rejected with a clear error message so that invalid values are caught before the conversion starts.
16. As a developer, I want `--mode invalid-value` to be rejected with a clear error message so that typos in mode names are caught immediately.
17. As a developer, I want `--output-format invalid-value` to be rejected with a clear error message so that typos in format names are caught immediately.
18. As a developer, I want `to-markdown` to write its result to stdout and all error messages to stderr so that I can pipe the Markdown into other tools without mixing in diagnostic output.
19. As a developer, I want `to-markdown` to exit with code 0 on success and code 1 on any error so that scripts can detect failures reliably.
20. As a developer, I want `ez-rag --help` to list `to-markdown` and not `pdf-to-markdown` so that I discover the unified command when exploring the tool.
21. As a developer, I want `ez-rag to-markdown --help` to describe all options, their defaults, and which are PDF-only so that I understand the command without having to try options blindly.
22. As a developer, I want the `to-markdown` HTML output to be identical to what the ingestion pipeline produces so that the inspection result is trustworthy and not a divergent approximation.

## User Acceptance Tests

1. Given a valid local PDF file, when I run `ez-rag to-markdown <file>.pdf`, then the command exits with code 0 and prints non-empty Markdown to stdout.
2. Given a valid local HTML file, when I run `ez-rag to-markdown <file>.html`, then the command exits with code 0 and prints non-empty Markdown to stdout.
3. Given a URL that returns `text/html`, when I run `ez-rag to-markdown <url>`, then the command exits with code 0 and prints non-empty Markdown to stdout.
4. Given a URL that returns `application/pdf`, when I run `ez-rag to-markdown <url>`, then the command exits with code 0 and prints non-empty Markdown to stdout.
5. Given a valid local PDF file, when I run `ez-rag to-markdown --mode rag <file>.pdf`, then the command exits with code 0 and the output contains no bold markers (`**`).
6. Given a valid local PDF file with multiple pages, when I run `ez-rag to-markdown --max-pages 1 <file>.pdf`, then the command exits with code 0 and the output is shorter than running without `--max-pages`.
7. Given a valid local PDF file, when I run `ez-rag to-markdown --output-format xml <file>.pdf`, then the command exits with code 0 and the output starts with `<`.
8. Given a valid local HTML file, when I run `ez-rag to-markdown --mode readable <file>.html`, then the command exits with code 1 and stderr contains a message indicating `--mode` is only valid for PDF input.
9. Given a valid local HTML file, when I run `ez-rag to-markdown --output-format xml <file>.html`, then the command exits with code 1 and stderr contains a message indicating `--output-format` is only valid for PDF input.
10. Given a URL that returns an unsupported content type (e.g. `application/zip`), when I run `ez-rag to-markdown <url>`, then the command exits with code 1 and stderr contains a non-empty error message.
11. Given a file path with an unrecognised extension (e.g. `.docx`), when I run `ez-rag to-markdown <file>.docx`, then the command exits with code 1 and stderr contains a non-empty error message.
12. Given a path to a file that does not exist, when I run `ez-rag to-markdown <path>`, then the command exits with code 1 and stderr contains a non-empty error message with no stack trace.
13. Given the argument `--max-pages -1`, when I run `ez-rag to-markdown --max-pages -1 <file>.pdf`, then the command exits with code 1 and stderr contains a non-empty error message.
14. Given the argument `--mode typo`, when I run `ez-rag to-markdown --mode typo <file>.pdf`, then the command exits with code 1 and stderr contains a non-empty error message.
15. When I run `ez-rag --help`, then `to-markdown` appears in the subcommand list and `pdf-to-markdown` does not.
16. When I run `ez-rag to-markdown --help`, then the output exits with code 0 and describes `--mode`, `--max-pages`, and `--output-format` options with their PDF-only constraint noted.

## Out of Scope

- `stdin` (`-`) as an input source.
- Format conversion targets other than Markdown (e.g. plain text, JSON).
- HTML-specific conversion modes or fine-grained options (the HTML converter has no tunable parameters).
- Caching of fetched URL content across invocations.
- Configuring redirect-following behaviour (Jsoup already follows redirects).
- Word processor formats (DOCX, ODT, RTF, etc.).
- Download progress reporting for PDF-from-URL.
- A deprecation alias for `pdf-to-markdown` — the old command is removed entirely.

## Further Notes

`HtmlToMarkdownConverter` is already used inside `HtmlDocumentReader` for the live ingestion pipeline. Running `to-markdown` on an HTML input will produce exactly what the pipeline sees — no divergence is possible as long as both code paths instantiate the same converter class.

The PDF pipeline (`PdfMarkdown`) accepts a `java.io.File` rather than a stream. This is why a PDF served over HTTP must be written to a temporary file before conversion. This is an existing constraint in `PdfMarkdown` and is not changed by this feature.

---

## Technical Annex
> Written against codebase as of: 2026-06-07

### Architectural Decisions

#### ToMarkdownCommand (new — replaces PdfToMarkdownCommand)

**Location:** `src/main/kotlin/ch/obermuhlner/ezrag/command/ToMarkdownCommand.kt`

**Picocli declaration:**
```kotlin
@Command(
    name = "to-markdown",
    mixinStandardHelpOptions = true,
    description = ["Convert a PDF or HTML file (or URL) to Markdown and write the result to stdout."]
)
class ToMarkdownCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) : Callable<Int>
```

**Parameters and options (identical naming to PdfToMarkdownCommand for PDF parity):**
- `@Parameters(index = "0") lateinit var input: String` — file path or HTTP/HTTPS URL
- `@Option(names = ["--mode"]) var mode: String = "readable"` — PDF-only
- `@Option(names = ["--max-pages"]) var maxPages: Int = 0` — PDF-only; 0 = unlimited
- `@Option(names = ["--output-format"]) var outputFormat: String = "markdown"` — PDF-only

**Input-type detection logic (in `call()`):**
1. If `input` starts with `http://` or `https://` → URL path.
2. Otherwise → local file path.
   - Extension `.pdf` → PDF path.
   - Extension `.html` or `.htm` → HTML path.
   - Anything else → exit 1 with message listing supported extensions.

**URL path:**
- Call `urlFetcher.fetch(input)` to get a `FetchResult`.
- `contentType` starts with `text/html` → convert `FetchResult.bytes.toString(Charsets.UTF_8)` via `HtmlToMarkdownConverter`.
- `contentType` starts with `application/pdf` → write `FetchResult.bytes` to `File.createTempFile(...)`, convert via `PdfMarkdown`, delete temp file in `finally`.
- Anything else → exit 1 with message including the unsupported content type.

**Validation guard for PDF-only options:**
Check `--mode` (non-default) and `--output-format` (non-default) against non-PDF inputs; if violated, write `"Error: --<option> is only valid for PDF input."` to errorWriter and return 1.
- Default for `--mode` is `"readable"` and for `--output-format` is `"markdown"`. Use a sentinel or explicit flag tracking to distinguish "user supplied" from "defaulted".
- Simplest approach: add `@Option(names = ["--mode"]) var modeSet: Boolean = false` companion fields, or check option value against a hardcoded default after picocli parsing. The existing `PdfToMarkdownCommand` does not need this guard; the new command does.

**Valid mode values:** `"readable"`, `"rag"` → map to `ConversionOptions.READABLE` / `ConversionOptions.RAG`.  
**Valid output-format values:** `"markdown"`, `"xml"`.  
**Invalid values:** write error to errorWriter, return 1.  
**`--max-pages` < 0:** write error to errorWriter, return 1.

**Delegation (no new conversion logic):**
- HTML → `HtmlToMarkdownConverter().convert(htmlString)`
- PDF → existing call pattern from `PdfToMarkdownCommand.call()` (lines 48–65 of current file)

#### EzRagCommand (modified)

Replace `PdfToMarkdownCommand::class` with `ToMarkdownCommand::class` in the `subcommands` array inside `EzRagCommand.kt`.

#### PdfToMarkdownCommand (deleted)

Remove `src/main/kotlin/ch/obermuhlner/ezrag/command/PdfToMarkdownCommand.kt`. No alias or shim.

### Automated Testing Decisions

**What makes a good test:** Tests assert observable external behaviour — stdout content, stderr content, and exit code. One assertion per behaviour. Do not assert internal routing or implementation details.

**Prior art:** `PdfToMarkdownCommandTest` at `src/test/kotlin/ch/obermuhlner/ezrag/command/PdfToMarkdownCommandTest.kt`. Uses `PrintWriter(StringWriter())` injection for both `outputWriter` and `errorWriter`, then reads `StringWriter.toString()` to assert output. This exact pattern must be reused in `ToMarkdownCommandTest`.

#### ToMarkdownCommandTest (new)

**Location:** `src/test/kotlin/ch/obermuhlner/ezrag/command/ToMarkdownCommandTest.kt`

Test cases (one assertion per case):

| Input | Options | Expected exit | Expected observable |
|---|---|---|---|
| Local PDF (test resource) | (none) | 0 | stdout non-empty |
| Local HTML (test resource) | (none) | 0 | stdout non-empty |
| Local PDF | `--mode rag` | 0 | stdout contains no `**` |
| Local PDF (multi-page) | `--max-pages 1` | 0 | stdout shorter than without flag |
| Local PDF | `--output-format xml` | 0 | stdout starts with `<` |
| Local HTML | `--mode readable` | 1 | stderr mentions PDF-only |
| Local HTML | `--output-format xml` | 1 | stderr mentions PDF-only |
| Unknown extension (`.docx`) | (none) | 1 | stderr non-empty |
| Nonexistent file | (none) | 1 | stderr non-empty, no "Exception" in stdout |
| Local PDF | `--max-pages -1` | 1 | stderr non-empty |
| Local PDF | `--mode invalid` | 1 | stderr non-empty |
| Local PDF | `--output-format invalid` | 1 | stderr non-empty |
| Stub URL → `text/html` | (none) | 0 | stdout non-empty |
| Stub URL → `application/pdf` | (none) | 0 | stdout non-empty |
| Stub URL → `application/zip` | (none) | 1 | stderr non-empty |

**Stub URL fetcher:** implement a local `FakeUrlFetcher` inner class inside the test file that returns a pre-configured `FetchResult`. For the PDF case, use the bytes of the same test PDF resource used elsewhere in the suite. For HTML, return a minimal HTML string as bytes.

#### SubcommandTest (modified)

- Remove assertion that `pdf-to-markdown` appears in `ez-rag --help` output.
- Add assertion that `to-markdown` appears in `ez-rag --help` output.
- Add assertion that `ez-rag to-markdown --help` exits with code 0.

#### PdfToMarkdownCommandTest (deleted)

Remove `src/test/kotlin/ch/obermuhlner/ezrag/command/PdfToMarkdownCommandTest.kt`. All coverage is superseded by `ToMarkdownCommandTest`.
