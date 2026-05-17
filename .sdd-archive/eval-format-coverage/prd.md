# PRD: Eval Corpus Coverage for File Format Readers

## Problem Statement

The eval harness measures retrieval quality using a corpus that contains only `.txt` files.
When a developer improves the Markdown reader (e.g. heading-based splitting, YAML front-matter
handling) or the PDF reader, there is no eval scenario that exercises those code paths. A
regression in `MarkdownDocumentReader` or `PdfDocumentReader` would be invisible to the eval
suite — it would pass as if nothing changed.

Additionally, the JUnit eval suite currently has one hand-written test class per scenario
(`EvalFactualScenarioTest`, `EvalMultiChunkScenarioTest`, `EvalHardNegativesScenarioTest`,
`EvalOverallTest`). Adding a new corpus scenario requires writing a new test class. This
contradicts User Story 8 of the original eval PRD: *"add a new eval scenario by dropping a
directory into the corpus, without modifying any code."*

## Solution

Add a `factual-md/` eval scenario whose documents are `.md` files structured with H1/H2
headings and YAML front matter. The scenario covers the same factual topics as `factual/`
(planets, capitals, elements), making format the only variable. This immediately measures
the current Markdown reader and will measure the upcoming `MarkdownDocumentReader`
heading-based splitter without any rework.

Replace the four existing per-scenario test classes with a single discovery-based
`EvalScenariosTest` that finds all scenario directories at runtime by scanning for
`questions.yaml` files. Adding `factual-pdf/` or any future scenario then requires zero
code changes — just a new directory.

Thresholds are read from `questions.yaml` exclusively (single source of truth). The test
code carries no hardcoded threshold values.

## User Stories

1. As a developer improving the Markdown reader, I want the eval suite to measure retrieval
   quality on `.md` documents, so that I can verify my change improved (or at least did not
   regress) Markdown ingestion.
2. As a developer, I want Markdown eval documents structured with H1/H2 headings, so that
   the scenario immediately exercises heading-based chunking when `MarkdownDocumentReader`
   ships, without any rework to the corpus.
3. As a developer, I want at least one eval Markdown document to include YAML front matter,
   so that the front-matter stripping logic is covered by the eval suite.
4. As a developer, I want the Markdown distractor document to be flat prose with no headings,
   so that the token-based fallback path in `MarkdownDocumentReader` is also exercised.
5. As a developer, I want the `factual-md/` scenario to share the same factual topics as
   `factual/` (planets, capitals, elements), so that format is the only variable and a
   divergence between the two scenarios points directly at the Markdown reader.
6. As a developer, I want `factual-md/` to have thresholds set from the first commit
   (recall@3 ≥ 0.85, hit_rate@3 ≥ 0.85), so that a Markdown reader regression fails CI
   automatically.
7. As a developer, I want to add a new eval scenario (`factual-pdf/` or any other format)
   by dropping a directory under `src/test/resources/eval/`, without modifying any test code.
8. As a developer, I want the eval JUnit suite to discover all scenario directories at runtime,
   run each as a separate parameterized test case, and assert thresholds read from each
   scenario's `questions.yaml`.
9. As a developer, I want a guard assertion that fails loudly if no scenario directories are
   discovered, so that a misconfigured corpus path does not produce a silent green build.
10. As a developer, I want the overall weighted Hit Rate@3 across all discovered scenarios to
    be asserted in a single aggregate test, so that future scenarios with no thresholds still
    contribute to a quality floor.
11. As a developer, I want threshold values to live exclusively in `questions.yaml`, so that
    calibrating a threshold is a single-file change with no test-code edits.
12. As a developer, I want the four existing per-scenario test classes to be deleted, so that
    the eval suite has no duplicated coverage and no stale hardcoded threshold values.

## Implementation Decisions

### New corpus scenario: `factual-md/`

A new subdirectory under `src/test/resources/eval/` containing:

- `planets.md` — section-per-planet with H2 headings, YAML front matter
- `capitals.md` — section-per-country with H2 headings, no front matter
- `elements.md` — section-per-element with H2 headings, YAML front matter
- `cooking_distractor.md` — flat prose, no headings (exercises the heading-splitter fallback path once that feature ships)
- `questions.yaml` — same 16 questions as `factual/questions.yaml`, `expected_sources` referencing `.md` filenames, and a `thresholds:` block with `recall_at_k: 0.85`, `hit_rate_at_k: 0.85`

The document content is topically equivalent to the `factual/` `.txt` files. Format is the
only variable between the two scenarios.

### YAML front matter convention

`planets.md` and `elements.md` include a YAML front-matter block delimited by `---`. This
exercises the `stripYamlFrontMatter` path in the current `DocumentLoader` and will continue
to exercise the equivalent path in `MarkdownDocumentReader`. `capitals.md` and
`cooking_distractor.md` have no front matter, confirming that files without it also load
correctly.

### Extensibility convention

Each format gets its own scenario directory named `factual-<extension>/`. The discovery
mechanism — scanning for all `questions.yaml` files under the corpus root — means adding
`factual-pdf/` later requires zero code changes.

### Discovery-based `EvalScenariosTest`

The four existing test classes (`EvalFactualScenarioTest`, `EvalMultiChunkScenarioTest`,
`EvalHardNegativesScenarioTest`, `EvalOverallTest`) are deleted and replaced by a single
`EvalScenariosTest`:

- `@Tag("eval")`, `@ParameterizedTest` driven by a `@MethodSource` that scans the classpath
  resource root `eval/` for subdirectories containing a `questions.yaml`.
- Guard: the method source asserts that at least one scenario was found before returning.
- Per-scenario `@ParameterizedTest` method: loads scenario via `EvalCorpusLoader`, evaluates
  via `EvalEngine` with a `@TempDir`, computes metrics via `EvalMetricsCalculator`, and
  asserts against the thresholds in the loaded `EvalScenario`. Scenarios without a
  `thresholds:` block are metrics-only (no assertion failure).
- Separate aggregate `@Test` method: discovers all scenarios, runs each in its own `@TempDir`,
  computes weighted Hit Rate@3 across all scenarios, asserts the result exceeds 0.7. This
  preserves the safety net from `EvalOverallTest` but uses discovery rather than a hardcoded
  scenario list.
- `TransformersEmbeddingModel` is initialised once per class in `@BeforeAll`, identical to
  the pattern in the deleted classes.

### No changes to `EvalModel`, `EvalCorpusLoader`, `EvalEngine`, `EvalMetricsCalculator`, or `EvalReporter`

The existing modules already support all required functionality: scenario loading, threshold
parsing, engine execution, and metrics calculation. This change is purely additive (new corpus
files) and structural (test class consolidation).

## Testing Decisions

Good tests assert on externally observable behaviour — the structure and content of the
loaded scenario and the metric values produced — not on which internal methods were called.

| What to test | Test type | Notes |
|---|---|---|
| `EvalScenariosTest` per-scenario parameterized | `@Tag("eval")`, ONNX model, `@TempDir` | Replaces `EvalFactualScenarioTest` et al.; thresholds from YAML |
| `EvalScenariosTest` aggregate | `@Tag("eval")`, ONNX model, `@TempDir` per scenario | Replaces `EvalOverallTest`; discovery-based scenario list |
| `EvalScenariosTest` guard | Implicit in `@MethodSource` | Fails loudly if corpus root returns zero scenarios |

No new unit tests are needed: `EvalCorpusLoader`, `EvalMetricsCalculator`, and `EvalReporter`
are not modified and their existing tests remain valid.

Prior art: `EvalFactualScenarioTest` (parameterized structure to replicate), `EvalOverallTest`
(aggregate assertion logic to port), `OnnxEmbeddingIntegrationTest` (ONNX model setup pattern).

## Out of Scope

- PDF eval scenario (`factual-pdf/`): the extensibility convention is designed for it, but
  authoring it is deferred until after `factual-md/` establishes the pattern.
- `multi-chunk` or `hard-negatives` scenarios in Markdown format: those can be added later
  once a Markdown-specific failure mode warrants a dedicated scenario.
- Eval coverage for `MarkdownDocumentReader` heading metadata (heading_title, heading_level,
  heading_path): those fields are tested at the unit level in the `markdown-heading-splitter`
  feature; the eval harness measures end-to-end retrieval quality, not metadata correctness.
- Any change to `EvalModel`, `EvalCorpusLoader`, `EvalEngine`, `EvalMetricsCalculator`, or
  `EvalReporter`.

## Further Notes

- The `factual-md/` scenario will initially run through the current `DocumentLoader.loadMarkdown`
  path (full file as one document, then token-based chunking). Once `MarkdownDocumentReader`
  ships, the same scenario will exercise heading-based splitting automatically — no corpus
  changes needed. The heading structure in the documents is forward-compatible.
- The distractor `cooking_distractor.md` being flat prose is intentional: it exercises the
  heading-splitter's no-heading fallback path once that feature lands.
- The `EvalScenariosTest` aggregate threshold (weighted Hit Rate@3 > 0.7) was inherited from
  `EvalOverallTest`. With `factual-md/` adding a 16-question scenario at 0.85 thresholds,
  this floor will become easier to meet, not harder — which is acceptable since the per-scenario
  thresholds are the primary quality gate.
- The `questions.yaml` `thresholds:` block is the canonical location for quality thresholds.
  After each significant retrieval improvement, developers should re-run the eval suite,
  observe the new baselines, and tighten the thresholds in the YAML files.
