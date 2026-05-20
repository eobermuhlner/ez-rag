# Eval Corpus Format

This document describes the YAML format used by `ez-rag eval` to define evaluation scenarios.

## Overview

Each scenario lives in its own subdirectory under the corpus root. The harness discovers scenarios by finding all `questions.yaml` files recursively under the corpus root directory.

```
corpus-root/
  factual/
    questions.yaml
    astronomy.md
    cooking.md
  multi-chunk/
    questions.yaml
    long-document.md
```

## `questions.yaml` Schema

```yaml
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
    expected_answer_contains: ["event horizon"]  # optional

thresholds:                 # optional block; omit entirely for metrics-only mode
  recall_at_k: 0.8
  mrr: 0.6
  hit_rate_at_k: 0.9
```

## Fields

### `documents` (required)

A list of document entries, each with:

| Field | Type   | Required | Description |
|-------|--------|----------|-------------|
| `file` | string | yes | Path to the document file, relative to the scenario directory. |
| `role` | string | yes | Classification of the document. See [Document Roles](#document-roles) below. |

All document files listed here are ingested into a fresh temporary store before questions are evaluated.

### `questions` (required)

A list of question entries, each with:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | yes | Unique identifier for the question within the scenario. |
| `question` | string | yes | The question text passed to `SearchCommand`. |
| `expected_sources` | list of strings | yes | Filenames (not paths) of documents that should appear in the top-k search results for this question to count as a hit. |
| `expected_answer_contains` | list of strings | no | Keywords the answer should contain. Currently informational only; not used for pass/fail evaluation. |

### `thresholds` (optional)

If this block is omitted, the scenario runs in **metrics-only mode**: metrics are reported but never cause a build failure.

If the block is present, each field is independently optional. A threshold is enforced only when its value is set.

| Field | Type | Description |
|-------|------|-------------|
| `recall_at_k` | float | Minimum required Recall@k (fraction of questions with a hit in top-k). |
| `mrr` | float | Minimum required Mean Reciprocal Rank. |
| `hit_rate_at_k` | float | Minimum required Hit Rate@k (synonymous with Recall@k). |

The `ez-rag eval` command exits non-zero when any threshold-bearing scenario fails at least one threshold.

## Document Roles

| Role | Meaning |
|------|---------|
| `relevant` | This document answers one or more questions. It should appear in top-k results for those questions. |
| `distractor` | Unrelated noise. Included to test that the retriever does not return irrelevant content. |
| `hard-negative` | Topically similar to the relevant documents but does not answer any question. The most diagnostically useful role: a good retriever should prefer `relevant` over `hard-negative` documents. |

## Value of `k`

`k` defaults to **5**, matching the default `topK` of `SearchCommand`. This value is currently global and cannot be overridden per scenario.

## Metrics

- **Recall@k**: Fraction of questions for which at least one `expected_source` appears in the top-k results.
- **Hit Rate@k**: Synonymous with Recall@k (binary per question, then averaged).
- **MRR** (Mean Reciprocal Rank): For each question, the reciprocal of the rank of the first `expected_source` in the results (0 if not found in top k); averaged over all questions.

## Example

The built-in corpus in `src/test/resources/eval/` contains several ready-made scenarios:

- `factual/` — short plain-text documents with unambiguous factual content and direct-lookup questions.
- `factual-md/` — same content as `factual/` but in Markdown format, to verify format handling.
- `multi-chunk/` — longer documents where answers span chunk boundaries.
- `hard-negatives/` — documents with topically similar but non-answering hard negatives.
- `complex-md/` — Markdown documents with complex formatting (multi-column tables, nested bullet
  lists, inline citations, blockquotes, YAML front matter) to verify that structured content is
  chunked and indexed correctly.

To run these scenarios:

```sh
./gradlew test -Dtags=eval
```

To run eval against your own corpus:

```sh
ez-rag eval /path/to/your/corpus
```
