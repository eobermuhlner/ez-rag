# Tasks: RAG Evaluation Harness

## Task 01-eval-minimal-pipeline

A thin vertical spike: `ez-rag eval <corpus-dir>` discovers scenario directories (each containing a `questions.yaml`), ingests all scenario documents into a temporary store, runs `SearchCommand.call()` for each question, computes Recall@5, MRR, and Hit Rate@5, and prints one result line per scenario to stdout. Exit code is always 0 at this stage. No thresholds, no formatted table, no JSON — just the live end-to-end pipeline working.

New types introduced here and used by all subsequent tasks: `EvalDocument` (file path + role enum: `relevant`/`distractor`/`hard-negative`), `EvalQuestion` (id, question text, expected sources, optional expected_answer_contains), `EvalScenario` (name, documents, questions, null thresholds), `EvalQuestionResult` (question id, expected sources, retrieved source filenames in rank order), `EvalMetrics` (recallAtK, mrr, hitRateAtK, hardNegativeMetrics: EvalMetrics?).

New classes: `EvalCorpusLoader`, `EvalMetricsCalculator`, `EvalEngine`, `EvalCommand` (minimal output).

### Implementation steps

- [x] Write failing test for `EvalCorpusLoader`: given a `@TempDir` with a `questions.yaml` containing 3 documents and 5 questions, the loader returns an `EvalScenario` with the correct counts, roles, ids, and document paths resolved relative to the scenario directory
- [x] Define data classes `EvalDocument`, `EvalQuestion`, `EvalScenario` (thresholds field is null here), `EvalQuestionResult`, `EvalMetrics`; implement `EvalCorpusLoader` using Jackson
- [x] Write failing tests for `EvalMetricsCalculator`: (a) all questions hit at rank 1 → recall=1.0, mrr=1.0; (b) no questions hit → recall=0.0, mrr=0.0; (c) expected source at rank 2 for every question → mrr=0.5; (d) hitRateAtK equals recallAtK for the same input
- [x] Implement `EvalMetricsCalculator.calculate(results: List<EvalQuestionResult>, k: Int): EvalMetrics`
- [x] Write failing unit test for `EvalEngine` using a fake `EmbeddingModel`: given a scenario with 2 documents and 3 questions, `evaluate` returns 3 `EvalQuestionResult` objects, each with `retrievedSources` being the filenames (in rank order) returned by `SearchCommand` JSON output
- [x] Implement `EvalEngine.evaluate(scenario: EvalScenario, storeDir: Path, embeddingModel: EmbeddingModel): List<EvalQuestionResult>` — calls `IngestCommand` for all scenario document files, then `SearchCommand` with `--output json` per question and parses the filenames from JSON
- [x] Write failing unit test for `EvalCommand`: given a corpus dir with one scenario, the command prints a line containing the scenario name and the three metric values, and exits 0
- [x] Implement `EvalCommand` walking `<corpus-dir>` recursively for `questions.yaml` files, running `EvalEngine` per scenario in a `Files.createTempDirectory` store, and printing `{scenarioName}: recall@5={v} mrr={v} hit@5={v}` per scenario
- [x] Register `EvalCommand` in `EzRagCommand.subcommands` and add it to `SubcommandTest`

### Acceptance criteria

- [x] `EvalCorpusLoader` loading a 3-document, 5-question YAML returns an `EvalScenario` with exactly 3 documents and 5 questions; each `EvalDocument.path` is resolved relative to the scenario directory (not an absolute path from the filesystem root)
- [x] `EvalMetricsCalculator.calculate` returns `recallAtK = 1.0` and `mrr = 1.0` when every question's expected source is at rank 1 in the retrieved results
- [x] `EvalMetricsCalculator.calculate` returns `recallAtK = 0.0` and `mrr = 0.0` when no expected source appears in any result list
- [x] `EvalMetricsCalculator.calculate` returns `mrr = 0.5` when the expected source is at rank 2 for every question
- [x] `hitRateAtK` equals `recallAtK` for the same input (they are defined identically in the PRD)
- [x] `EvalEngine.evaluate` calls `SearchCommand.call()` (the CLI entry point), not any internal service method; each `EvalQuestionResult.retrievedSources` contains filenames in the order they appeared in the JSON output
- [x] `ez-rag eval <corpus-dir>` prints one line per discovered scenario and exits with code 0
- [x] `eval` appears in `ez-rag --help` as a registered subcommand (verified by the existing `SubcommandTest` pattern)

### Quality gates

- [x] No Kotlin compiler warnings (`-Werror` enforced in `build.gradle.kts`)
- [x] `EvalEngine` has no direct `System.out` or `System.err` calls; output capture uses `StringWriter`/`PrintWriter` injected into `SearchCommand`
- [x] All assertions use AssertJ (`assertThat`) consistent with existing test style

---

## Task 02-eval-thresholds-and-table

Extend the pipeline with threshold support and proper text table output. The `thresholds` block in `questions.yaml` is now parsed into `EvalThresholds` (recallAtK, mrr, hitRateAtK as nullable Doubles). `EvalCommand` shows a formatted text table (header + separator + one row per scenario + overall row) with a PASS/FAIL status column. Exit code becomes non-zero when any threshold-bearing scenario fails. Also documents the corpus YAML format (user story 16).

Depends on Task 01.

### Implementation steps

- [x] Write failing test for `EvalCorpusLoader`: a YAML with a `thresholds` block produces non-null `EvalThresholds` with the correct values; a YAML without a `thresholds` block produces `EvalScenario.thresholds == null`
- [x] Add `EvalThresholds` data class and wire it into `EvalScenario`; update `EvalCorpusLoader`
- [x] Write failing tests for `EvalReporter`: (a) text output contains a header row with columns Scenario, Questions, Recall@5, MRR, Hit@5, Status; (b) a scenario meeting all thresholds shows PASS; (c) a scenario failing one threshold shows FAIL and names the failing metric; (d) a scenario with null thresholds has an empty Status cell; (e) the overall row aggregates question counts and averages metrics
- [x] Implement `EvalReporter.reportText(reports: List<EvalScenarioReport>, writer: PrintWriter)` producing the fixed-width table
- [x] Write failing test for `EvalCommand` exit code: exits non-zero when any threshold-bearing scenario fails; exits 0 when no scenario defines thresholds; exits 0 when all thresholds pass
- [x] Update `EvalCommand` to use `EvalReporter` and return the correct exit code
- [x] Add `docs/eval-corpus-format.md` documenting the YAML schema (fields, role enum values, optional thresholds, `k` default)

### Acceptance criteria

- [x] `EvalCorpusLoader` produces `EvalScenario.thresholds = EvalThresholds(recallAtK=0.8, mrr=0.6, hitRateAtK=0.9)` from a YAML containing those values
- [x] `EvalCorpusLoader` produces `EvalScenario.thresholds = null` from a YAML with no `thresholds` block
- [x] Text table contains a header row with at minimum the columns: Scenario, Questions, Recall@5, MRR, Hit@5, Status
- [x] A scenario where `recallAtK ≥ threshold` and `mrr ≥ threshold` and `hitRateAtK ≥ threshold` shows `PASS` in the Status column
- [x] A scenario where `recallAtK < threshold` shows `FAIL  (recall_at_k < {threshold})` in the Status column
- [x] A scenario with null thresholds has an empty or absent Status column entry — it never shows PASS or FAIL
- [x] The overall summary row shows the sum of question counts and the unweighted average of each metric across all scenarios
- [x] `EvalCommand` exits non-zero when at least one threshold-bearing scenario fails; exits 0 otherwise
- [x] `docs/eval-corpus-format.md` documents all YAML fields: `documents`, `questions`, `thresholds`, the `role` enum, and `k`

### Quality gates

- [x] No Kotlin compiler warnings
- [x] `EvalReporter` writes only to the provided `PrintWriter`; no `System.out` calls

---

## Task 03-eval-json-and-hard-negatives

Add two independent extensions to the pipeline: `--format json` output and hard-negative subset metrics. For JSON, `EvalReporter.reportJson` produces a JSON array of scenario objects. For hard negatives, `EvalMetricsCalculator` computes a separate `EvalMetrics` for the subset of questions whose `expectedSources` overlap with documents tagged `hard-negative` in the scenario; this subset is stored in `EvalMetrics.hardNegativeMetrics: EvalMetrics?` and shown as an additional row in both text and JSON output when present.

Depends on Tasks 01 and 02.

### Implementation steps

- [x] Write failing test for `EvalReporter.reportJson`: output is valid JSON; each element has fields `scenario`, `questions`, `recallAtK`, `mrr`, `hitRateAtK`, and optional `status`
- [x] Implement `EvalReporter.reportJson(reports, writer)` using Jackson; wire `--format json` flag in `EvalCommand`
- [x] Write failing test for `EvalMetricsCalculator` hard-negative subset: given results where 2 of 5 questions target a hard-negative document, `EvalMetrics.hardNegativeMetrics` is computed over only those 2 questions; when no question targets a hard-negative document, `hardNegativeMetrics` is null
- [x] Implement hard-negative subset logic in `EvalMetricsCalculator.calculate`; require the caller to pass the set of hard-negative source filenames
- [x] Update `EvalEngine` to pass the hard-negative document filenames to `EvalMetricsCalculator`
- [x] Update `EvalReporter` text output to show an indented hard-negative row when `hardNegativeMetrics` is non-null; update JSON output to include a `hardNegatives` nested object when non-null

### Acceptance criteria

- [x] `--format json` produces a JSON array parseable by `ObjectMapper`; each element contains at minimum `scenario`, `questions`, `recallAtK`, `mrr`, `hitRateAtK`; scenarios with thresholds include a `status` field
- [x] `EvalMetrics.hardNegativeMetrics` is non-null and contains independently computed metrics when at least one question's expected source is a hard-negative document in the scenario
- [x] `EvalMetrics.hardNegativeMetrics` is null when no question targets a hard-negative document
- [x] Hard-negative metrics are shown as a sub-row in the text table (e.g. indented `  hard-negatives` row) when non-null
- [x] Hard-negative metrics appear as a nested `hardNegatives` object in JSON output when non-null

### Quality gates

- [x] No Kotlin compiler warnings
- [x] JSON output is produced by Jackson, not by manual string concatenation

---

## Task 04-eval-error-handling

Harden `EvalCommand` and `EvalEngine` against bad inputs and runtime failures. Observable behavior: each error case prints a clear message to stderr and exits non-zero.

Depends on Task 01.

### Implementation steps

- [x] Write failing test: `EvalCommand` with a non-existent `<corpus-dir>` prints an error message to stderr and exits non-zero
- [x] Write failing test: `EvalCommand` with a `<corpus-dir>` that contains no `questions.yaml` files prints "No eval scenarios found" to stderr and exits non-zero
- [x] Write failing test: `EvalCorpusLoader` given a `questions.yaml` with a document `file` path that does not exist throws a descriptive exception (or `EvalEngine` surfaces the error cleanly)
- [x] Write failing test: when `SearchCommand` exits non-zero during `EvalEngine.evaluate`, the engine throws an exception with the scenario name and question id included in the message
- [x] Implement all four error paths in `EvalCommand` and `EvalEngine`

### Acceptance criteria

- [x] `ez-rag eval /nonexistent` exits non-zero and prints a message containing "does not exist" (or equivalent) to stderr
- [x] `ez-rag eval <empty-dir>` exits non-zero and prints "No eval scenarios found" to stderr
- [x] A `questions.yaml` referencing a missing document file causes `EvalEngine` to throw with a message identifying the missing file path
- [x] A `SearchCommand` non-zero exit during evaluation causes `EvalEngine` to throw with a message identifying the scenario name and question id where the failure occurred

### Quality gates

- [x] No Kotlin compiler warnings
- [x] All error messages go to stderr (errorWriter), not stdout

---

## Task 05-eval-builtin-corpus-and-suite

Author the three built-in evaluation scenarios as checked-in corpus files and create the `@Tag("eval")` JUnit suite that exercises the full stack with the ONNX local model. Update Gradle to exclude `"eval"` from the default test run.

Depends on Tasks 01, 02, 03, 04 (all eval infrastructure).

### Implementation steps

- [x] Update `build.gradle.kts` to exclude both `"integration"` and `"eval"` from the default test run: replace `excludeTags("integration")` with `excludeTags("integration", "eval")`
- [x] Write failing `EvalFactualScenarioTest` (`@Tag("eval")`, `@TempDir`, ONNX model via `@BeforeAll`): runs `EvalEngine` against `src/test/resources/eval/factual/`, asserts Recall@5 ≥ 1.0 and Hit Rate@5 ≥ 1.0
- [x] Author `src/test/resources/eval/factual/questions.yaml` with 3–5 short `.txt` documents (unambiguous factual content), 5–8 direct-lookup questions, 1–2 distractors, thresholds: `recall_at_k: 1.0`, `hit_rate_at_k: 1.0`; commit the `.txt` document files alongside
- [x] Write failing `EvalMultiChunkScenarioTest` (`@Tag("eval")`): runs the `multi-chunk/` scenario and asserts the test completes without error and reports numeric metrics (thresholds omitted from YAML until baseline is established)
- [x] Author `src/test/resources/eval/multi-chunk/questions.yaml` with 2–3 longer documents where answers cross chunk boundaries at the default chunk size, 4–6 questions
- [x] Write failing `EvalHardNegativesScenarioTest` (`@Tag("eval")`): runs the `hard-negatives/` scenario and asserts hard-negative subset metrics are non-null and reported
- [x] Author `src/test/resources/eval/hard-negatives/questions.yaml` with 3–4 documents (1–2 tagged `hard-negative`), 4–6 questions; hard-negative docs must be topically similar to the relevant docs but answer no question
- [x] Write `EvalOverallTest` (`@Tag("eval")`): aggregates all three scenarios and asserts the overall Hit Rate@5 across all questions exceeds 0.7
- [x] Follow `EmbeddingSearchIntegrationTest` ONNX model initialization pattern for all test classes (companion object `@BeforeAll`)

### Acceptance criteria

- [x] `./gradlew test` (default, no `-Dtags`) does not execute any `@Tag("eval")` test
- [x] `./gradlew test -Dtags=eval` runs `EvalFactualScenarioTest`, `EvalMultiChunkScenarioTest`, `EvalHardNegativesScenarioTest`, and `EvalOverallTest` without build errors
- [x] `factual/` scenario achieves Recall@5 ≥ 1.0 and Hit Rate@5 ≥ 1.0 (thresholds committed in the YAML, test asserts them)
- [x] `multi-chunk/` scenario reports numeric Recall@5, MRR, and Hit Rate@5 values (any value; no threshold assertion yet)
- [x] `hard-negatives/` scenario reports non-null `hardNegativeMetrics` in the result
- [x] `EvalOverallTest` asserts the weighted-average Hit Rate@5 across all three scenarios is greater than 0.7
- [x] All three `questions.yaml` files parse without error using `EvalCorpusLoader`

### Quality gates

- [x] No Kotlin compiler warnings
- [x] ONNX model initialized once per test class (`@BeforeAll`), not once per test method
- [x] All eval test classes carry `@Tag("eval")`
