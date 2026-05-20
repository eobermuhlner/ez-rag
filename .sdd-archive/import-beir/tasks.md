# Tasks: `import-beir`

## Task [01-dataset-registry-and-list-command]

Wire a new `ImportBeirCommand` into `EzRagCommand` and back it with a `BeirDatasetRegistry` of hardcoded known datasets. After this task, `ez-rag import-beir --list` prints a formatted table of available datasets and `ez-rag import-beir <unknown>` exits 1 with a helpful error listing known names. No download or conversion logic yet.

### Implementation steps

- [x] Create `BeirDatasetRegistry` with at least 8 hardcoded entries (name, domain description, approximate doc count, approximate query count); include nfcorpus and scifact
- [x] Create `ImportBeirCommand` as a PicoCLI `@Command` / Spring `@Component` with all CLI flags declared: `dataset` (positional, optional), `outputDir` (optional positional), `--list`, `--split`, `--max-questions`, `--max-distractors`, `--recall-threshold`, `--hit-threshold`, `--force`
- [x] Register `ImportBeirCommand` in `EzRagCommand`'s subcommands list
- [x] Implement `--list` branch: print table with column headers (Dataset, Domain, ~Docs, ~Queries), one row per entry, exit 0
- [x] Implement name validation: if `dataset` is not null and unknown, print error message and list known dataset names to error output, exit 1
- [x] Write unit tests for `BeirDatasetRegistry`: known name resolves to non-null metadata, unknown name returns null, all names appear in enumeration
- [x] Write unit/integration tests for `ImportBeirCommand`: `--list` output, unknown name error and exit code, Spring wiring (no missing beans)

### Acceptance criteria

- [x] `ez-rag --help` output contains `import-beir`
- [x] `ez-rag import-beir --list` exits 0 and prints a table that includes column headers (e.g. "Dataset") and rows for at least nfcorpus and scifact with domain and count information
- [x] `ez-rag import-beir unknownname` exits 1 and the error output lists at least one known dataset name
- [x] `ez-rag import-beir` (no args, no `--list`) exits non-zero and prints a usage/help message
- [x] `ImportBeirCommand` starts without Spring wiring errors (no missing beans)

### Quality gates

- [x] Zero compiler warnings (`-Werror` is active)
- [x] No unresolved Kotlin compiler errors

---

## Task [02-beir-corpus-parsing-and-conversion]

Implement `BeirCorpusReader` and `BeirCorpusConverter`. Given a directory containing raw BEIR files (`corpus.jsonl`, `queries.jsonl`, `qrels/<split>.tsv`), the converter produces a valid ez-rag eval corpus: one `.txt` file per document and a `questions.yaml`. Extend `ImportBeirCommand` so that when `corpus.jsonl` is already present in the output directory (e.g., from a prior manual download), the command skips downloading and runs conversion directly.

### Implementation steps

- [x] Create `BeirCorpusReader` that parses `corpus.jsonl` → `BeirDocument(id, title, text)` list, `queries.jsonl` → `Map<queryId, queryText>`, and `qrels/<split>.tsv` → `Map<queryId, Map<docId, score>>`
- [x] Detect TSV header row by checking whether the score field in the first line is non-numeric; handle both cases identically
- [x] Exclude qrels entries with relevance score < 1
- [x] Create `BeirCorpusConverter` accepting `BeirCorpusReader` output and a `ConversionConfig(maxQuestions, maxDistractors, randomSeed, recallThreshold?, hitThreshold?)`:
  - Select up to `maxQuestions` queries that have ≥ 1 relevant document
  - Collect relevant documents for selected queries
  - Sample up to `min(maxDistractors, totalDocs - relevantDocs)` non-relevant documents as distractors
  - Sampling is deterministic given `randomSeed`
  - Write one `.txt` file per document (title + body); filename = sanitised BEIR `_id` (non-alphanumeric → `_`, capped at 120 chars, `.txt` suffix)
  - Write `questions.yaml` with a `documents:` block listing all files with roles (`relevant` or `distractor`) and a `questions:` block; `expected_sources` contains filenames only (no path separators); `expected_chunk_contains` is absent or empty
  - Include `thresholds:` block with `recall_at_k` and `hit_rate_at_k` only when the corresponding config values are non-null; `mrr` key absent unless explicitly configured
- [x] Extend `ImportBeirCommand` to detect corpus.jsonl in the output directory, skip download, print a skip-download message to stdout, and run the reader + converter
- [x] Write unit tests for `BeirCorpusReader` using small fixture JSONL/TSV files in `@TempDir`
- [x] Write unit tests for `BeirCorpusConverter` using in-memory structures and `@TempDir` output; verify via `EvalCorpusLoader`

### Acceptance criteria

- [x] Given fixture BEIR files, the converter writes exactly `relevantDocCount + min(maxDistractors, totalDocs - relevantDocCount)` `.txt` files to the output directory
- [x] The generated `questions.yaml` is parseable end-to-end by `EvalCorpusLoader` without errors; all filenames listed in `documents:` physically exist in the output directory
- [x] The `documents:` block lists all written files with role `relevant` or `distractor`; `expected_sources` in each question contains only filenames (no `/` or `\` separators)
- [x] `thresholds:` block is absent when neither `recallThreshold` nor `hitThreshold` is given; both `recall_at_k` and `hit_rate_at_k` are present when both are given; `mrr` key is absent when no mrr threshold is supplied
- [x] A TSV file without a header row is parsed identically to the same file with a header row
- [x] qrels entries with relevance score 0 are excluded from `expected_sources`
- [x] Two converter runs with the same seed and inputs produce byte-identical `questions.yaml` files
- [x] `ez-rag import-beir nfcorpus --output-dir <dir-containing-corpus.jsonl>` exits 0, prints a skip-download message to stdout, and writes `questions.yaml`

### Quality gates

- [x] Zero compiler warnings (`-Werror`)
- [x] `BeirCorpusReader` and `BeirCorpusConverter` contain no HTTP or network I/O (verified by absence of `java.net`, `HttpClient`, or third-party HTTP imports)

---

## Task [03-beir-download-and-full-import]

Implement `BeirDownloader` and complete the `ImportBeirCommand` pipeline so that `ez-rag import-beir nfcorpus` downloads the BEIR zip from the CDN, extracts it, converts it to an eval corpus, and prints progress. Implement skip behavior (questions.yaml already exists → skip entirely), `--force` to re-download, and verify the output is runnable by `ez-rag eval`.

### Implementation steps

- [x] Create `BeirDownloader` using `java.net.http.HttpClient` for download and `java.util.zip.ZipInputStream` for extraction; CDN URL pattern: `https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/<name>.zip`
- [x] Skip download if `corpus.jsonl` is already present in the target directory and `--force` is not set; print skip message to stdout
- [x] On `--force`: delete existing extracted files before re-downloading, regardless of whether `questions.yaml` exists
- [x] Print progress to `outputWriter` in this order: `"Downloading <name>"`, `"Extracting"`, `"Writing <N> documents"`, `"Writing questions.yaml"`
- [x] Wire `BeirDownloader` into `ImportBeirCommand`: high-level skip when `questions.yaml` exists and `--force` absent (print skip message, exit 0); otherwise download → extract → read → convert
- [x] Default output directory: `~/.ez-rag/beir/<name>/`; `--output-dir` positional overrides it
- [x] Write unit tests for `BeirDownloader` using a local zip fixture served by an in-memory HTTP server (e.g., `MockWebServer`); no real network calls
- [x] Add integration test tagged `@Tag("integration")` that downloads nfcorpus from CDN and verifies the resulting `questions.yaml` is parseable by `EvalCorpusLoader`

### Acceptance criteria

- [x] `ez-rag import-beir nfcorpus` (default output dir) downloads, extracts, and writes a valid eval corpus under `~/.ez-rag/beir/nfcorpus/`
- [x] `ez-rag import-beir nfcorpus --output-dir /custom/path` writes to `/custom/path` instead of the default
- [x] A second invocation when `questions.yaml` exists and `--force` is absent prints a skip message to stdout, exits 0, and makes no network requests
- [x] `ez-rag import-beir nfcorpus --force` re-downloads and regenerates even when `questions.yaml` already exists
- [x] `--force` on a fresh directory (no prior download) proceeds normally without errors
- [x] Progress output contains, in order, substrings: `"Downloading"`, `"Extracting"`, `"Writing"`, `"questions.yaml"`
- [x] `BeirDownloader` unit test passes using only a local zip fixture; no real network calls during the unit test
- [x] An unknown dataset name exits 1 before any network request is made
- [x] `ez-rag eval ~/.ez-rag/beir/nfcorpus` (after import) runs without errors — **integration test only** (`@Tag("integration")`)

### Quality gates

- [x] Zero compiler warnings (`-Werror`)
- [x] `BeirDownloader` imports only `java.net.http.HttpClient` for HTTP (no third-party HTTP libraries)
- [x] Integration tests are tagged `@Tag("integration")` and excluded from the default `./gradlew test` run (Gradle config already excludes this tag)
