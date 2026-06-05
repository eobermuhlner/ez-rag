# Tasks: `pdf-to-markdown` Subcommand

## Task [01-basic-markdown-output]

A new `pdf-to-markdown` subcommand is registered in the root command and converts a single PDF file to Markdown on stdout. This is the foundation all subsequent tasks build on. The command is discoverable via `ez-rag --help`, exits 0 on success, and follows the established `@Command` + `@Component` + `Callable<Int>` pattern with constructor-injected `PrintWriter` for testability. A `@ParentCommand` reference to `EzRagCommand` is declared so that inherited global flags (`--verbose`, `--stack-trace`) work correctly.

### Implementation steps

- [ ] Write a failing test: instantiate `PdfToMarkdownCommand` with a `StringWriter`-backed `PrintWriter`, call it with `sample.pdf`, assert output is non-empty and exit code is 0
- [ ] Write a failing test: `SubcommandTest` — `pdf-to-markdown --help` exits 0
- [ ] Write a failing test: `SubcommandTest` — `help lists all subcommands` asserts `pdf-to-markdown` appears
- [ ] Create `PdfToMarkdownCommand` with `@Command(name = "pdf-to-markdown")`, `@Component`, constructor-injected `outputWriter` and `errorWriter` (defaulting to `System.out`/`System.err`), and `@ParentCommand private var parent: EzRagCommand?`
- [ ] Implement `call()`: resolve PDF file, call `PdfMarkdown.toMarkdown()`, write to `outputWriter`, return 0
- [ ] Register `PdfToMarkdownCommand::class` in `EzRagCommand`'s `subcommands` array
- [ ] Verify `SpringWiringTest` still passes (Spring component scan picks up the new command)

### Acceptance criteria

- [ ] `pdf-to-markdown sample.pdf` exits 0 and at least one line of non-whitespace text is written to stdout
- [ ] Output is recognisable Markdown (contains at least one non-empty line of text)
- [ ] `pdf-to-markdown --help` exits 0 and usage text is printed
- [ ] `ez-rag --help` output contains the string `pdf-to-markdown`
- [ ] `--verbose` flag is accepted without error (inherited via `@ParentCommand`)

### Quality gates

- [ ] `./gradlew compileKotlin` produces zero warnings
- [ ] `./gradlew test` passes

---

## Task [02-conversion-mode]

The `--mode` flag is added to `pdf-to-markdown`, selecting between `readable` (default, full inline formatting) and `rag` (inline formatting stripped, ToC excluded, table spans normalised). Invalid values are rejected by a guard in `call()` — not picocli enum parsing — to keep the flag as a string and produce a consistent error message on stderr.

### Implementation steps

- [ ] Write a failing test: `--mode rag` on `sample.pdf` produces output that does not contain `**` inline-bold markers
- [ ] Write a failing test: `--mode invalid` exits 1 and errorWriter contains an error message
- [ ] Add `@Option(names = ["--mode"])` as a `String` field defaulting to `"readable"` in `PdfToMarkdownCommand`
- [ ] In `call()`, map `"readable"` → `ConversionOptions.READABLE`, `"rag"` → `ConversionOptions.RAG`, anything else → write error to `errorWriter` and return 1
- [ ] Pass the resolved `ConversionOptions` to `PdfMarkdown.toMarkdown()`

### Acceptance criteria

- [ ] Omitting `--mode` is equivalent to `--mode readable` (both produce output with no guard error)
- [ ] `--mode rag` produces non-empty output that contains no `**word**` or `*word*` inline-formatting patterns (verified on a PDF that has bold text; use `machine_learning.pdf` from eval resources which is known to have formatted content)
- [ ] `--mode readable` produces non-empty output (same bar as Task 01 baseline)
- [ ] `--mode invalid` exits 1 and stderr contains an error message identifying the unknown mode
- [ ] `--mode rag` and `--mode readable` both exit 0

### Quality gates

- [ ] `./gradlew compileKotlin` produces zero warnings
- [ ] `./gradlew test` passes

---

## Task [03-page-limit-and-error-handling]

The `--max-pages` flag limits how many pages are processed. `0` (the default) means unlimited (`Int.MAX_VALUE` internally). Negative values are rejected by a guard in `call()`. Error handling is added for missing or unreadable files: exit code 1, human-readable message on stderr. Use `machine_learning.pdf` from eval test resources for multi-page assertions, as it is known to be multi-page.

### Implementation steps

- [ ] Write a failing test: `--max-pages 1` on `machine_learning.pdf` exits 0 and output is shorter than without the flag
- [ ] Write a failing test: a nonexistent file path exits 1 and errorWriter contains an error message
- [ ] Write a failing test: `--max-pages -1` exits 1 and errorWriter contains an error message
- [ ] Add `@Option(names = ["--max-pages"])` as an `Int` field defaulting to `0` in `PdfToMarkdownCommand`
- [ ] In `call()`, guard: if `maxPages < 0` write error to `errorWriter` and return 1; map `0` → `Int.MAX_VALUE`
- [ ] Wrap the PDF conversion in a try/catch; on any exception write a message to `errorWriter` and return 1 (do not print a stack trace unless `parent?.stackTrace` is true)

### Acceptance criteria

- [ ] `--max-pages 1` on `machine_learning.pdf` exits 0 and produces non-empty output
- [ ] Output of `--max-pages 1` is strictly shorter than output of the same file without the flag (multi-page PDF confirmed)
- [ ] `--max-pages 0` (default) exits 0 and produces non-empty output
- [ ] A path to a nonexistent file exits 1
- [ ] On failure, stderr contains a human-readable message (not a raw Java exception class)
- [ ] `--max-pages -1` exits 1 and stderr contains an error message

### Quality gates

- [ ] `./gradlew compileKotlin` produces zero warnings
- [ ] `./gradlew test` passes

---

## Task [04-xml-output]

The `--output-format` flag adds an `xml` option alongside the default `markdown`. XML output bypasses the Markdown converter and instead serialises the positional `TextElement` data extracted from the PDF, grouped into `<page>` elements, each containing child elements with `x`, `y`, `font`, `fontSize`, and text attributes. This requires adding `toXml()` to the `PdfMarkdown` facade. The `--mode` flag is intentionally ignored when `--output-format xml` is selected — XML always emits raw positional data regardless of conversion mode. Invalid values for `--output-format` are rejected by a guard in `call()`.

### Implementation steps

- [ ] Write a failing test: `--output-format xml sample.pdf` exits 0 and output starts with `<`
- [ ] Write a failing test: XML output contains at least one `<page` element with at least one child element that has non-empty text content
- [ ] Write a failing test: `--output-format invalid` exits 1 and errorWriter contains an error message
- [ ] Add `toXml()` to `PdfMarkdown` in `ingestion/pdf/`: call `extractFilteredPageElements()`, render each page as `<page number="N">` containing one element per `TextElement` with attributes `x`, `y`, `endX`, `fontSize`, `font`, and the text as element content; no external XML library needed
- [ ] Add `@Option(names = ["--output-format"])` as a `String` field defaulting to `"markdown"` in `PdfToMarkdownCommand`
- [ ] In `call()`, map `"markdown"` → call `toMarkdown()`, `"xml"` → call `toXml()`, anything else → write error to `errorWriter` and return 1
- [ ] Document in `PdfToMarkdownCommand`'s `--output-format` option description that `--mode` is ignored when format is `xml`

### Acceptance criteria

- [ ] `--output-format xml sample.pdf` exits 0 and the first character of stdout is `<`
- [ ] XML output contains at least one `<page` string
- [ ] Each `<page>` element contains at least one child element with non-empty text content
- [ ] `--output-format markdown` (explicit) exits 0 and produces Markdown output (same bar as Task 01)
- [ ] `--output-format xml --mode rag` exits 0 without error (mode is accepted but has no effect on XML output)
- [ ] `--output-format invalid` exits 1 and stderr contains an error message identifying the unknown format

### Quality gates

- [ ] `./gradlew compileKotlin` produces zero warnings
- [ ] `./gradlew test` passes
