# Tasks: Chunking-Sensitive Eval Corpus

Three new eval scenario directories committed to `src/test/resources/eval/`. No code changes required — `EvalScenariosTest` auto-discovers any directory containing `questions.yaml`.

**Dependency order:** Tasks 01 and 02 are independent and can be grabbed in parallel. Task 03 depends on Task 01.

**Aggregate threshold risk:** `EvalScenariosTest` has a fixed test requiring the aggregate weighted Hit Rate@3 across all scenarios to exceed 0.7. Before merging any of these tasks, verify that the new scenarios do not pull the aggregate below 0.7. With ~106 questions in existing scenarios scoring ~0.98, up to 75 new questions at ~0.50 Hit Rate@3 still lands at ~0.78. If actual scores are unexpectedly lower, revisit document and question design before merging.

---

## Task 01-chunking-md-scenario

Create the `chunking-md` eval scenario end-to-end: synthetic Markdown documents designed to produce measurably below-1.0 scores with the current heading-based chunker, plus a `questions.yaml` that uses `expected_chunk_contains` to detect table-splitting and prose-splitting failures.

The current Markdown chunker creates one chunk per heading section regardless of length. A `##` section containing a large comparison table followed by several paragraphs becomes a single oversized chunk; semantic search degrades because the chunk covers too broad a topic. Questions that target specific table cells or specific paragraph-final sentences will surface this degradation via `expected_chunk_contains` scoring.

Topic domain: software architecture patterns, API design, release engineering — distinct from the existing `complex-md` topics (databases, ML, networking) to avoid score contamination.

### Implementation steps

- [x] Create 3 synthetic Markdown documents (~3000–5000 words each) in `src/test/resources/eval/chunking-md/`. Each document must contain: at least one `##` section with a comparison table (5+ rows, 4+ columns) followed by multiple prose paragraphs (the section must be at least 2000 words so it becomes an oversized single chunk); at least one run of 3–4 consecutive prose paragraphs with no sub-heading where the answer fact appears only in the third or fourth paragraph; at least one inline citation or reference block (`[Author, Year]` or a `## References` section) attached to a specific claim.
- [x] Create one distractor Markdown document (topically unrelated domain, e.g. cooking, geography, or art history).
- [x] Create one hard-negative Markdown document: same technical vocabulary (software, APIs, releases), answers none of the scenario questions.
- [x] Create `src/test/resources/eval/chunking-md/questions.yaml` with 15–25 questions across all relevant documents. Table-cell questions: use `expected_chunk_contains` with the exact cell value string as it appears in the document. Paragraph-boundary questions: use `expected_chunk_contains` with the key concluding sentence from the third or fourth paragraph of a long heading-section. Citation/reference questions and cross-section questions: use `expected_sources` only. No `thresholds:` block.

### Acceptance criteria

- [x] `EvalScenariosTest.discoverScenarios()` includes `chunking-md` (the directory contains `questions.yaml` and all referenced files exist).
- [x] `questions.yaml` contains 15–25 questions.
- [x] At least 5 questions use `expected_chunk_contains` targeting specific table cell values from the comparison tables (verifiable by checking each phrase exists verbatim in the document).
- [x] At least 5 questions use `expected_chunk_contains` targeting the third or fourth paragraph of a heading section that is at least 2000 words long (verifiable by checking the paragraph position in the document and the word count of its containing section).
- [x] At least 2 questions use `expected_sources` only — either citation/reference lookups or cross-section questions.
- [x] Hard-negative document shares vocabulary with relevant documents but answers none of the questions (verifiable by checking that no `expected_sources` entry names the hard-negative file).
- [x] No `thresholds:` block in `questions.yaml`.
- [x] `./gradlew test -Dtags=eval` includes the `chunking-md` scenario in its output and completes without crashing.

### Quality gates

- [x] `questions.yaml` is valid YAML parseable by `EvalCorpusLoader` without errors.
- [x] Every `file:` entry in `questions.yaml` resolves to an existing file in `chunking-md/`.
- [x] No `thresholds:` key present in `questions.yaml`.
- [x] Every string in `expected_chunk_contains` appears verbatim (case-insensitive) as a substring in the corresponding `expected_sources` document(s).
- [x] No question has both `expected_chunk_contains` and `expected_answer_contains` set simultaneously — when `expected_chunk_contains` is non-empty, `EvalMetricsCalculator.isHit()` ignores `expected_answer_contains`.

---

## Task 02-chunking-txt-scenario

Create the `chunking-txt` eval scenario end-to-end: synthetic plain-text documents using only blank lines as paragraph separators, with key answer facts placed in the final 1–2 sentences of paragraphs that `TokenTextSplitter(chunkSize=1000, overlap=200)` would cut before. Questions use `expected_chunk_contains` to detect mid-paragraph splits.

`PlainTextDocumentReader` feeds the entire file as a single document to `TokenTextSplitter`. A paragraph that begins after character ~4500 of a long section and whose answer fact appears only in its last sentence will be cut if the paragraph is long enough to cross the 1000-token boundary. Questions that use `expected_chunk_contains` on those final sentences score 0 under the current implementation.

Topic domain: self-contained factual domain unrelated to the Markdown scenarios (e.g. astronomy, ecology, or history of science).

### Implementation steps

- [x] Create 2–3 plain-text documents (~2000–4000 words each) in `src/test/resources/eval/chunking-txt/` using blank lines as the only paragraph separator. At least one document must have a paragraph that begins after approximately character 4500 of the document (roughly token 900 at ~5 chars/token) with the key answer fact exclusively in the last 1–2 sentences of that paragraph; verify the paragraph's start position manually by counting characters before committing.
- [x] Create one hard-negative plain-text document: same vocabulary, answers none of the questions.
- [x] Create `src/test/resources/eval/chunking-txt/questions.yaml` with 15–25 questions. Paragraph-boundary questions use `expected_chunk_contains` with the exact key sentence. No `thresholds:` block.

### Acceptance criteria

- [x] `EvalScenariosTest.discoverScenarios()` includes `chunking-txt`.
- [x] `questions.yaml` contains 15–25 questions.
- [x] At least 5 questions use `expected_chunk_contains` targeting sentences from paragraphs that begin after character 4500 of their document (verifiable by checking the character offset of the paragraph start in the file).
- [x] Every document file contains no ATX headings (no line starting with `#`) and no Markdown pipe table syntax (no line containing `|` adjacent to a `---` separator row).
- [x] Hard-negative document answers none of the questions (verifiable by checking no `expected_sources` entry names the hard-negative file).
- [x] No `thresholds:` block in `questions.yaml`.
- [x] `./gradlew test -Dtags=eval` includes the `chunking-txt` scenario in its output and completes without crashing.

### Quality gates

- [x] `questions.yaml` is valid YAML parseable by `EvalCorpusLoader` without errors.
- [x] Every `file:` entry in `questions.yaml` resolves to an existing file in `chunking-txt/`.
- [x] No `thresholds:` key present in `questions.yaml`.
- [x] Every string in `expected_chunk_contains` appears verbatim (case-insensitive) as a substring in the corresponding `expected_sources` document(s).
- [x] No document file has any line starting with `#` (no ATX headings).

---

## Task 03-chunking-pdf-scenario

**Depends on Task 01.**

Create the `chunking-pdf` eval scenario end-to-end: the same synthetic documents from `chunking-md` rendered as PDFs, with at least one comparison table that spans two pages so that `PagePdfDocumentReader` splits the table mid-row. Questions mirror `chunking-md` with `expected_sources` referencing `.pdf` filenames.

The PDF documents need not be regenerated by a build tool — render them offline (e.g. via Pandoc, LibreOffice, or a browser print-to-PDF) and commit the binary `.pdf` files. The multi-page table condition is the key stress: the current reader produces one document per page, so a table row that starts on page N and ends on page N+1 becomes two disconnected fragments, causing hard retrieval failures.

### Implementation steps

- [x] Render the 3 content Markdown documents from Task 01 as PDFs and place them in `src/test/resources/eval/chunking-pdf/`. Ensure at least one comparison table is laid out across two pages in the rendered output (adjust document length or table position if necessary).
- [x] Render the hard-negative Markdown document from Task 01 as a PDF. Also render the distractor document if it will be listed in `questions.yaml`; otherwise omit it.
- [x] Create `src/test/resources/eval/chunking-pdf/questions.yaml` mirroring the questions from `chunking-md/questions.yaml`: same question ids, same question text, same `expected_chunk_contains` values. Change all `expected_sources` entries to reference the corresponding `.pdf` filenames. Do not carry over `expected_answer_contains` if present — set only `expected_chunk_contains` and `expected_sources`. No `thresholds:` block.

### Acceptance criteria

- [x] `EvalScenariosTest.discoverScenarios()` includes `chunking-pdf`.
- [x] Question count and ids in `chunking-pdf/questions.yaml` match `chunking-md/questions.yaml`.
- [x] All `expected_sources` entries reference `.pdf` filenames only.
- [x] `expected_chunk_contains` values on table-cell and paragraph-boundary questions are identical to those in `chunking-md/questions.yaml` (same phrases, same questions).
- [x] At least one PDF file contains a comparison table that visually spans two pages (verifiable by opening the PDF).
- [x] No `thresholds:` block in `questions.yaml`.
- [x] `./gradlew test -Dtags=eval` includes the `chunking-pdf` scenario in its output and completes without crashing.

### Quality gates

- [x] `questions.yaml` is valid YAML parseable by `EvalCorpusLoader` without errors.
- [x] Every `file:` entry in `questions.yaml` resolves to an existing `.pdf` file in `chunking-pdf/`.
- [x] No `thresholds:` key present in `questions.yaml`.
- [x] Every string in `expected_chunk_contains` appears verbatim (case-insensitive) as a substring in the text content of the corresponding source `.pdf` file (verifiable by extracting PDF text with `pdftotext` or equivalent).
