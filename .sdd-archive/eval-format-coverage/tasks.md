# Tasks: Eval Corpus Coverage for File Format Readers

## Task [01-discovery-eval-test]

Replace the four per-scenario JUnit test classes with a single discovery-based `EvalScenariosTest`. The new class scans the classpath resource root `eval/` for all subdirectories containing `questions.yaml`, runs each as a separate `@ParameterizedTest` case, reads thresholds exclusively from each scenario's YAML, and guards against a zero-scenario discovery. An aggregate `@Test` asserts the weighted Hit Rate@3 across all discovered scenarios exceeds 0.7. The four old test classes are deleted.

### Implementation steps

- [x] Create `EvalScenariosTest` with a `discoverScenarios()` `@MethodSource` stub that returns an empty stream — the guard assertion (`require(scenarios.isNotEmpty())`) fires immediately, making the test suite fail with a descriptive error. This is the TDD red step.
- [x] Implement the discovery logic: scan the classpath `eval/` directory for subdirectories containing `questions.yaml` and return them as a `Stream<Arguments>`. The guard assert must remain, failing loudly on an empty result.
- [x] Write the `@ParameterizedTest` method body: load scenario via `EvalCorpusLoader`, evaluate via `EvalEngine` with `@TempDir`, compute metrics via `EvalMetricsCalculator`, assert each non-null threshold from `scenario.thresholds` (scenarios with no `thresholds:` block skip assertions silently).
- [x] For the `hard-negatives` scenario, add an assertion in the parameterized body that `metrics.hardNegativeMetrics` is non-null when the scenario contains at least one `HARD_NEGATIVE` document.
- [x] Run the `@ParameterizedTest` — verify it discovers exactly `factual`, `multi-chunk`, `hard-negatives` and each case passes using thresholds from YAML.
- [x] Add the aggregate `@Test`: discover all scenarios, run each in its own `Files.createTempDirectory`, compute weighted Hit Rate@3, assert > 0.7.
- [x] Delete `EvalFactualScenarioTest`, `EvalMultiChunkScenarioTest`, `EvalHardNegativesScenarioTest`, `EvalOverallTest`.

### Acceptance criteria

- [x] Running `./gradlew test -Peval` discovers exactly three scenarios (factual, multi-chunk, hard-negatives) and produces three parameterized test cases.
- [x] No threshold numeric literals appear in `EvalScenariosTest` source; all threshold values come from `scenario.thresholds`.
- [x] A scenario whose `questions.yaml` has no `thresholds:` block passes without an assertion failure (conditional chain `thresholds?.recallAtK?.let { ... }` produces no assertion).
- [x] The `@MethodSource` guard throws `IllegalStateException` (or equivalent) with a message referencing the corpus path when zero scenario directories are found.
- [x] The aggregate test asserts weighted Hit Rate@3 > 0.7 across all discovered scenarios and fails if that floor is not met.
- [x] For the `hard-negatives` scenario, `metrics.hardNegativeMetrics` is non-null after evaluation (the scenario contains `HARD_NEGATIVE` documents that trigger the secondary metric computation).
- [x] `EvalFactualScenarioTest`, `EvalMultiChunkScenarioTest`, `EvalHardNegativesScenarioTest`, `EvalOverallTest` no longer exist in the codebase.
- [x] All three scenarios pass with the same metric outcomes as before the refactor.

### Quality gates

- [x] `./gradlew build -x test` produces zero compiler warnings and zero linter errors.
- [x] `./gradlew test` (non-eval unit tests) passes with no failures.

---

## Task [02-factual-md-corpus]

Create the `factual-md/` eval scenario under `src/test/resources/eval/`. The scenario contains four Markdown files (planets.md, capitals.md, elements.md, cooking_distractor.md) and a `questions.yaml` with the same 16 questions as `factual/`, `expected_sources` referencing `.md` filenames, and thresholds recall@3 ≥ 0.85, hit_rate@3 ≥ 0.85. The `EvalScenariosTest` from Task 01 discovers and runs it automatically with zero code changes. The `.md` extension is already handled by `DocumentLoader.loadMarkdown` (strips YAML front-matter, applies token-based chunking).

### Implementation steps

- [x] Create `factual-md/questions.yaml` referencing the four `.md` files with the same 16 questions as `factual/questions.yaml` (but `expected_sources` pointing to `.md` filenames) and a `thresholds:` block (`recall_at_k: 0.85`, `hit_rate_at_k: 0.85`). Because the four `.md` files do not exist yet, `EvalCorpusLoader.load()` throws `IllegalArgumentException` — the parameterized test case errors. This is the TDD red step.
- [x] Create `planets.md` with one H2 section per planet and a YAML front-matter block (`---`).
- [x] Create `capitals.md` with one H2 section per country, no front matter.
- [x] Create `elements.md` with one H2 section per element and a YAML front-matter block (`---`).
- [x] Create `cooking_distractor.md` as flat prose paragraphs with no Markdown headings (no lines starting with `#`).
- [x] Run `./gradlew test -Peval` and verify the `factual-md` parameterized case passes with recall@3 ≥ 0.85 and hit_rate@3 ≥ 0.85.

### Acceptance criteria

- [x] Running `./gradlew test -Peval` discovers four scenarios (factual, factual-md, multi-chunk, hard-negatives) and produces four parameterized test cases.
- [x] The `factual-md` parameterized test case passes with recall@3 ≥ 0.85 and hit_rate@3 ≥ 0.85.
- [x] `planets.md` and `elements.md` each begin with a YAML front-matter block delimited by `---` on its own line.
- [x] `capitals.md` contains no YAML front-matter block (no `---` delimiter at the top).
- [x] `cooking_distractor.md` contains no Markdown headings (no lines matching `^#+\s`).
- [x] All 16 questions in `factual-md/questions.yaml` have `expected_sources` entries that reference `.md` filenames.
- [x] No `.kt` source file is created or modified to make `factual-md` work — the scenario is discovered and loaded by existing code paths.

### Quality gates

- [x] `./gradlew build -x test` produces zero compiler warnings and zero linter errors.
- [x] `./gradlew test` (non-eval unit tests) passes with no failures.
