# PRD: RAG Evaluation Harness

## Problem Statement

When improving document ingestion, chunking, or hybrid search, there is currently no way to measure whether a change actually improves retrieval quality. Changes are validated by running the tool manually and eyeballing results, which is subjective, slow, and catches regressions only by accident.

## Solution

A retrieval evaluation harness that runs a synthetic benchmark corpus against the live search pipeline and reports Recall@k, MRR, and Hit Rate@k per scenario. The harness is usable both as a developer CI tool (`@Tag("eval")` JUnit suite) and as a power-user benchmarking subcommand (`ez-rag eval <corpus-dir>`), so users can measure retrieval quality on their own document collections.

## User Stories

1. As a developer, I want to run `./gradlew test -Dtags=eval` and see retrieval metrics for each built-in scenario, so that I can verify a chunking or search change improved (or at least did not regress) retrieval quality.
2. As a developer, I want Recall@5, MRR, and Hit Rate@5 reported per scenario, so that I can tell whether the right document is being found and how highly it ranks.
3. As a developer, I want each eval scenario isolated in its own temp store, so that results from one scenario cannot contaminate another.
4. As a developer, I want the eval JUnit suite tagged `@Tag("eval")` and excluded from the default `./gradlew test` run, so that the slow ONNX model does not slow down the normal TDD cycle.
5. As a developer, I want a `factual/` scenario with short documents and direct-lookup questions, so that I have a baseline that any reasonable retriever should pass.
6. As a developer, I want a `multi-chunk/` scenario where the answer spans a chunk boundary, so that I can measure whether my chunking overlap settings keep answers retrievable.
7. As a developer, I want a `hard-negatives/` scenario with topically similar but wrong documents alongside the relevant ones, so that I can measure whether my retriever distinguishes relevance from topic overlap.
8. As a developer, I want to add a new eval scenario by dropping a directory into the corpus, without modifying any code, so that the harness is easy to extend as new failure modes are discovered.
9. As a developer, I want each scenario to optionally define threshold values for each metric in its YAML, so that I can pin an accepted quality floor and have the test suite fail if a regression drops below it.
10. As a developer, I want thresholds to be optional — omitting them means the scenario is metrics-only and never fails the build — so that I can add new scenarios incrementally without committing to thresholds I haven't calibrated yet.
11. As a power user, I want to run `ez-rag eval <corpus-dir>` to benchmark retrieval quality against my own corpus of documents and questions, so that I can measure whether my configuration and model choices work well for my content.
12. As a power user, I want the eval command to ingest documents fresh into a temporary store for each run, so that results are not affected by any pre-existing store.
13. As a power user, I want the eval command to print a plain-text table with one row per scenario showing the scenario name, question count, Recall@k, MRR, Hit Rate@k, and a PASS/FAIL status, so that I can scan results at a glance.
14. As a power user, I want to pass `--format json` to get machine-readable output, so that I can pipe results into other tools or track trends over time.
15. As a power user, I want the command to exit with code 0 when all scenarios with thresholds pass, and non-zero otherwise, so that I can use it in shell scripts or CI.
16. As a power user, I want the corpus YAML format to be documented so that I can author my own scenarios and understand how to classify documents as `relevant`, `distractor`, or `hard-negative`.
17. As a developer, I want hard-negative documents (topically similar but wrong) reported as a separate metric subset where applicable, so that I can see whether my retriever is confused by topical similarity specifically.
18. As a developer, I want the eval JUnit suite to use the ONNX local model (all-MiniLM-L6-v2) — the same model used in `OnnxEmbeddingIntegrationTest` — so that metrics reflect real semantic similarity without requiring an API key.
19. As a power user, I want `ez-rag eval` to use whatever embedding model is configured by the user (same as `ingest`/`search`), so that benchmark results reflect my actual deployment configuration.
20. As a developer, I want the eval harness to invoke search through `SearchCommand.call()` (the full CLI pipeline), not a service layer shortcut, so that the metrics capture any bug or filtering that occurs in the CLI layer.

## Implementation Decisions

### Corpus format (YAML)

Each scenario lives in its own subdirectory. The harness discovers scenarios by finding all `questions.yaml` files under the corpus root.

```yaml
# questions.yaml — example shape (from design session, not a code snippet)
documents:
  - file: astronomy.md
    role: relevant          # roles: relevant | distractor | hard-negative
  - file: black-holes.md
    role: hard-negative     # topically similar but does not answer any question
  - file: cooking.md
    role: distractor        # unrelated noise

questions:
  - id: q1
    question: "What is the event horizon of a black hole?"
    expected_sources: ["astronomy.md"]
    # optional: expected_answer_contains — only meaningful for query evaluation
    expected_answer_contains: ["event horizon"]

thresholds:                 # optional block; omit entirely for metrics-only mode
  recall_at_k: 0.8
  mrr: 0.6
  hit_rate_at_k: 0.9
```

`k` defaults to 5, matching the `search` command default. Each scenario can override it if needed.

### Modules

**EvalCorpus (data model)** — data classes representing a loaded scenario: `EvalScenario`, `EvalDocument` (file path + role), `EvalQuestion` (id, question text, expected sources, optional answer contains), `EvalThresholds` (optional). No I/O; purely a data model.

**EvalCorpusLoader** — reads a scenario directory from disk: locates `questions.yaml`, resolves document paths relative to the scenario directory, deserializes into `EvalScenario`. Uses Jackson (already on the classpath via Spring). Stateless; takes a `Path` and returns an `EvalScenario`.

**EvalEngine** — orchestrates evaluation of a single `EvalScenario` against a given store directory. Steps: (1) ingest all scenario documents using `IngestCommand.call()`; (2) for each question, call `SearchCommand.call()` with `--format json` and capture output; (3) parse results into a list of `EvalQuestionResult` (question id, retrieved sources in rank order, whether each expected source was found and at what rank). Stateless given a store directory; `@TempDir` in JUnit provides isolation.

**EvalMetricsCalculator** — pure function: takes a list of `EvalQuestionResult` and a value of `k`, returns `EvalMetrics` (recall@k, mrr, hit_rate@k). Also computes the same metrics for the hard-negative subset separately. No I/O, no state; trivially testable.

**EvalReporter** — formats `EvalScenarioReport` (scenario name, metrics, threshold result) as a plain-text table or JSON. Determines per-scenario PASS/FAIL by comparing metrics to thresholds. Takes a `PrintWriter`; no side effects beyond writing.

**EvalCommand** — the `ez-rag eval <corpus-dir>` picocli subcommand. Discovers scenario directories, runs `EvalEngine` per scenario (each in a `@TempDir`-equivalent temp directory), calls `EvalReporter`, exits non-zero if any scenario with thresholds fails. Registered in `EzRagCommand` subcommands list.

### Built-in corpus (checked into `src/test/resources/eval/`)

Three scenarios:
- `factual/` — 3–5 short `.txt` documents with unambiguous factual content, 5–8 direct-lookup questions, 1–2 distractors. No hard negatives. Thresholds: recall@5 ≥ 1.0, hit_rate@5 ≥ 1.0.
- `multi-chunk/` — 2–3 longer documents where answers span a chunk boundary at the default chunk size. 4–6 questions. Tests overlap settings. Thresholds: deliberately lenient until a baseline is established.
- `hard-negatives/` — 3–4 documents where 1–2 are topically similar to the relevant documents but do not answer any question. 4–6 questions. Thresholds: set after baseline run.

### JUnit eval suite

- One test class per scenario (e.g. `EvalFactualScenarioTest`) plus one `EvalOverallTest` that aggregates all scenarios and asserts the overall hit rate is above a floor.
- All tagged `@Tag("eval")`.
- `@TempDir` per test for store isolation.
- ONNX model initialized once per test class via `@BeforeAll` with the same cache directory pattern as `OnnxEmbeddingIntegrationTest`.
- Excluded from the default Gradle test run (already handled by the existing `excludeTags("integration")` — a new `excludeTags("integration", "eval")` replaces it).

### Metrics definitions

- **Recall@k**: fraction of questions for which at least one `expected_source` appears in the top-k results.
- **Hit Rate@k**: same as Recall@k (binary per question, then averaged) — the two names are synonyms here.
- **MRR** (Mean Reciprocal Rank): for each question, the reciprocal of the rank of the first `expected_source` in the results (0 if not found in top-k); averaged over all questions.

### Output format

Plain-text table (default):
```
Scenario          Questions  Recall@5  MRR    Hit@5   Status
─────────────────────────────────────────────────────────────
factual           8          1.00      0.94   1.00    PASS
multi-chunk       6          0.83      0.71   0.83    PASS
hard-negatives    5          0.80      0.65   0.80    FAIL  (recall_at_k < 0.85)
─────────────────────────────────────────────────────────────
Overall           19         0.89      0.77   0.89
```

JSON (`--format json`): array of scenario objects with the same fields.

Exit code: 0 if all scenarios with thresholds pass (or no thresholds defined), non-zero otherwise.

## Testing Decisions

Good tests for this feature test *observable outcomes*, not implementation details:
- Does the corpus loader produce the right number of documents and questions given a known YAML file?
- Does the metrics calculator produce the correct Recall@k, MRR, and Hit Rate@k for a hand-crafted list of results?
- Does the reporter produce output that contains the right scenario name, metric values, and PASS/FAIL status?
- Does the eval command exit non-zero when a threshold is violated?

**Do not** test that `EvalEngine` calls specific methods on `SearchCommand` — test that the search results used to compute metrics are correct.

Modules to test:

| Module | Test type | Prior art |
|--------|-----------|-----------|
| `EvalCorpusLoader` | Unit, `@TempDir` | `ConfigFileReaderTest`, `ConfigServiceTest` |
| `EvalMetricsCalculator` | Pure unit (no I/O) | `DocumentChunkerTest` |
| `EvalReporter` | Unit, `StringWriter` capture | `IngestCommandTest`, `SearchCommandTest` |
| `EvalCommand` (threshold/exit-code) | Unit, `@TempDir`, fake engine | `IngestCommandTest`, `SearchCommandTest` |
| `EvalEngine` + full pipeline | `@Tag("eval")`, ONNX model, `@TempDir` | `OnnxEmbeddingIntegrationTest`, `IngestIntegrationTest` |

The `@Tag("eval")` suite exercises the full stack end-to-end (corpus YAML → ingest → search → metrics → thresholds) using the built-in corpus in `src/test/resources/eval/`.

## Out of Scope

- `query` evaluation (LLM answer quality, faithfulness, relevance scoring) — search evaluation is the priority.
- Downloading or bundling external real-world document corpora.
- Streaming or incremental eval results.
- Comparing two runs automatically (baseline vs. candidate diff).
- Eval results persistence or trend tracking.
- Any UI beyond the terminal table and JSON flag.
- Support for corpus formats other than YAML.

## Further Notes

- The corpus YAML format is a **public API** once `ez-rag eval` ships. Design it conservatively — add fields later, never remove them.
- The first run of the `@Tag("eval")` suite will establish the baseline. Only after that should thresholds be added to the built-in scenario YAMLs and committed.
- `hard-negative` documents are the most diagnostically valuable part of the corpus. When adding future scenarios, prefer hard negatives over random distractors.
- The existing `excludeTags("integration")` in `build.gradle.kts` needs to be extended to also exclude `"eval"` so the new tag is consistently excluded from the default run.
