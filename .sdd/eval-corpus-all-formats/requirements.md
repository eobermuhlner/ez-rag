# Requirements: Eval Corpus — All Supported File Formats

## Problem Statement

The eval corpus covers only three file formats: plain text (`.txt`), Markdown (`.md`), and PDF (`.pdf`). Every other format supported by the ingestion layer — AsciiDoc, RST, HTML, RTF, Word, PowerPoint, Excel, JSON, JSONL, JSONC, CSV, XML, Kotlin, Java, TypeScript, and unknown plain-text files — has no eval scenario. A regression in any of those readers (wrong content stripped, chunks silently dropped, unexpected encoding issues) would pass the eval suite undetected.

## Solution

Add one chunking eval scenario per format for every format supported by the ingestion layer, plus one scenario for unknown-extension plain-text files that exercises the fallback chunker. Each scenario contains a single target document (no distractors) and three questions with `expected_chunk_contains` assertions verifying that text from the beginning, middle, and end of the document is retrievable. The existing discovery-based `EvalScenariosTest` picks up all new scenarios automatically — no test code changes are required.

## User Stories

1. As a developer improving the AsciiDoc reader, I want the eval suite to include a `chunking-adoc/` scenario, so that I can verify my change did not regress AsciiDoc ingestion.
2. As a developer improving the RST reader, I want the eval suite to include a `chunking-rst/` scenario, so that RST ingestion regressions are caught automatically.
3. As a developer improving the HTML reader, I want the eval suite to include a `chunking-html/` scenario, so that HTML content extraction regressions are caught automatically.
4. As a developer improving the RTF reader, I want the eval suite to include a `chunking-rtf/` scenario, so that RTF ingestion regressions are caught automatically.
5. As a developer improving the Word reader, I want the eval suite to include a `chunking-docx/` scenario, so that Word document ingestion regressions are caught automatically.
6. As a developer improving the PowerPoint reader, I want the eval suite to include a `chunking-pptx/` scenario, so that PowerPoint ingestion regressions are caught automatically.
7. As a developer improving the JSON reader, I want the eval suite to include a `chunking-json/` scenario, so that JSON ingestion regressions are caught automatically.
8. As a developer improving the JSONL reader, I want the eval suite to include a `chunking-jsonl/` scenario, so that JSONL ingestion regressions are caught automatically.
9. As a developer improving the JSONC reader, I want the eval suite to include a `chunking-jsonc/` scenario with a file containing comments, so that the comment-aware parser path is exercised.
10. As a developer improving the CSV reader, I want the eval suite to include a `chunking-csv/` scenario, so that CSV ingestion regressions are caught automatically.
11. As a developer improving the Excel reader, I want the eval suite to include a `chunking-xlsx/` scenario, so that Excel ingestion regressions are caught automatically.
12. As a developer improving the XML reader, I want the eval suite to include a `chunking-xml/` scenario, so that XML ingestion regressions are caught automatically.
13. As a developer improving the Kotlin source code reader, I want the eval suite to include a `chunking-kt/` scenario, so that Kotlin source code ingestion regressions are caught automatically.
14. As a developer improving the Java source code reader, I want the eval suite to include a `chunking-java/` scenario, so that Java source code ingestion regressions are caught automatically.
15. As a developer improving the TypeScript source code reader, I want the eval suite to include a `chunking-ts/` scenario, so that TypeScript source code ingestion regressions are caught automatically.
16. As a developer testing the plain-text fallback chunker, I want the eval suite to include a `chunking-unknown/` scenario whose document has an unrecognised extension (e.g. `.xyz`), so that the fallback path is covered by the eval suite.
17. As a developer, I want each scenario to assert a threshold of 100% Hit Rate@3, Recall@3, and MRR, so that any chunk silently dropped by a reader causes a clear test failure.
18. As a developer, I want each scenario to contain exactly one target document with no distractors, so that the test isolates format-specific ingestion quality from retrieval ranking.
19. As a developer, I want each scenario to contain exactly three questions with `expected_chunk_contains` assertions targeting text from the beginning, middle, and end of the document, so that the full document is verified to be ingested.
20. As a developer, I want all new scenarios to be discovered automatically by the existing `EvalScenariosTest` without any code changes, so that adding a format scenario is a corpus-only operation.

## User Acceptance Tests

1. Given a `.adoc` file with three clearly separated sections, when the eval suite runs the `chunking-adoc` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
2. Given a `.rst` file with three clearly separated sections, when the eval suite runs the `chunking-rst` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
3. Given an `.html` file with three clearly separated sections, when the eval suite runs the `chunking-html` scenario, then all three `expected_chunk_contains` assertions pass and no HTML markup appears in any retrieved chunk.
4. Given a `.rtf` file with three clearly separated sections, when the eval suite runs the `chunking-rtf` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
5. Given a `.docx` file with three clearly separated sections, when the eval suite runs the `chunking-docx` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
6. Given a `.pptx` file with content spread across multiple slides, when the eval suite runs the `chunking-pptx` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
7. Given a `.json` file containing a structured dataset, when the eval suite runs the `chunking-json` scenario, then all three `expected_chunk_contains` assertions targeting specific data values pass with Hit Rate@3 of 1.0.
8. Given a `.jsonl` file containing newline-delimited JSON records, when the eval suite runs the `chunking-jsonl` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
9. Given a `.jsonc` file containing JSON with inline comments, when the eval suite runs the `chunking-jsonc` scenario, then all three `expected_chunk_contains` assertions targeting data values pass and no comment text appears in any retrieved chunk.
10. Given a `.csv` file containing tabular data, when the eval suite runs the `chunking-csv` scenario, then all three `expected_chunk_contains` assertions targeting specific cell values pass with Hit Rate@3 of 1.0.
11. Given an `.xlsx` file containing tabular data, when the eval suite runs the `chunking-xlsx` scenario, then all three `expected_chunk_contains` assertions targeting specific cell values pass with Hit Rate@3 of 1.0.
12. Given an `.xml` file with structured content, when the eval suite runs the `chunking-xml` scenario, then all three `expected_chunk_contains` assertions pass and no XML tags appear in any retrieved chunk.
13. Given a `.kt` file containing Kotlin source code with multiple class and function declarations, when the eval suite runs the `chunking-kt` scenario, then all three `expected_chunk_contains` assertions targeting specific identifiers or doc-comment phrases pass with Hit Rate@3 of 1.0.
14. Given a `.java` file containing Java source code with equivalent declarations, when the eval suite runs the `chunking-java` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
15. Given a `.ts` file containing TypeScript source code with equivalent declarations, when the eval suite runs the `chunking-ts` scenario, then all three `expected_chunk_contains` assertions pass with Hit Rate@3 of 1.0.
16. Given a `.xyz` file containing plain text with three clearly separated sections, when the eval suite runs the `chunking-unknown` scenario, then all three `expected_chunk_contains` assertions pass, confirming the fallback chunker handled the unrecognised extension.
17. Given the full eval suite is run, when all 16 new scenarios are included, then the aggregate weighted Hit Rate@3 across all scenarios remains above 0.7.

## Definition of Done

- All 16 new scenario directories exist under `src/test/resources/eval/` with the naming convention `chunking-<extension>/`.
- Each scenario directory contains exactly one document file and one `questions.yaml`.
- Each `questions.yaml` contains exactly three questions, each with `expected_chunk_contains`, and a `thresholds:` block with `recall_at_k: 1.0`, `hit_rate_at_k: 1.0`, and `mrr: 1.0`.
- All binary document files (`.rtf`, `.docx`, `.pptx`, `.xlsx`) are committed as pre-built binary fixtures.
- The eval suite (`./gradlew test -Peval`) passes with all 16 new scenarios meeting their thresholds.
- No changes were made to `EvalScenariosTest` or any other test code.
- No regression in the existing three chunking scenarios (`chunking-txt`, `chunking-md`, `chunking-pdf`).

## Out of Scope

- Scenarios for extension aliases (`.doc`, `.xls`, `.htm`, `.xhtml`, `.asciidoc`, `.kts`, `.tsx`, `.js`, `.jsx`): the primary extension scenario covers the shared reader.
- Factual, complex, hard-negatives, or multi-chunk scenario variants for the new formats: those can be added once a format-specific failure mode warrants a dedicated scenario type.
- Programmatic generation of binary fixtures: files are authored externally (e.g. LibreOffice) and committed as static test resources, consistent with the existing `sample.pdf` approach.
- Any changes to `EvalModel`, `EvalCorpusLoader`, `EvalEngine`, `EvalMetricsCalculator`, `EvalReporter`, or `EvalScenariosTest`.

## Further Notes

- The `chunking-pptx` document content is organised per-slide rather than per-section, since PowerPoint is a presentation format. This is acceptable — the test still verifies content from the beginning, middle, and end of the presentation is retrievable.
- For the JSONC scenario the `expected_chunk_contains` phrases must target data values, not comment text, since comments are stripped before chunking.
- Thresholds of 1.0 for a single-document scenario with no distractors are the appropriate baseline. If a format's reader produces variable output (e.g. due to table rendering differences), the threshold can be relaxed per scenario after the first successful eval run.
- Binary files should be kept small (under 50 KB each) to avoid inflating the repository size. The content need only be long enough to produce at least three distinct chunk boundaries.

---

## Technical Annex
> Written against codebase as of: 2026-06-18

### Architectural Decisions

#### Scenario directory naming and structure

Each scenario follows the convention established by `chunking-txt/`, `chunking-md/`, and `chunking-pdf/`:

```
src/test/resources/eval/chunking-<ext>/
  <document>.<ext>
  questions.yaml
```

#### `questions.yaml` schema for the new scenarios

All 16 new scenarios use the single-document, no-distractor pattern:

```yaml
documents:
  - file: <document>.<ext>
    role: relevant

questions:
  - id: q1
    question: "<question targeting beginning of document>"
    expected_sources: ["<document>.<ext>"]
    expected_chunk_contains: ["<phrase from beginning>"]

  - id: q2
    question: "<question targeting middle of document>"
    expected_sources: ["<document>.<ext>"]
    expected_chunk_contains: ["<phrase from middle>"]

  - id: q3
    question: "<question targeting end of document>"
    expected_sources: ["<document>.<ext>"]
    expected_chunk_contains: ["<phrase from end>"]

thresholds:
  recall_at_k: 1.0
  hit_rate_at_k: 1.0
  mrr: 1.0
```

#### Document content families

**Prose family** (adoc, rst, html, rtf, docx, pptx): a shared short reference document converted to each format. Suggested content: a three-section technology overview (e.g. introduction to distributed systems, concurrency models, and fault tolerance) with distinct vocabulary per section, enabling specific `expected_chunk_contains` phrases. Each section should be 200–400 words. The pptx variant organises the same content across three slides.

**Structured family** (json, jsonl, jsonc, csv, xlsx, xml): a small product catalog dataset with at least 10 items, each having name, category, price, and description fields. Questions target specific product names and price values. The jsonc variant adds inline comments on several fields — `expected_chunk_contains` must not reference comment text.

**Code family** (kt, java, ts): the same simple utility class translated to each language — a `StringUtils` class with three public methods (`truncate`, `padLeft`, `countWords`), each with a doc-comment. Questions target method names and doc-comment phrases.

**Fallback family** (unknown `.xyz`): a plain text file with the same three-section prose content as the prose family. The `.xyz` extension is not registered in `DocumentReaderRegistry`, so `BinaryDetector` will run; the file must pass the binary check (no null bytes). The fallback path calls `PlainTextDocumentReader`.

#### Binary file authoring

Files for `.rtf`, `.docx`, `.pptx`, and `.xlsx` are created externally (e.g. LibreOffice Writer/Impress/Calc) from the prose/structured content, saved, and committed to `src/test/resources/eval/<scenario>/`. File size target: under 50 KB each.

#### No code changes required

`EvalScenariosTest.discoverScenarios()` at line 32–47 of `EvalScenariosTest.kt` already scans `eval/` for all `questions.yaml` files and adds them as parameterized test cases. Adding a new directory under `eval/` is sufficient.

`DocumentReaderRegistry.read()` at line 56–67 of `DocumentReaderRegistry.kt` already implements the unknown-extension fallback: if the extension is not in the `readers` map, it runs `BinaryDetector.isBinary()` and, if the file is text, delegates to `PlainTextDocumentReader`.

### Automated Testing Decisions

Good tests assert on the externally observable outcome — whether the eval metrics meet the threshold — not on which internal reader class was invoked.

All 16 new scenarios are exercised by the existing `EvalScenariosTest` parameterized test (`scenario meets thresholds`) and the aggregate test (`aggregate weighted Hit Rate@3 across all scenarios exceeds 0_7`). No new test classes or methods are needed.

| Scenario | Test coverage | Notes |
|---|---|---|
| `chunking-adoc` through `chunking-ts` | `EvalScenariosTest` parameterized | `@Tag("eval")`, auto-discovered |
| `chunking-unknown` | `EvalScenariosTest` parameterized | Exercises `DocumentReaderRegistry` fallback path |
| All 16 combined | `EvalScenariosTest` aggregate | Contributes to weighted Hit Rate@3 floor |

Prior art for the test structure: `EvalScenariosTest` (already exists, no changes needed). Prior art for binary file fixtures: `src/test/resources/eval/chunking-pdf/` (PDF files committed as binaries).
