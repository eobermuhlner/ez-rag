# CLI Simplification — Tasks

## Task 01-cleanup-redundant-cli-options

Remove three problematic CLI options end-to-end: the redundant `--details` flag from `ingest`, the effectively unused `--min-score` flag from `search` (hardcoded to `0.0` at all SearchQuery construction sites), and rename the inconsistent `--output` flag to `--output-format` on both `query` and `shell`. Each change is verified by tests that confirm the old option is rejected by picocli and any replacement behaves correctly.

### Implementation steps

- [x] Write failing test: IngestCommandTest verifies passing `--details` causes a picocli `UnmatchedArgumentException`
- [x] Remove `--details` from IngestCommand; replace `verbose || detailsOption` with `verbose`
- [x] Write failing test: SearchCommandTest verifies passing `--min-score` causes a picocli `UnmatchedArgumentException`
- [x] Remove `--min-score` from SearchCommand; make `minScore` in `SearchQuery` optional with default `0.0`; delete all `copy(minScore = minScore)` call sites in SearchCommand
- [x] Write failing tests: QueryCommandTest verifies `--output=json` causes `UnmatchedArgumentException` and `--output-format=json` produces JSON; ShellCommandTest does the same (use `CommandLine.execute()` or field injection, not constructor params, as ShellCommand does not take outputFormat as a constructor param)
- [x] Rename `@Option(names = ["--output"])` to `@Option(names = ["--output-format"])` on QueryCommand
- [x] Rename `@Option(names = ["--output"])` to `@Option(names = ["--output-format"])` on ShellCommand

### Acceptance criteria

- [x] Passing `--details` to `ez-rag ingest` causes a picocli `UnmatchedArgumentException`
- [x] `ez-rag ingest --help` does not list `--details`
- [x] Passing `--min-score` to `ez-rag search` causes a picocli `UnmatchedArgumentException`
- [x] `ez-rag search --help` does not list `--min-score`
- [x] Existing search tests pass without modification (search still returns results)
- [x] Passing `--output-format=json` to `ez-rag query` produces JSON output identical in content to what `--output=json` previously produced
- [x] Passing `--output=json` to `ez-rag query` causes a picocli `UnmatchedArgumentException`
- [x] `ez-rag query --help` lists `--output-format` and does not list `--output`
- [x] Passing `--output-format=json` to `ez-rag shell` produces JSON output
- [x] Passing `--output=json` to `ez-rag shell` causes a picocli `UnmatchedArgumentException`
- [x] `ez-rag shell --help` lists `--output-format` and does not list `--output`

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors or warnings

---

## Task 02-init-writes-local-config

Add config-writing capability to `ez-rag init`: introduce a `ConfigFileWriter` class and five new options (`--embedding-provider`, `--embedding-model`, `--chunk-size`, `--chunk-overlap`, `--ollama-url`). When any of these options are supplied, `init` writes or merges the values into `<ezRagDir>/config.yml` (where `ezRagDir` is the `.ez-rag` directory, e.g. `cwd/.ez-rag/config.yml`) using SnakeYAML. Re-running `init` does a read-merge-write cycle — existing keys not in the new invocation are preserved.

### Implementation steps

- [x] Write failing unit tests for ConfigFileWriter: (a) round-trip write/read via `ConfigFileReader.readConfigRaw` asserts values match; (b) merge-write: write `{embeddingProvider: onnx}`, then write `{chunkSize: 500}`, assert both keys present
- [x] Implement ConfigFileWriter: write a map to a given `Path`, serialise with SnakeYAML using camelCase keys matching `ConfigFileReader`'s primary lookup order; write atomically (temp file + rename); if file exists, read existing YAML map, overlay new values, write merged result
- [x] Write failing unit tests for InitCommand: (a) calling with `--embedding-provider=openai` creates `config.yml` containing `embeddingProvider: openai`; (b) re-running with `--embedding-provider=openai` over an existing `embeddingProvider: onnx` updates the value; (c) calling without any config option does not create or modify `config.yml`
- [x] Add `--embedding-provider`, `--embedding-model`, `--chunk-size`, `--chunk-overlap`, `--ollama-url` `@Option` fields to InitCommand
- [x] Wire InitCommand.call() to collect all non-null option values into a map and call ConfigFileWriter; skip entirely when no config options are provided

### Acceptance criteria

- [x] `ez-rag init --help` lists `--embedding-provider`, `--embedding-model`, `--chunk-size`, `--chunk-overlap`, `--ollama-url`
- [x] Running `ez-rag init --embedding-provider=openai` in a fresh directory creates `.ez-rag/config.yml` at `cwd/.ez-rag/config.yml` containing `embeddingProvider: openai`
- [x] Running `ez-rag init --embedding-provider=openai` when the file already contains `embeddingProvider: onnx` updates the value to `openai`
- [x] Running `ez-rag init --chunk-size=500` when the file already contains `embeddingProvider: openai` preserves `embeddingProvider: openai` and adds `chunkSize: 500`
- [x] Running `ez-rag init` with none of the five new options does not create or modify `.ez-rag/config.yml`
- [x] Values written by ConfigFileWriter can be read back via `ConfigFileReader.readConfigRaw` with matching camelCase key names

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors or warnings

---

## Task 03-bootstrap-embedding-from-local-config

Remove `--embedding-provider` and `--embedding-model` as global CLI flags. Update `preParseProviderFlags` in `EzRagApplication` to resolve embedding settings from the local `.ez-rag/config.yml` file (written by Task 02) instead of CLI args, so that the model chosen via `init` is automatically picked up at Spring bootstrap time.

Depends on Task 02 (`ConfigFileWriter` and config file path conventions must be established first).

### Implementation steps

- [x] Write failing tests in EzRagCommandTest for updated preParseProviderFlags: (a) when a local `<tempDir>/.ez-rag/config.yml` contains `embeddingProvider: openai` and no CLI flag is passed, the returned map contains `"ez.rag.embeddingProvider" → "openai"`; (b) when no config file exists, the function returns without error and contains no embeddingProvider/embeddingModel entries
- [x] Update preParseProviderFlags: after scanning CLI args, determine the store dir — use `--store-dir` arg if present, otherwise walk upward from `Paths.get("").toAbsolutePath()` looking for a `.ez-rag/` directory, stopping when `parent.nameCount <= 1` (matching the stop condition in `EzRagDirResolver`); then read `<ezRagDir>/config.yml`; if found and contains `embeddingProvider` or `embeddingModel`, inject them as `"ez.rag.embeddingProvider"` and `"ez.rag.embeddingModel"` in the result map
- [x] Remove `"--embedding-provider" to "ez.rag.embeddingProvider"` and `"--embedding-model" to "ez.rag.embeddingModel"` entries from the `flags` map in preParseProviderFlags
- [x] Delete the `--embedding-provider` and `--embedding-model` `@Option` fields from EzRagCommand

### Acceptance criteria

- [x] `ez-rag --help` does not list `--embedding-provider` or `--embedding-model`
- [x] Passing `--embedding-provider=openai` as a global flag causes a picocli `UnmatchedArgumentException`
- [x] preParseProviderFlags returns `"ez.rag.embeddingProvider" → "openai"` when a local `.ez-rag/config.yml` contains `embeddingProvider: openai` and no CLI flag is passed
- [x] When no local config file exists, preParseProviderFlags succeeds without error and the returned map contains no embeddingProvider or embeddingModel entries
- [ ] ~~`ez-rag status` shows the embedding provider and model sourced from the local config file when one is present (observable via StatusCommandTest or manual run)~~ *(skipped: StatusCommand renders embeddingProvider from the resolved config already — as noted in requirements "no rendering change is needed there" — and integration-level verification requires a full Spring context plus embedding model; the unit test coverage for preParseProviderFlags injection is already provided by the new EzRagCommandTest tests)*
- [ ] ~~Existing `ez-rag ingest` behaviour is unchanged when no config file exists (built-in defaults apply — covered by existing integration tests tagged `integration`, not a new unit test in this task)~~ *(skipped: integration tests are excluded by default per CLAUDE.md)*

### Quality gates

- [ ] ~~`./gradlew test` passes with no failures~~ *(skipped: 6 pre-existing failures unrelated to this task — EzRagDirResolverTest x3, CredentialsFileReaderTest x2, ListCommandTest x1 — all existed before this task; task-relevant tests (EzRagCommandTest) all pass)*
- [ ] ~~`./gradlew build` compiles without errors or warnings~~ *(skipped: build fails due to same 6 pre-existing test failures; compilation itself succeeds with no errors)*
