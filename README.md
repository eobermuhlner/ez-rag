# ez-rag

A command-line tool for RAG (retrieval-augmented generation). Ingest documents into a local vector store, then query them using any supported LLM provider.

## Requirements

- JDK 21 or later (the build scripts embed a JDK 21 toolchain as fallback)

No API key is required for basic use. The default configuration uses a local ONNX embedding model (`all-MiniLM-L6-v2`) and returns retrieved context chunks directly instead of calling an LLM. To get LLM-generated answers, configure a provider explicitly (see [Providers](#providers)).

## Build and install

```sh
./gradlew installDist
```

This produces a self-contained distribution at `build/install/ez-rag/`. Add the `bin/` directory to your PATH or create a symlink:

```sh
ln -s "$PWD/build/install/ez-rag/bin/ez-rag" ~/.local/bin/ez-rag
```

Verify the installation:

```sh
ez-rag --help
```

## Quick start

No configuration needed — works out of the box:

```sh
# Ingest a directory of documents (downloads embedding model on first run)
ez-rag ingest ./docs

# Retrieve relevant chunks (passthrough mode: no LLM required)
ez-rag query "What is the project's license?"

# Multi-word questions can be passed without quotes
ez-rag query What is the project license?
```

To get LLM-generated answers, specify a provider:

```sh
# Using environment variables (works anywhere, including CI/CD)
export OPENAI_API_KEY=sk-...
ez-rag query --provider openai "Summarize the architecture."

export ANTHROPIC_API_KEY=sk-ant-...
ez-rag query --provider anthropic "What are the main features?"

# Or store the key in a credentials file (see API keys section below)
ez-rag query --provider openai "Summarize the architecture."

# Fully local setup with Ollama (no API key)
ez-rag ingest --embedding-provider ollama ./docs
ez-rag query --provider ollama --embedding-provider ollama "What are the main features?"
```

```sh
# Pipe a question from stdin
echo "What is X?" | ez-rag search
```

## Commands

| Command                        | Description                                                                                        |
|--------------------------------|----------------------------------------------------------------------------------------------------|
| `init`                         | Initialize a `.ez-rag/` workspace in the current directory and add the store to `.gitignore`.      |
| `ingest <file\|dir>`           | Ingest files or directories (recursive) into the vector store. Supports `.txt`, `.pdf`, `.md`. Prints each file as it is ingested. |
| `delete <file> [<file>...]`    | Remove one or more ingested documents from the vector store without touching other content.        |
| `list`                         | List all ingested documents with chunk counts and staleness flags. Use `--output-format json` for machine-readable output with absolute paths. |
| `show <file>`                  | Show per-chunk metadata (and optionally raw text) for an ingested file. Useful for debugging retrieval. |
| `query [<word>...]`            | Retrieve relevant chunks and answer using an LLM. Reads question from positional args or stdin.    |
| `status`                       | Show store health, aggregate counts, active configuration, and credential status.                  |
| `search [<word>...]`           | Pure embedding search returning raw chunks without LLM involvement. Reads question from positional args or stdin. |
| `eval <corpus-dir>`            | Evaluate retrieval quality against a corpus of scenarios. Exits 1 if any threshold fails.          |
| `mcp-server`                   | Run as an MCP server over stdio (for Claude Code and other agentic tools).                         |
| `shell`                        | _(not yet implemented)_ Interactive REPL mode.                                                     |

Every command accepts `--help` for details.

### init

Initialize a `.ez-rag/` workspace in the current directory:

```sh
ez-rag init
```

Output (first run):
```
Initialized .ez-rag/ in /path/to/project/.ez-rag
```

Output (already exists):
```
.ez-rag/ already exists at /path/to/project/.ez-rag
```

`init` also adds `.ez-rag/vector-store.json` to the project's `.gitignore` (if the file exists) so the vector store is never accidentally committed. Running `ingest` without calling `init` first works too — the directory is created automatically.

## Ingest-specific flags

| Flag             | Description                                                                                   |
|------------------|-----------------------------------------------------------------------------------------------|
| `--quiet` / `-q` | Suppress per-file output; print only the final summary line.                                  |
| `--details`      | Print chunk details (token count and text preview) for each ingested file.                    |

By default, `ingest` prints one line per file as it processes it:

```
Ingesting: docs/getting-started.md
Ingesting: docs/configuration.md
Skipping: docs/old-guide.md (already ingested)
2 files ingested, 14 chunks created, 1 skipped
```

Pass `--quiet` to suppress everything except the summary:

```
2 files ingested, 14 chunks created, 1 skipped
```

Pass `--details` to also print chunk token counts and text previews, useful for tuning `--chunk-size`:

```
Ingesting: docs/getting-started.md
  Chunk 0 [312 tokens]: "Introduction This guide walks you through…"
  Chunk 1 [287 tokens]: "Configuration All settings can be overrid…"
1 files ingested, 2 chunks created, 0 skipped
```

### delete

Remove one or more ingested documents from the vector store:

```sh
ez-rag delete docs/old-guide.md
```

Output:
```
Deleted: /absolute/path/to/docs/old-guide.md (7 chunks)
```

Delete multiple files in a single command:

```sh
ez-rag delete docs/old-guide.md docs/deprecated.md
```

Pass `--quiet` (or `-q`) to suppress all output on success:

```sh
ez-rag delete --quiet docs/old-guide.md
```

If the file was never ingested, `delete` prints a warning but still exits 0 (safe for scripts):

```
Warning: not found in store: /absolute/path/to/docs/old-guide.md
```

| Flag             | Description                                         |
|------------------|-----------------------------------------------------|
| `--quiet` / `-q` | Suppress output on success; warnings are still printed. |

After deletion, running `ez-rag ingest` on the same file re-ingests it normally.

### show

Inspect how an ingested file was chunked, including per-chunk metadata:

```sh
ez-rag show docs/getting-started.md
```

Output:
```
File: /absolute/path/to/docs/getting-started.md
Chunks: 3

Chunk 1 — 842 chars, mtime: 1716000000000
Chunk 2 — 1021 chars, mtime: 1716000000000
Chunk 3 — 634 chars, mtime: 1716000000000
```

Pass `--chunks` to also print the raw text of each chunk beneath its metadata line:

```sh
ez-rag show --chunks docs/getting-started.md
```

Pass `--output json` for machine-readable output suitable for scripting with `jq`:

```sh
ez-rag show --output json docs/getting-started.md
```

JSON output:
```json
{
  "file": "/absolute/path/to/docs/getting-started.md",
  "chunks": [
    { "chunkIndex": 0, "charCount": 842, "mtime": 1716000000000 },
    { "chunkIndex": 1, "charCount": 1021, "mtime": 1716000000000 },
    { "chunkIndex": 2, "charCount": 634, "mtime": 1716000000000 }
  ]
}
```

Combine `--output json --chunks` to include the raw `text` field in each chunk object.

If the file was never ingested, `show` exits with a non-zero code and prints an error to stderr.

| Flag            | Description                                               |
|-----------------|-----------------------------------------------------------|
| `--chunks`      | Include raw chunk text in output.                         |
| `--output`      | Output format: `text` (default) or `json`.                |

### list

List all ingested documents with their chunk counts:

```sh
ez-rag list
```

Output (paths relative to current working directory):
```
docs/getting-started.md  (3 chunks)
docs/old-guide.md  (1 chunks)  [STALE]
```

Documents marked `[STALE]` have been modified on disk since they were last ingested, or their source file no longer exists. Re-ingest them to bring the store up to date.

Documents are listed in alphabetical order by path.

Pass `--output-format json` for machine-readable output with absolute paths:

```sh
ez-rag list --output-format json
```

```json
[
  { "path": "/abs/path/to/docs/getting-started.md", "chunks": 3, "stale": false },
  { "path": "/abs/path/to/docs/old-guide.md", "chunks": 1, "stale": true }
]
```

If no store exists, `list` prints an error to stderr and exits with code `1`.

| Flag                | Description                                             |
|---------------------|---------------------------------------------------------|
| `--output-format`   | Output format: `text` (default) or `json`.              |

### status

Show store health, aggregate counts, and active configuration:

```sh
ez-rag status
```

Output:
```
Store: /path/to/.ez-rag/vector-store.json
Chunks: 14
Documents: 2
Size: 142 KB
Stale documents: 0
Last ingest time: 2026-05-17T10:30:00Z

Configuration:
  storeDir:
  provider: passthrough
  model:
  embeddingProvider: onnx
  embeddingModel: all-MiniLM-L6-v2
  rerankModel: cross-encoder/ms-marco-MiniLM-L-6-v2
  chunkSize: 1000
  chunkOverlap: 200
  topK: 5
```

The `Credentials:` section is shown only when a provider that needs an API key is active. `status` no longer lists individual documents — use `ez-rag list` for that.

Pass `--output-format json` for machine-readable output:

```sh
ez-rag status --output-format json
```

| Flag                | Description                                             |
|---------------------|---------------------------------------------------------|
| `--output-format`   | Output format: `text` (default) or `json`.              |

### eval

Evaluate retrieval quality against a corpus of scenarios:

```sh
ez-rag eval ./my-corpus
```

Output:
```
Scenario          Questions  Recall@3  MRR     Hit@3   Status
─────────────────────────────────────────────────────────────
factual           16         0.94      0.63    0.94    PASS
hard-negatives    12         1.00      0.88    1.00    PASS
  hard-negative              1.00      0.75    1.00
multi-chunk       14         0.93      0.63    0.93    PASS
─────────────────────────────────────────────────────────────
Overall           42         0.96      0.71    0.96
```

How to read the output:

- Each scenario row covers **all questions** in that scenario directory, regardless of document role.
- The indented `hard-negative` sub-row covers only the **subset of questions whose expected source is a hard-negative document**. It has no Questions count (it is a subset, not an independent scenario) and no Status (thresholds apply to the scenario as a whole, not the subset).
- `Overall` averages the per-scenario metrics weighted equally across scenarios, not questions.
- `Status` is `PASS` when all configured thresholds are met, `FAIL (<metric> < <threshold>)` for the first failing metric, or blank when no thresholds are defined.

Exit code is `0` when all thresholds pass (or no thresholds are defined), `1` when any threshold fails. This makes `eval` suitable for use in CI pipelines.

Pass `--format json` for machine-readable output:

```sh
ez-rag eval --format json ./my-corpus
```

| Flag       | Description                               |
|------------|-------------------------------------------|
| `--format` | Output format: `text` (default) or `json`.|

#### Corpus format

A corpus is a directory containing one or more scenario subdirectories. Each subdirectory must contain a `questions.yaml` file and the document files it references.

```
my-corpus/
  factual/
    questions.yaml
    planets.txt
    capitals.txt
    elements.txt
    cooking_distractor.txt
```

`questions.yaml` format:

```yaml
documents:
  - file: planets.txt
    role: relevant          # relevant | distractor | hard-negative
  - file: capitals.txt
    role: relevant
  - file: cooking_distractor.txt
    role: distractor        # included in the store but not an expected source

questions:
  - id: q1
    question: "What is the capital of France?"
    expected_sources: ["capitals.txt"]
  - id: q2
    question: "What is the largest planet in the solar system?"
    expected_sources: ["planets.txt"]

thresholds:               # optional; eval exits 1 if any threshold is missed
  recall_at_k: 0.9
  mrr: 0.8
  hit_rate_at_k: 0.9
```

**Document roles:**

| Role            | Description                                                                                         |
|-----------------|-----------------------------------------------------------------------------------------------------|
| `relevant`      | Contains answer content; retrieved chunks from these files count as hits.                           |
| `distractor`    | Ingested into the store but not expected as a source; tests that irrelevant content is not returned. |
| `hard-negative` | Similar to the query topics but does not contain the answer; the hardest kind of distractor. Hard-negative metrics are reported separately as an indented sub-row. |

**Metrics:**

| Metric       | Description                                                                                                           |
|--------------|-----------------------------------------------------------------------------------------------------------------------|
| `Recall@3`   | Fraction of questions for which the expected chunk appears anywhere in the top 3 results. The primary pass/fail metric. |
| `MRR`        | Mean Reciprocal Rank. For each question, the score is 1/rank of the first relevant result (1.0 if rank 1, 0.5 if rank 2, 0.33 if rank 3, 0 if not found). Averaged across all questions. Sensitive to ordering — a high MRR means relevant chunks tend to appear first. |
| `Hit@3`      | Same as Recall@3 when each question has a single expected source. Differs when a question has multiple expected sources: Recall@3 counts partial credit, Hit@3 is binary (1 if any expected source is found, 0 otherwise). |

## Search-specific flags

| Flag            | Default | Description                                      |
|-----------------|---------|--------------------------------------------------|
| `--top-k N`     | `5`     | Maximum number of chunks to return               |
| `--min-score T` | `0.0`   | Minimum similarity score; lower-scoring chunks are filtered out |
| `--output`      | `text`  | Output format: `text` or `json`                  |

## Global flags

These flags apply to all subcommands:

| Flag                      | Default                  | Description                                    |
|---------------------------|--------------------------|------------------------------------------------|
| `--provider`              | `passthrough`            | Chat provider: `passthrough`, `openai`, `anthropic`, `ollama` |
| `--embedding-provider`    | `onnx`                   | Embedding provider: `onnx`, `openai`, `ollama` |
| `--model`                 | provider default         | Override the chat model name                   |
| `--embedding-model`       | provider default         | Override the embedding model name              |
| `--rerank-model`          | `cross-encoder/ms-marco-MiniLM-L-6-v2` | Cross-encoder reranker model name. Set to `""` to disable reranking. |
| `--rerank-candidates`     | `topK * 3`               | Number of candidates fetched before reranking. Only relevant when `--rerank-model` is set. |
| `--ollama-url`            | `http://localhost:11434` | Ollama base URL                                |
| `--verbose` / `-v`        | off                      | Enable debug logging; for `query`, also prints each source file path, similarity score, and chunk index to stderr. When reranking is active also prints reranker name and candidate pool size. |

## Providers

### Chat providers

| Provider      | Default model       | API key (env var or credentials file) | Notes                                               |
|---------------|---------------------|---------------------------------------|-----------------------------------------------------|
| `passthrough` | —                   | —                                     | Default. Returns retrieved context chunks directly; no LLM call, no API key required. |
| `openai`      | `gpt-4o-mini`       | `OPENAI_API_KEY`                      |                                                     |
| `anthropic`   | `claude-sonnet-4-6` | `ANTHROPIC_API_KEY`                   |                                                     |
| `ollama`      | `llama3.2`          | `OLLAMA_BASE_URL` (optional)          | Requires a running Ollama instance                  |

### Embedding providers

| Provider | Default model            | Notes                                                                           |
|----------|--------------------------|---------------------------------------------------------------------------------|
| `openai` | `text-embedding-3-small` | Requires `OPENAI_API_KEY`                                                       |
| `ollama` | `nomic-embed-text`       | Requires a running Ollama instance with the model pulled                        |
| `onnx`   | `all-MiniLM-L6-v2`       | Fully local; model downloaded automatically on first use to `~/.ez-rag/models/` |

Anthropic has no embedding API. If you use `--provider anthropic`, set `--embedding-provider openai` or `--embedding-provider onnx`.

The ONNX provider requires no API key and sends no data to external services. It is suitable for air-gapped or privacy-sensitive environments.

## API keys

API keys can be provided in three ways, in priority order:

1. **Environment variable** — `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`. Always wins; suitable for CI/CD.
2. **Project-local credentials file** — `.ez-rag/credentials.yml` in the current working directory. Overrides the home file.
3. **Home credentials file** — `~/.ez-rag/credentials.yml`. Personal default applied to all projects.

### Credentials file format

Create `~/.ez-rag/credentials.yml` (or `.ez-rag/credentials.yml` in your project):

```yaml
openai-api-key: sk-...
anthropic-api-key: sk-ant-...
```

Both kebab-case (`openai-api-key`) and camelCase (`openaiApiKey`) spellings are accepted.

### Security

Restrict the file to owner-read-only to avoid a warning:

```sh
chmod 600 ~/.ez-rag/credentials.yml
```

If the file is readable by group or others, ez-rag prints a warning with the exact `chmod` command to fix it, then continues.

### Project-local credentials and `.gitignore`

When ez-rag reads a project-local credentials file for the first time, it automatically appends `.ez-rag/credentials.yml` to the project's `.gitignore` (if the file exists) and prints a notice. This prevents accidental key commits.

### Diagnosing credential configuration

`ez-rag status` shows where each key is loaded from without revealing the key value:

```
openai-api-key:     set (env var OPENAI_API_KEY)
anthropic-api-key:  set (~/.ez-rag/credentials.yml)
```

If a required key is missing, ez-rag prints an actionable error naming both the environment variable and the credentials file path:

```
Missing API key for provider 'openai'.
Set the OPENAI_API_KEY environment variable, or add 'openai-api-key' to
~/.ez-rag/credentials.yml or .ez-rag/credentials.yml.
```

## Configuration file

Persistent defaults can be set in `~/.ez-rag/config.yml` so you do not have to repeat flags on every invocation. CLI flags always override the config file.

```yaml
#provider: openai           # default: passthrough
#embedding-provider: onnx   # default: onnx
#model: gpt-4o              # default: "" (ignored for passthrough)
#embedding-model: text-embedding-3-small   # default: all-MiniLM-L6-v2
#ollama-url: http://localhost:11434
#store-path: .ez-rag/vector-store.json
#chunk-size: 1000
#chunk-overlap: 200
#top-k: 5
#system-prompt: ""
#output-format: text
#verbose: false
rerank-model: cross-encoder/ms-marco-MiniLM-L-6-v2   # default: cross-encoder/ms-marco-MiniLM-L-6-v2 (reranking enabled by default)
#rerank-candidates: 15   # default: topK * 3 when rerank-model is set
```

All keys support both camelCase (`chunkSize`) and kebab-case (`chunk-size`) spelling.

## Vector store

Documents are stored in a `SimpleVectorStore` JSON file. The default location is `.ez-rag/vector-store.json` relative to the current working directory, following the same convention as `.git/` and `.claude/`. Override the path with `--store <path>` or the `store-path` config key.

## RAG settings

| Setting         | Default               | Description                          |
|-----------------|-----------------------|--------------------------------------|
| `chunk-size`    | 1000                  | Token count per chunk                |
| `chunk-overlap` | 200                   | Overlap between consecutive chunks   |
| `top-k`         | 5                     | Number of chunks retrieved per query |
| `system-prompt` | built-in RAG template | Override the LLM system prompt       |

## Reranking

Reranking is an optional second-pass stage that improves result quality. After the initial vector similarity search retrieves a larger pool of candidate chunks, a cross-encoder model re-scores each candidate by jointly encoding the query and chunk text together. The final result set is the top `topK` chunks ordered by the cross-encoder's relevance score.

Reranking is enabled by default using `cross-encoder/ms-marco-MiniLM-L-6-v2`. The model is downloaded automatically on first use and cached in `~/.ez-rag/models/` — no manual setup required.

To disable reranking, set the rerank model to an empty string:

```sh
# via CLI flag
ez-rag search --rerank-model "" "What is X?"

# via environment variable
export RERANK_MODEL=
ez-rag search "What is X?"

# via config file (~/.ez-rag/config.yml)
rerank-model: ""
```

To use a different model, override the default:

```sh
ez-rag search --rerank-model my-custom/cross-encoder "What is X?"
```

### How it works

When reranking is active, the pipeline fetches `rerankCandidates` chunks from the vector store (default: `topK * 3`) instead of just `topK`. The reranker then scores all candidates and the top `topK` are returned. The `score` field in results reflects the cross-encoder relevance score, not the original embedding similarity score.

```sh
# Fetch 15 candidates, rerank, return top 5 (reranking is on by default; topK * 3 = 15)
ez-rag search "What is X?"

# Override the candidate pool explicitly
ez-rag search --rerank-candidates 20 "What is X?"
```

### Reranking flags and config keys

| Source          | Key / Flag               | Description                                                             |
|-----------------|--------------------------|-------------------------------------------------------------------------|
| CLI             | `--rerank-model`         | Cross-encoder model name; set to `""` to disable reranking              |
| CLI             | `--rerank-candidates N`  | Candidate pool size before reranking                                    |
| Environment     | `RERANK_MODEL`           | Same as `--rerank-model`                                                |
| Environment     | `RERANK_CANDIDATES`      | Same as `--rerank-candidates`                                           |
| Config file     | `rerank-model`           | Same as `--rerank-model`                                                |
| Config file     | `rerank-candidates`      | Same as `--rerank-candidates`                                           |

### Recommended model

`cross-encoder/ms-marco-MiniLM-L-6-v2` is a 6-layer MiniLM model fine-tuned on MS MARCO passage ranking. It runs entirely on CPU (no GPU required), is fast enough for interactive use (sub-second for up to 25 candidates), and requires no API key.

### Verbose output

Pass `--verbose` to see reranking diagnostics on stderr:

```
Reranker: cross-encoder/ms-marco-MiniLM-L-6-v2
Reranking: 15 candidates → top 5
```

## Output format

By default, responses are human-readable with source citations. Pass `--output json` for machine-readable output suitable for agentic pipelines.

## MCP Server

`ez-rag mcp-server` starts a long-running MCP server that communicates over stdio using the MCP protocol. Claude Code and any other MCP-compatible agentic tool can call `ingest`, `query`, `search`, and `status` as structured tools without parsing CLI text output.

> **Note:** You must ingest documents with the `ingest` tool (or the `ez-rag ingest` CLI command) before calling `status`, `search`, or `query`. The server loads the vector store from disk at startup.

### Registering ez-rag in Claude Code

Add the following to `.claude/mcp.json` in your project (create the file if it does not exist):

```json
{
  "mcpServers": {
    "ez-rag": {
      "command": "ez-rag",
      "args": ["mcp-server"]
    }
  }
}
```

To use an LLM provider or a non-default store location:

```json
{
  "mcpServers": {
    "ez-rag": {
      "command": "ez-rag",
      "args": ["mcp-server", "--provider", "openai", "--store", ".ez-rag/vector-store.json"]
    }
  }
}
```

### MCP server flags

| Flag                   | Default                          | Description                                            |
|------------------------|----------------------------------|--------------------------------------------------------|
| `--provider`           | `passthrough`                    | Chat provider used by the `query` tool                 |
| `--embedding-provider` | `onnx`                           | Embedding provider used by all tools                   |
| `--store`              | `.ez-rag/vector-store.json`      | Path to the vector store JSON file                     |
| `--verbose` / `-v`     | off                              | Enable debug logging to stderr (does not affect stdout)|

### Available MCP tools

#### `status`

Returns metadata about the vector store.

No input parameters.

| Return field | Type             | Description                             |
|--------------|------------------|-----------------------------------------|
| `storePath`  | String           | Absolute path of the vector store file  |
| `chunkCount` | Int              | Total number of stored chunks           |
| `documents`  | List of objects  | Each entry has `path` (String) and `chunkCount` (Int) |
| `error`      | String or null   | Set when an error occurred              |

#### `search`

Searches the vector store using embeddings and returns matching chunks. No LLM is involved.

| Parameter  | Type   | Required | Default | Description                                    |
|------------|--------|----------|---------|------------------------------------------------|
| `question` | String | yes      | —       | The search question or query text              |
| `topK`     | Int    | no       | `5`     | Maximum number of chunks to return             |
| `minScore` | Double | no       | `0.0`   | Minimum similarity score threshold (0.0–1.0)   |

| Return field | Type            | Description                                 |
|--------------|-----------------|---------------------------------------------|
| `chunks`     | List of objects | Each entry has `filePath`, `chunkIndex`, `score`, `content` |
| `error`      | String or null  | Set when an error occurred                  |

#### `query`

Retrieves relevant chunks and returns an answer. With the default `passthrough` provider the context chunks are returned directly; with a real provider an LLM generates the answer.

| Parameter  | Type   | Required | Default          | Description                          |
|------------|--------|----------|------------------|--------------------------------------|
| `question` | String | yes      | —                | The question to ask                  |
| `topK`     | Int    | no       | `5`              | Maximum number of chunks to retrieve |
| `provider` | String | no       | server default   | Override the chat provider           |
| `model`    | String | no       | provider default | Override the chat model              |

| Return field | Type            | Description                                                              |
|--------------|-----------------|--------------------------------------------------------------------------|
| `answer`     | String          | Generated answer, or context chunks if using the `passthrough` provider  |
| `sources`    | List of objects | Each entry has `filePath`, `chunkIndex`, `similarityScore`, `excerpt`    |
| `error`      | String or null  | Set when an error occurred                                               |

#### `delete`

Removes an ingested document from the vector store by file path. The store is saved to disk after each successful call.

| Parameter  | Type   | Required | Description                                    |
|------------|--------|----------|------------------------------------------------|
| `filePath` | String | yes      | Path to the file to remove from the store      |

| Return field    | Type           | Description                                    |
|-----------------|----------------|------------------------------------------------|
| `filePath`      | String         | Absolute path that was matched against         |
| `chunksRemoved` | Int            | Number of chunks removed (0 if not found)      |
| `error`         | String or null | Set when an error occurred                     |

#### `show`

Returns per-chunk metadata for an ingested file. Optionally includes raw chunk text.

| Parameter       | Type    | Required | Description                                           |
|-----------------|---------|----------|-------------------------------------------------------|
| `filePath`      | String  | yes      | Path to the file to inspect                           |
| `includeChunks` | Boolean | no       | If true, include raw chunk text in each chunk object  |

| Return field | Type            | Description                                                                 |
|--------------|-----------------|-----------------------------------------------------------------------------|
| `file`       | String          | Absolute path that was matched against                                      |
| `chunks`     | List of objects | Each entry has `chunkIndex`, `charCount`, `mtime`, and optionally `text`    |
| `error`      | String or null  | Set when an error occurred or the file was not found                        |

#### `ingest`

Ingests documents from a file or directory into the vector store. The store is saved to disk after each successful call.

| Parameter      | Type   | Required | Default | Description                                  |
|----------------|--------|----------|---------|----------------------------------------------|
| `path`         | String | yes      | —       | Path to a file or directory to ingest        |
| `chunkSize`    | Int    | no       | `1000`  | Chunk size in characters                     |
| `chunkOverlap` | Int    | no       | `200`   | Overlap between consecutive chunks           |

| Return field    | Type           | Description                              |
|-----------------|----------------|------------------------------------------|
| `filesIngested` | Int            | Number of files successfully ingested    |
| `chunksCreated` | Int            | Number of chunks added to the store      |
| `skipped`       | Int            | Files skipped because already up to date |
| `error`         | String or null | Set when an error occurred               |

## Developer guide

### Technology stack

- Kotlin 2.0, JDK 21
- Spring Boot 3.4 (no web server; `web-application-type: none`)
- Spring AI 1.0 (OpenAI, Anthropic, Ollama, Transformers starters)
- picocli 4.7 for argument parsing
- Gradle with Kotlin DSL

### Build

```sh
./gradlew build          # compile, test, assemble fat JAR
./gradlew test           # run tests only
./gradlew installDist    # produce runnable distribution under build/install/
```

The Kotlin compiler is configured with `-Werror`, so warnings are treated as build failures.

### Testing

The project uses test-driven development. Unit tests live in `src/test/kotlin/` alongside the source tree. Run them with:

```sh
./gradlew test
```

### Provider selection design

Spring AI's auto-configuration is fully disabled in `application.yml`. Provider beans are constructed manually in `ProviderConfiguration` based on the resolved config at startup. This avoids the need for Spring profiles and allows runtime provider selection from CLI flags without recompilation. To add a new provider, add a branch to `ProviderConfiguration.chatModel()` or `ProviderConfiguration.embeddingModel()`.

### Adding a new subcommand

1. Create a class in `command/` annotated with `@Command` and `@Component` that implements `Callable<Int>`.
2. Register it in the `subcommands` list of `@Command` on `EzRagCommand`.
3. Inject `ChatModel`, `EmbeddingModel`, or `ConfigService` as constructor parameters.
