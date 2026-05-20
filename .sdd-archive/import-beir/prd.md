# PRD: `ez-rag import-beir`

## Problem Statement

Evaluating retrieval quality against the hand-crafted built-in scenarios (factual, multi-chunk,
hard-negatives, complex-md) tells users whether the pipeline is working, but cannot reveal
how the system compares to published benchmarks or how it degrades under realistic retrieval
difficulty. The built-in corpus is too clean: documents cover distinct topics, questions share
vocabulary with their source documents, and scores are consistently near 1.0.

Users who want a meaningful benchmark — one where the retriever can actually fail — have no
built-in path. The existing Python conversion script (`scripts/beir_to_ezrag.py`) works but
requires Python and is a manual two-step process that sits outside the main tool.

## Solution

Add a new `ez-rag import-beir` subcommand that downloads a BEIR dataset by name, converts it
to the standard ez-rag eval corpus format (document files + `questions.yaml`), and saves it to
a local directory. The user then runs `ez-rag eval` on that directory as usual — no changes to
the eval pipeline required.

```sh
# First run: download and convert
ez-rag import-beir nfcorpus

# Evaluate (re-runnable with different embedding providers, no re-download)
ez-rag eval ~/.ez-rag/beir/nfcorpus

# Browse available datasets
ez-rag import-beir --list
```

## User Stories

1. As a developer, I want to run `ez-rag import-beir --list` so that I can see which BEIR
   datasets are available and choose one appropriate for my domain.
2. As a developer, I want to run `ez-rag import-beir nfcorpus` so that the dataset is
   downloaded and converted without needing Python or any external tools.
3. As a developer, I want the converted corpus saved to `~/.ez-rag/beir/nfcorpus/` by default
   so that I do not have to specify a path every time.
4. As a developer, I want to override the output directory with a positional argument so that
   I can store corpora on a different disk or path.
5. As a developer, I want `import-beir` to skip the download if the corpus already exists so
   that repeated invocations are fast and idempotent.
6. As a developer, I want to pass `--force` to re-download and overwrite an existing corpus so
   that I can refresh after a dataset update or a corrupted download.
7. As a developer, I want unknown dataset names to fail immediately with a list of known names
   so that I get a helpful error rather than a 404 from the CDN.
8. As a developer, I want to run `ez-rag eval ~/.ez-rag/beir/nfcorpus` after importing so that
   I can measure retrieval quality using the same eval pipeline I use for built-in scenarios.
9. As a developer, I want to re-run `ez-rag eval` on the same imported corpus with a different
   `--embedding-provider` so that I can compare embedding models without re-downloading.
10. As a developer, I want the generated `questions.yaml` to contain no `thresholds:` block by
    default so that the first run is always metrics-only and I can establish a baseline.
11. As a developer, I want to pass `--recall-threshold` and `--hit-threshold` so that I can
    opt into pass/fail evaluation once I have a known baseline.
12. As a developer, I want `--max-questions` (default 50) so that I can limit the number of
    questions for a quick sanity check.
13. As a developer, I want to set `--max-questions` to the full test-split size (e.g. 323 for
    nfcorpus) so that I can run the complete published benchmark.
14. As a developer, I want `--max-distractors` (default 20) so that I can control how many
    non-relevant documents are added as distractors in the corpus.
15. As a developer, I want `--split` (default `test`) so that I can use the `dev` split for
    iterative tuning without contaminating the test split.
16. As a developer, I want each document written as a plain `.txt` file containing the title
    and body so that the existing ingestion and chunking pipeline handles it without changes.
17. As a developer, I want the `questions.yaml` `expected_sources` to list only filenames, not
    full paths, so that the corpus directory is relocatable.
18. As a developer, I want the conversion to use the same `questions.yaml` format as the
    built-in scenarios so that `ez-rag eval` requires no changes.
19. As a developer, I want `import-beir` to print progress (downloading, extracting, writing
    N documents, writing questions.yaml) so that I know the command has not hung.
20. As a developer, I want the download to be resumable-safe: if the zip extraction fails
    midway, a subsequent run with `--force` should produce a clean result.

## Implementation Decisions

### Modules

**`BeirDatasetRegistry`** (new, pure data)
A registry of known BEIR datasets, each with name, approximate document count, query count,
and domain description. Provides lookup by name (returning null for unknown names) and
enumeration for `--list`. No I/O; trivially testable.

**`BeirDownloader`** (new, I/O)
Responsible for downloading the BEIR zip from the CDN and extracting it to a local directory.
Accepts the dataset name, target directory, and a `force` flag. Skips the download if the
target directory already contains the expected BEIR layout (corpus.jsonl present). Uses
`java.net.http.HttpClient` for the download and `java.util.zip.ZipInputStream` for extraction,
both part of the standard library. Prints progress to a `PrintWriter`.

CDN URL pattern: `https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/<name>.zip`

**`BeirCorpusReader`** (new, pure parsing)
Parses the three BEIR files from a directory into typed in-memory structures:
- `corpus.jsonl` → list of `BeirDocument(id, title, text)`
- `queries.jsonl` → map of `queryId → queryText`
- `qrels/<split>.tsv` → map of `queryId → map of docId → relevanceScore`

Handles both TSV files with and without a header row. Relevance threshold is score ≥ 1.
No HTTP, no file writing; fully testable with small fixture files.

**`BeirCorpusConverter`** (new, pure conversion)
Takes the parsed BEIR structures and a `ConversionConfig` (maxQuestions, maxDistractors,
randomSeed, recallThreshold, hitThreshold) and produces an ez-rag eval corpus:
- Writes one `.txt` file per document to the output directory
- Writes `questions.yaml` in the standard ez-rag format
- Sampling is deterministic given a fixed seed
- Document filenames are derived by sanitising the BEIR `_id` (replace non-alphanumeric
  characters with underscores, cap at 120 characters, append `.txt`)
- Fully testable without any disk I/O by injecting a file-writing abstraction or by using
  a temp directory in tests

**`ImportBeirCommand`** (new, thin orchestrator)
Picocli `@Command` / Spring `@Component` registered in `EzRagCommand`. Wires the four
modules above. Handles:
- `--list` flag (prints registry table, exits 0)
- Dataset name validation (print error + known names, exit 1)
- Default output directory resolution (`~/.ez-rag/beir/<dataset>/`)
- Progress printing via `outputWriter`

No business logic in this class.

### CLI flags summary

| Flag | Type | Default | Description |
|---|---|---|---|
| `dataset` | positional param | — | Dataset name (validated against registry) |
| `outputDir` | optional positional | `~/.ez-rag/beir/<dataset>/` | Output directory |
| `--list` | boolean | false | Print known datasets and exit |
| `--split` | string | `test` | qrels split to use |
| `--max-questions` | int | 50 | Maximum questions to include |
| `--max-distractors` | int | 20 | Maximum distractor documents |
| `--recall-threshold` | double? | null | Adds `recall_at_k` to `thresholds:` block |
| `--hit-threshold` | double? | null | Adds `hit_rate_at_k` to `thresholds:` block |
| `--force` | boolean | false | Re-download even if corpus exists |

### Unchanged components
`EvalCorpusLoader`, `EvalEngine`, `EvalMetricsCalculator`, `EvalReporter`, `EvalCommand` —
all unchanged. The generated corpus is indistinguishable from a hand-authored scenario.

### BEIR document roles
All documents referenced by selected queries are marked `relevant`. Sampled non-relevant
documents are marked `distractor`. No `hard-negative` role is assigned (BEIR does not
provide hard-negative labels).

## Testing Decisions

A good test exercises the public contract of a module — its inputs and outputs — without
asserting on internal implementation details (class names, private method order, etc.).

**`BeirDatasetRegistry`**
Unit tests verifying: known names resolve to non-null metadata; an unknown name returns null;
`--list` output contains all known dataset names. No I/O.

**`BeirCorpusReader`**
Unit tests using small fixture JSONL/TSV files written to a `@TempDir`. Verify: documents
are parsed with correct ids, titles, and bodies; queries map correctly; qrels with and without
header are both parsed; scores below 1 are excluded. Prior art: `EvalCorpusLoader` tests.

**`BeirCorpusConverter`**
Unit tests using in-memory `BeirDocument`/query/qrel structures and a `@TempDir` output
directory. Verify: correct number of `.txt` files written; `questions.yaml` parses correctly
via `EvalCorpusLoader`; only relevant docs appear in `expected_sources`; distractor count
is bounded; `thresholds:` block is absent when no threshold flags given, and present when
they are. Prior art: `EvalCorpusLoader` tests.

**`BeirDownloader`**
Unit test using a local zip fixture served by a `MockWebServer` (or constructed in-memory)
to verify extraction layout. Verify: skip behaviour when corpus already exists; `--force`
overwrites; progress messages are written to the `PrintWriter`.

**`ImportBeirCommand`**
Integration test constructing the command with a mock downloader and verifying exit codes and
output messages for: `--list`, unknown dataset name, existing corpus (skip message), full
conversion producing a valid `questions.yaml`. Prior art: `IngestCommandTest`,
`EvalCommandTest`.

## Out of Scope

- Multi-hop / reasoning questions (BEIR is single-hop retrieval only)
- Uploading or sharing converted corpora
- Support for BEIR datasets not hosted at the standard CDN URL
- Incremental updates to an existing converted corpus
- Parallel download of multiple datasets
- Converting BEIR datasets to the `hard-negative` role (BEIR has no hard-negative labels)
- Changes to `ez-rag eval`, `EvalCorpusLoader`, or any existing eval module
- The Python script `scripts/beir_to_ezrag.py` — it remains as-is

## Further Notes

- The BEIR CDN does not require authentication; all listed datasets are publicly available.
- nfcorpus and scifact are the recommended starting datasets: nfcorpus exposes the
  vocabulary mismatch problem (medical queries vs. clinical documents); scifact has clean
  binary relevance (one correct document per query) making MRR = Recall@k.
- The full nfcorpus test split (323 queries) takes ~30 seconds to ingest and evaluate with
  the default ONNX embedding model. Users running in CI should use `--max-questions 50`.
- BEIR relevance scores ≥ 1 are treated as relevant, consistent with published BEIR
  evaluation practice. Most datasets use only 0 and 1; a few (e.g. trec-covid) use 0/1/2.
