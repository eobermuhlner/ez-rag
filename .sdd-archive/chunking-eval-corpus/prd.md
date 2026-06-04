# PRD: Chunking-Sensitive Eval Corpus

## Problem Statement

The existing eval scenarios (`complex-md`, `complex-pdf`, `factual`, `factual-md`, `hard-negatives`, `multi-chunk`) all score near 1.0 under the current implementation (overall Hit@3 = 0.98). This ceiling effect makes it impossible to measure the impact of changes to document parsing and chunking. An upcoming rewrite of three parsing/chunking pipelines — Markdown (paragraph, table, citation-aware), PDF (convert to Markdown then apply the same chunking), and plain text (blank-line paragraph splitting) — needs a measurable quality baseline to confirm improvements and catch future regressions.

## Solution

Three new synthetic eval corpus scenarios committed to `src/test/resources/eval/`:

- **`chunking-md`** — Markdown documents with tables inside long heading-sections and multi-paragraph prose blocks. Questions target specific table cells and paragraph-boundary content using `expected_chunk_contains`, which the current heading-based chunker buries in oversized chunks (MRR degradation) or the `TokenTextSplitter` cuts across (hard failures).
- **`chunking-pdf`** — Identical content in PDF format. The current page-by-page reader splits multi-page tables mid-row, causing hard retrieval failures. Questions are the same as `chunking-md` with `.pdf` source names.
- **`chunking-txt`** — Plain-text documents with natural blank-line paragraph boundaries. The current `TokenTextSplitter` cuts paragraphs mid-sentence near the token limit; questions target content that lands at these boundaries.

No thresholds are set initially. The scenarios run in metrics-only mode until the new chunking implementations are in place; thresholds are committed after the first passing run.

No changes to the eval engine, corpus loader, metrics calculator, or reporter are required — the existing infrastructure already discovers and runs all scenarios automatically via `EvalScenariosTest`.

## User Stories

1. As a developer implementing Markdown paragraph/table/citation-aware chunking, I want a scenario that scores measurably below 1.0 with the current heading-based chunker, so that I can confirm my implementation actually improves retrieval quality rather than guessing.
2. As a developer implementing PDF-to-Markdown conversion + chunking, I want a scenario that reliably fails with the current page-by-page PDF reader (due to multi-page table splitting), so that I have a concrete quality bar to clear.
3. As a developer implementing blank-line paragraph splitting for plain text, I want a scenario that scores measurably below 1.0 with the current `TokenTextSplitter`, so that I can verify the new implementation keeps paragraphs intact.
4. As a developer making any change to document parsing or chunking, I want to run `./gradlew test -Dtags=eval` and see per-scenario Recall@3, MRR, and Hit@3 for the new scenarios, so that I can detect regressions before merging.
5. As a developer reviewing a PR, I want the eval scenarios to distinguish PDF, Markdown, and plain-text failures independently, so that I can narrow down which parser code path regressed.
6. As a developer, I want `expected_chunk_contains` set on table-targeting questions, so that a hit is counted only when the retrieved chunk actually contains the expected cell value — not merely when the source file appears somewhere in the results.
7. As a developer, I want `expected_chunk_contains` set on paragraph-boundary questions, so that splits that discard the final sentence of a paragraph count as a failure even when the source file itself is retrieved.
8. As a developer, I want the new scenarios to have no `thresholds:` block initially, so that they run as metrics-only observations until the new chunking implementations are in place.
9. As a developer, I want thresholds committed in a follow-up commit immediately after the new chunking implementations pass the scenarios, so that the quality floor is pinned and future regressions break the build.
10. As a developer, I want the scenario documents to be synthetic and committed to the repository, so that the tests are fully reproducible without downloading or licensing external content.
11. As a developer, I want the `chunking-md` and `chunking-pdf` scenarios to use identical topics and questions (differing only in file extension), so that PDF conversion quality is directly comparable to native Markdown parsing quality.
12. As a developer, I want each scenario document to be long enough to produce 5–15 chunks under the current implementation, so that there is genuine competition between chunks and retrieval rank matters.
13. As a developer, I want each scenario to include at least one hard-negative document (topically similar but not answering any question), so that the scenarios measure discrimination as well as recall.
14. As a developer, I want the `chunking-md` and `chunking-pdf` documents to contain comparison tables with 5+ rows and 4+ columns inside heading-sections, so that the scenarios reliably stress table boundary chunking.
15. As a developer, I want at least one question per scenario to target a fact in the second half of a table that would be split by naive token-based chunking, so that the scenario detects table-splitting regressions.
16. As a developer, I want at least one question per scenario to target a multi-sentence paragraph whose key conclusion appears in the final sentences, so that mid-paragraph splits count as failures.
17. As a developer, I want citation/reference questions in `chunking-md` and `chunking-pdf`, so that citation blocks detached from their context by chunking are also measured.
18. As a power user running `ez-rag eval <corpus-dir>`, I want the new scenarios to appear in the same output table as all other scenarios, so that I can see all format-specific quality metrics in one view.

## Implementation Decisions

### No code changes to the eval framework

The existing `EvalScenariosTest` discovers scenario directories automatically by scanning `src/test/resources/eval/` for subdirectories containing `questions.yaml`. The `EvalCorpusLoader`, `EvalEngine`, `EvalMetricsCalculator`, and `EvalReporter` require no changes. Adding the three new scenarios is purely a matter of creating new resource directories.

### `expected_chunk_contains` hit semantics (existing behaviour)

`EvalMetricsCalculator.isHit()` already implements the following: when `expected_chunk_contains` is non-empty on a question, a hit is scored only when a retrieved chunk's content contains at least one of the listed phrases (case-insensitive). When `expected_chunk_contains` is empty, a hit is scored when the chunk's source filename matches `expected_sources`. This distinction is central to the new scenarios: table-cell and paragraph-boundary questions use `expected_chunk_contains` to require that the specific content survived chunking and appeared in the retrieved chunk.

### Three scenario directories

**`chunking-md`** — 3–4 synthetic Markdown documents, ~3000–5000 words each, producing approximately 5–15 chunks under the current heading-based chunker. Required structural elements per document:
- At least one `##` section containing a comparison table with 5+ rows and 4+ columns followed by several paragraphs of prose; the section is long enough that the current implementation produces one oversized chunk.
- At least one sequence of 3–4 consecutive paragraphs (no sub-heading) where the answer fact appears in the third or fourth paragraph; the `TokenTextSplitter` fallback would split this block mid-paragraph.
- At least one citation/reference block attached to a specific claim.

One distractor document (unrelated topic) and one hard-negative document (same technical domain, shares vocabulary with relevant documents but answers no questions).

**`chunking-pdf`** — Same document content and questions as `chunking-md`, rendered as PDFs. The PDF documents must contain at least one table that spans two pages, so that the current `PagePdfDocumentReader` splits the table mid-row. Questions reference `.pdf` filenames in `expected_sources`. No thresholds initially.

**`chunking-txt`** — 2–3 synthetic plain-text documents, ~2000–4000 words each, using blank lines as the only paragraph separator. At least one document has a key fact buried in the final 1–3 sentences of a paragraph that begins near token 900 of a ~1200-token section, so that `TokenTextSplitter` (chunk size 1000, overlap 200) cuts the paragraph before the key sentence. Questions target those final sentences using `expected_chunk_contains`.

### Document topics

The `chunking-md` / `chunking-pdf` documents use a different topic domain from the existing `complex-md` / `complex-pdf` scenarios (which cover database systems, machine learning, and network protocols) to avoid score contamination when both scenario sets are run together. Suggested topics: software architecture patterns, API design, and release engineering. The hard-negative document covers a related but non-answering topic (e.g., DevOps tooling that mentions the same software but not the specific facts being asked about).

The `chunking-txt` documents use a self-contained factual domain unrelated to the Markdown scenarios.

### Question design per scenario

Each scenario has 15–25 questions spread across the relevant documents:
- **Table-cell questions** (use `expected_chunk_contains`): ask for a specific cell value by identifying the row and column uniquely. Example: "What is the time complexity of algorithm X?" where the answer is a specific cell in a large table. These will fail with the current impl when the table is split.
- **Paragraph-boundary questions** (use `expected_chunk_contains`): ask for the conclusion of a multi-paragraph argument where the conclusion sentence appears after the token boundary. These will fail with the current `TokenTextSplitter`.
- **Citation questions** (use `expected_sources` only): ask what a cited work covers; the citation string is distinctive enough to be retrievable but may be separated from context by naive chunking.
- **Cross-section questions** (use `expected_sources` only): require identifying which section of a document contains a fact, testing that the heading context is preserved in the chunk.

### No thresholds initially

The `thresholds:` block is omitted from all three `questions.yaml` files. After the new chunking implementations are merged and the scenarios pass at acceptable levels, thresholds are added in a follow-up commit. This follows the same convention established in the archived eval-harness PRD.

### Calibration expectations

| Scenario | Expected Recall@3 (current impl) | Expected Recall@3 (new impl) |
|---|---|---|
| `chunking-md` | ~0.65–0.80 (MRR degradation from oversized chunks) | ~0.85–0.95 |
| `chunking-pdf` | ~0.50–0.70 (hard failures from mid-table page splits) | ~0.85–0.95 |
| `chunking-txt` | ~0.60–0.75 (mid-paragraph token splits) | ~0.85–0.95 |

These are design-time estimates. Actual baseline scores should be recorded after the scenarios are committed and run against the current implementation, before new chunking work begins.

## Testing Decisions

### What makes a good test for this feature

The scenarios *are* the tests. A good scenario:
- Uses `expected_chunk_contains` on questions whose answers would be destroyed by bad chunking (table cells, paragraph-final sentences), so that chunking failures are scored rather than masked.
- Uses `expected_sources` on questions where source-level retrieval is sufficient to detect regression (citation lookups, cross-section lookups).
- Has enough questions per structural element type (table, paragraph boundary, citation) that a single lucky retrieval does not inflate the score.
- Is reproducible: all documents are committed to the repository, no external downloads required.

### Modules and test infrastructure

No new test classes are required. `EvalScenariosTest` (tagged `@Tag("eval")`, already parameterized over all discovered scenarios) will automatically pick up and run the three new scenarios. Prior art: the existing `complex-md` and `complex-pdf` scenarios follow exactly this pattern.

The `expected_chunk_contains` evaluation path in `EvalMetricsCalculator.isHit()` is already covered by `EvalMetricsCalculatorTest`. No new unit tests are needed for the metric logic.

Manual verification after committing the new scenarios: run `./gradlew test -Dtags=eval` against the current implementation and record the baseline scores. If the scores are at or near 1.0, the scenario documents are not long/complex enough and must be revised before the new chunking work begins.

## Out of Scope

- Changes to `EvalEngine`, `EvalMetricsCalculator`, `EvalReporter`, or `EvalCorpusLoader`.
- A new `expected_chunk_contains` diagnostic metric or verbose per-question failure report in the terminal output — `expected_chunk_contains` already changes the hit condition; no additional reporter changes are needed.
- A side-by-side comparison command (`ez-rag eval compare`) for diffing two runs — useful later but not part of this work.
- Downloading or bundling real-world documents; all content is synthetic and committed.
- The actual implementation of the new Markdown, PDF, or text chunking strategies — this PRD covers only the eval corpus that measures those implementations.
- Threshold values — these are committed separately after the new chunking implementations produce baseline scores.
- Support for additional document formats (DOCX, HTML) in the eval corpus.
- Eval results persistence or trend tracking across runs.

## Further Notes

- Run `ez-rag eval src/test/resources/eval/` (or `./gradlew test -Dtags=eval`) against the current implementation immediately after committing the new scenario files to record the baseline. Document the scores in a comment on the PR or in a follow-up commit to `CLAUDE.md`. This baseline is the primary value of the exercise.
- The `chunking-pdf` and `chunking-md` scenarios should score differently when PDF→Markdown conversion is imperfect: `chunking-md` hitting 0.92 while `chunking-pdf` hits 0.80 would indicate conversion loss rather than chunking logic failure. Design the PDF documents to make this comparison meaningful.
- When calibrating the `chunking-txt` scenario, the key trigger condition is: a question whose answer phrase appears exclusively in the last 1–2 sentences of a paragraph, and the paragraph begins at a point where `TokenTextSplitter` with `chunkSize=1000, minChunkSizeChars=200` would cut before those sentences. Verify this by checking the chunk boundary manually before committing the YAML.
- Hard-negative documents in the new scenarios should share technical vocabulary with the relevant documents (same programming language names, same domain terms) but answer none of the questions. This makes them more diagnostically useful than random distractors.
- The corpus YAML format (`documents`, `questions`, `thresholds`, `expected_sources`, `expected_chunk_contains`, `expected_answer_contains`, `role`) is a public API. Do not introduce new fields without a corresponding loader change and backward-compatibility consideration.
