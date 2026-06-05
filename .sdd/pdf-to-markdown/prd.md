# PRD: `pdf-to-markdown` Subcommand

## Problem Statement

When ingesting a PDF file, ez-rag silently converts it to Markdown using a structure-preserving heuristic pipeline. Users have no way to inspect the output of that conversion without actually ingesting the document into a store. If the conversion produces unexpected results — wrong headings, garbled tables, missing sections — the user has no feedback loop to diagnose or understand the problem.

## Solution

Add a `pdf-to-markdown` subcommand that exposes the existing PDF conversion pipeline as a standalone, store-free operation. The user passes a single PDF file; the converted text is written to stdout. Optional flags control the conversion mode and output format, making it useful both for quick inspection and for deep layout debugging.

## User Stories

1. As a user, I want to run `ez-rag pdf-to-markdown my-doc.pdf` and see the Markdown that would be produced during ingestion, so that I can verify the conversion before committing to ingestion.
2. As a user, I want the output on stdout so that I can pipe it to a pager (`| less`), a file (`> out.md`), or another tool.
3. As a user, I want a `--mode readable` option (the default) that produces human-readable Markdown with full inline formatting, so that the output renders well in a Markdown viewer.
4. As a user, I want a `--mode rag` option that produces RAG-optimised Markdown (no inline formatting noise, no ToC, normalised table spans), so that I can preview exactly what text will be chunked and embedded.
5. As a user, I want a `--max-pages N` option so that I can quickly inspect just the first N pages of a large document without waiting for the full conversion.
6. As a user, I want a `--output-format xml` option that emits positional XML (x, y, font, text per element) instead of Markdown, so that I can debug layout-detection issues such as incorrect heading levels or missed table columns.
7. As a user, I want `--output-format markdown` to be the default so that the common case requires no extra flags.
8. As a user, I want `ez-rag pdf-to-markdown --help` to show a concise description and all available flags, so that I can discover the command without reading the README.
9. As a user, I want the command to exit with a non-zero code and print a clear error to stderr when the file does not exist or cannot be opened, so that I can detect failures in scripts.
10. As a user, I want the command to work without a vector store, embedding model, or API credentials, so that I can use it on any machine without extra setup.
11. As a developer, I want the command registered in the root `ez-rag` help listing, so that users can discover it via `ez-rag --help`.

## Implementation Decisions

### Module 1 — `PdfMarkdown` facade (modify)

The existing `PdfMarkdown` object in the `ingestion/pdf` package exposes only `toMarkdown()`. A `toXml()` method will be added alongside it.

The XML output renders each `TextElement` as an XML element with attributes for position (`x`, `y`, `endX`), font metadata (`fontSize`, `font`), and text content. Page boundaries are wrapped in `<page>` elements. No external XML library is needed — the output is produced by simple string building over the already-extracted `TextElement` list from `extractFilteredPageElements()`.

The `toXml()` method does not accept a `ConversionOptions` argument because XML output bypasses the Markdown conversion step entirely; all elements are emitted as-is.

### Module 2 — `PdfToMarkdownCommand` (new)

A new picocli subcommand class in the `command` package, following the same `@Command` + `@Component` + `Callable<Int>` pattern used by all other commands.

Constructor parameters follow the testability convention: `outputWriter: PrintWriter` and `errorWriter: PrintWriter` default to `System.out` and `System.err` respectively, so tests can capture output via `StringWriter` without touching real streams.

The command does not declare `@ParentCommand` or reference `EzRagCommand` fields, because it needs no store path, provider, or embedding model.

Flags:

| Flag | Type | Default | Enum values |
|---|---|---|---|
| `--mode` | enum | `readable` | `readable`, `rag` |
| `--max-pages` | Int | `0` (unlimited) | — |
| `--output-format` | enum | `markdown` | `markdown`, `xml` |

`--max-pages 0` is mapped to `Int.MAX_VALUE` before calling the facade, keeping the CLI default human-friendly.

On success the command writes converted text to `outputWriter` and returns exit code `0`. On failure (file not found, PDF load error) it writes a short message to `errorWriter` and returns exit code `1`.

### Module 3 — `EzRagCommand` (modify)

`PdfToMarkdownCommand::class` is added to the `subcommands` array in `EzRagCommand`.

## Testing Decisions

**What makes a good test**: tests assert observable output (what is written to the `PrintWriter`) and exit codes. They do not assert on internal state, private fields, or intermediate data structures. A test should read like a specification of user-visible behaviour.

**Modules to test**:

- `PdfToMarkdownCommand` — unit tests using the existing `sample.pdf` in `src/test/resources/documents/`. Tests inject a `StringWriter`-backed `PrintWriter` to capture stdout. Scenarios:
  - Default invocation produces non-empty Markdown output and exits `0`
  - `--mode rag` produces output without inline formatting markers
  - `--output-format xml` produces output that starts with an XML tag
  - `--max-pages 1` produces output covering at most one page
  - A non-existent file exits `1` and writes to stderr

- `SubcommandTest` (existing) — add a `pdf-to-markdown help exits 0` test and add `pdf-to-markdown` to the `help lists all subcommands` assertion.

**Prior art**: `PdfDocumentReaderTest` shows how to load `sample.pdf` from test resources via `javaClass.getResource("/documents/sample.pdf")`. `SubcommandTest` shows the picocli `CommandLine(EzRagCommand()).execute(...)` harness used for subcommand smoke tests.

## Out of Scope

- Multiple input files or directory traversal
- Writing output to a file (`--output-file`); redirect via shell is sufficient
- Exposing `ConversionOptions` fine-tuning knobs (`RuleTuning` fields) as CLI flags
- Image extraction (`--output-format images`)
- Any integration with the vector store, embedding models, or ingestion pipeline

## Further Notes

The `pdf-to-markdown` command name uses a hyphen (not underscore or space) to match the naming style of other compound subcommands in the project (`mcp-server`, `download-eval-corpus`).

The XML output format is intentionally kept simple and human-readable — it is a debugging aid, not a machine interchange format. Its exact schema is an implementation detail and is not part of any public API contract.
