# ez-rag

ez-rag is a command-line tool optimized for agentic coding workflows that lets you ingest local documents into a searchable knowledge base and query them using any supported LLM provider. RAG (retrieval-augmented generation) means the tool first retrieves the most relevant text chunks from your documents, then optionally passes those chunks to an LLM to generate a grounded answer.

## Table of contents

- [Introduction](#introduction)
  - [Requirements](#requirements)
  - [Build and install](#build-and-install)
  - [Quick start](#quick-start)
  - [Keeping the store up to date](#keeping-the-store-up-to-date)
- [Basic](#basic)
  - [Commands](#commands)
  - [Hybrid search](#hybrid-search)
  - [Search-specific flags](#search-specific-flags)
  - [Global flags](#global-flags)
- [Configuration](#configuration)
  - [Providers](#providers)
  - [API keys](#api-keys)
  - [Configuration file](#configuration-file)
  - [Store](#store)
  - [RAG settings](#rag-settings)
- [Agentic Coding Tools](#agentic-coding-tools)
  - [Using ez-rag with agentic coding tools](#using-ez-rag-with-agentic-coding-tools)
- [Advanced](#advanced)
  - [Reranking](#reranking)
  - [Output format](#output-format)
  - [MCP Server](#mcp-server)
  - [eval](#eval)
- [Contributing](#contributing)

## Introduction

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

### Faster startup with AppCDS

Generate an Application Class Data Sharing archive once after installing to reduce startup time by ~300ms:

```sh
./gradlew generateCdsArchive
```

This creates `build/install/ez-rag/lib/ez-rag.jsa` which the start scripts use automatically. Re-run after any dependency or code change.

## Quick start

No configuration needed — works out of the box:

```sh
# Ingest a single file (downloads embedding model on first run)
# Supported file types: .txt, .pdf, .md, .html / .htm, .csv, .rtf, .docx, .doc, .xlsx, .xls, .pptx, .ppt, .xml, .svg, .rss, .atom, .xhtml
ez-rag ingest README.md

# Ingest a webpage
ez-rag ingest https://en.wikipedia.org/wiki/Retrieval-augmented_generation

# search returns raw chunks (no LLM required)
ez-rag search "What is retrieval-augmented generation?"

# query passes those chunks to an LLM and returns a generated answer
ez-rag query "What is retrieval-augmented generation?"

# Multi-word questions can be passed without quotes
ez-rag query What is retrieval-augmented generation?
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

# Fully local LLM (no API key, downloads ~1 GB on first run)
ez-rag query --provider onnx "What are the main features?"
```

```sh
# Pipe a question from stdin
echo "What is X?" | ez-rag search
```

## Keeping the store up to date

After editing or adding documents, use `list` to see which files are stale and `reingest` to refresh them:

```sh
# Show all ingested documents; files modified since ingest are flagged [STALE]
ez-rag list

# Re-ingest only the stale documents
ez-rag reingest

# Or force re-ingest of every document in the store
ez-rag reingest --all
```

A document is marked `[STALE]` when its filesystem mtime has changed since the last ingest. `reingest` updates only the stale entries by default, so unchanged documents are never re-processed unnecessarily.

## Basic

## Commands

| Command                        | Description                                                                                        |
|--------------------------------|----------------------------------------------------------------------------------------------------|
| `init`                         | Initialize a `.ez-rag/` workspace in the current directory and add the store to `.gitignore`.      |
| `install-skill`                | Install the ez-rag skill for your AI coding tool. Auto-detects Claude Code, OpenCode, or falls back to generic. |
| `ingest <file\|dir>`           | Ingest files or directories (recursive) into the vector store. Supports `.txt`, `.pdf`, `.md`, `.html`/`.htm`, `.csv`, `.rtf`, `.docx`, `.doc`, `.xlsx`, `.xls`, `.pptx`, `.ppt`, `.xml`, `.svg`, `.rss`, `.atom`, `.xhtml`. Prints each file as it is ingested. |
| `delete <file> [<file>...]`    | Remove one or more ingested documents from the vector store without touching other content.        |
| `list`                         | List all ingested documents with chunk counts and staleness flags. Use `--output-format json` for machine-readable output with absolute paths. |
| `reingest`                     | Re-ingest all stale documents (mtime changed since last ingest). Use `--all` to force re-ingest of every document. |
| `show <file>`                  | Show per-chunk metadata (and optionally raw text) for an ingested file. Useful for debugging retrieval. |
| `chunk <file> <index>`         | Retrieve a specific chunk by file path and chunk index. Use `--window N` to also fetch the N surrounding chunks. |
| `status`                       | Show store health, aggregate counts, active configuration, and credential status.                  |
| `search [<word>...]`           | Search returning raw chunks without LLM involvement. Defaults to hybrid (BM25 + embedding) mode; override with `--mode`. Reads question from positional args or stdin. |
| `query [<word>...]`            | Retrieve relevant chunks and answer using an LLM. Reads question from positional args or stdin.    |
| `shell`                        | Interactive REPL mode with multi-turn conversation history and slash commands.                     |
| `to-markdown <input>`          | Convert a local PDF, local HTML/HTM file, or an HTTP/HTTPS URL to Markdown and print it to stdout. |

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

`init` also adds `.ez-rag/` to the project's `.gitignore` (if the file exists) so the entire store directory (vector store and Lucene index) is never accidentally committed. Running `ingest` without calling `init` first works too — the directory is created automatically.

To initialise and install the skill in one step:

```sh
ez-rag init --install-skill
```

### install-skill

Install the ez-rag skill for your AI coding tool:

```sh
ez-rag install-skill
```

The command auto-detects which tools are present and installs to the correct path:

| Tool | Detection signal | Project-level path | Global path (`--global`) |
|---|---|---|---|
| Claude Code | `.claude/` in project or home | `.claude/skills/ez-rag/SKILL.md` | `~/.claude/skills/ez-rag/SKILL.md` |
| OpenCode | `.opencode/` in project or `~/.config/opencode/` | `.agents/skills/ez-rag/SKILL.md` | `~/.config/opencode/skills/ez-rag/SKILL.md` |
| Generic | fallback when no known tool detected | `.agents/skills/ez-rag/SKILL.md` | `~/.agents/skills/ez-rag/SKILL.md` |

Re-running after upgrading ez-rag overwrites the previous file with the latest skill content.

**Flags:**

| Flag | Description |
|---|---|
| `--global` | Install into the user's home-level skill directory instead of the project directory. |
| `--tool <name>` | Install for a specific tool, bypassing auto-detection. Repeatable. Valid values: `claude-code`, `opencode`, `generic`. |

**Examples:**

```sh
# Auto-detect and install project-level
ez-rag install-skill

# Install globally for Claude Code
ez-rag install-skill --global --tool claude-code

# Install for both Claude Code and OpenCode at once
ez-rag install-skill --tool claude-code --tool opencode
```

### ingest

Ingest files or directories into the vector store. Supported file types: `.txt`, `.pdf`, `.md`, `.html`/`.htm`, `.csv`, `.rtf`, `.docx`, `.doc`, `.xlsx`, `.xls`, `.pptx`, `.ppt`, `.xml`, `.svg`, `.rss`, `.atom`, `.xhtml`.

Binary text stripping is opt-in. By default, binary files and binary URL responses are skipped. To enable stripping for specific extensions, pass `--binary-strip-extensions`:

```sh
ez-rag ingest --binary-strip-extensions bin exe dll ./firmware
```

When an extension is listed, the tool extracts all contiguous runs of at least 4 printable ASCII characters from the binary content and indexes them as plain text. A warning is printed when binary text stripping is applied. Files or URLs that yield no extractable text produce zero chunks and are counted as skipped. Files whose extension is not in the list are skipped without stripping.

```sh
ez-rag ingest ./docs
```

To ingest a password-protected Office file:

```sh
# Single password
ez-rag ingest --password mypassword report.docx

# Multiple passwords (tried in order until one succeeds — useful for directories with mixed passwords)
ez-rag ingest --password password1 --password password2 ./office-docs
```

If a file is encrypted and no matching password is supplied, it is skipped with a warning and ingestion of other files in the batch continues normally.

## Ingest-specific flags

| Flag             | Description                                                                                   |
|------------------|-----------------------------------------------------------------------------------------------|
| `--quiet` / `-q` | Suppress per-file output; print only the final summary line.                                  |
| `--details`      | Print chunk details (token count and text preview) for each ingested file.                    |
| `--password`     | Password for encrypted Office files. Repeat the flag for multiple passwords: `--password p1 --password p2`. Each password is tried in order; the first that succeeds is used. |
| `--binary-strip-extensions` | File extensions to enable binary text-stripping for. Repeat for multiple: `--binary-strip-extensions bin --binary-strip-extensions exe`. Default: no binary stripping. |

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

Documents marked `[STALE]` have been modified on disk since they were last ingested, or their source file no longer exists. URL-based sources that were ingested within the freshness window (default 24 hours) appear without a `[STALE]` marker. Re-ingest them to bring the store up to date.

Documents are listed in alphabetical order by path.

Pass `--output-format json` for machine-readable output with absolute paths:

```sh
ez-rag list --output-format json
```

```json
[
  { "path": "/abs/path/to/docs/getting-started.md", "chunks": 3, "status": "FRESH" },
  { "path": "/abs/path/to/docs/old-guide.md", "chunks": 1, "status": "STALE" }
]
```

If no store exists, `list` prints an error to stderr and exits with code `1`.

| Flag                | Description                                             |
|---------------------|---------------------------------------------------------|
| `--output-format`   | Output format: `text` (default) or `json`.              |
| `--url-freshness-hours` | Freshness window in hours for URL sources (default: 24). URLs ingested within this window appear as FRESH; those outside it appear as STALE. |

### reingest

Re-ingest all stale documents — those whose filesystem mtime differs from the mtime stored at ingest time:

```sh
ez-rag reingest
```

Output:
```
Stale documents: 2
Re-ingesting: docs/getting-started.md
Re-ingesting: docs/configuration.md
2 files re-ingested, 18 chunks created, 0 skipped
```

Use `--all` to force re-ingest of every document in the store regardless of staleness:

```sh
ez-rag reingest --all
```

Output (the `Stale documents:` line is omitted in `--all` mode):
```
Re-ingesting: docs/getting-started.md
Re-ingesting: docs/configuration.md
Re-ingesting: docs/api-reference.md
3 files re-ingested, 31 chunks created, 0 skipped
```

If a source file no longer exists on disk, a warning is printed on stderr and the file is skipped (the store entry is left untouched — use `delete` to remove it explicitly):

```
WARN: source file not found, skipping: /absolute/path/to/missing.md
```

Pass `--quiet` to suppress per-file `Re-ingesting:` lines and show only the summary:

```sh
ez-rag reingest --quiet
```

| Flag                 | Default | Description                                                    |
|----------------------|---------|----------------------------------------------------------------|
| `--all`              | off     | Re-ingest every document, not just stale ones                  |
| `--quiet` / `-q`     | off     | Suppress per-file output; print only the final summary line    |
| `--chunk-size N`     | `1000`  | Token count per chunk                                          |
| `--chunk-overlap N`  | `200`   | Overlap between consecutive chunks                             |
| `--store-dir <path>` | auto    | Target a specific store directory instead of the auto-resolved one |
| `--url-freshness-hours` | `24` | Freshness window in hours for URL sources. FRESH URLs are skipped by `reingest` (use `--all` to override). |

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

Pass `--output-format json` for machine-readable output suitable for scripting with `jq`:

```sh
ez-rag show --output-format json docs/getting-started.md
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

Combine `--output-format json --chunks` to include the raw `text` field in each chunk object.

If the file was never ingested, `show` exits with a non-zero code and prints an error to stderr.

| Flag              | Description                                               |
|-------------------|-----------------------------------------------------------|
| `--chunks`        | Include raw chunk text in output.                         |
| `--output-format` | Output format: `text` (default) or `json`.                |

### chunk

Retrieve a specific chunk by its file path and chunk index. Chunk indices are the `chunkIndex` values returned by `search` and `show`.

```sh
ez-rag chunk docs/getting-started.md 2
```

Output:
```
Chunk 2
  This is the text of the third chunk in the file.
```

Use `--window N` to also fetch the N chunks before and after the target. The window silently clamps at file boundaries, so `--window 1` on chunk 0 returns only chunks 0 and 1.

```sh
ez-rag chunk docs/getting-started.md 2 --window 1
```

Pass `--output-format json` for machine-readable output:

```sh
ez-rag chunk docs/getting-started.md 2 --output-format json
```

JSON output:
```json
{
  "file": "/absolute/path/to/docs/getting-started.md",
  "chunks": [
    { "chunkIndex": 2, "text": "This is the text of the third chunk in the file." }
  ]
}
```

When the chunk carries heading metadata (e.g. from a Markdown file), the JSON output includes `headingTitle`, `headingLevel`, and `headingPath` fields. These fields are omitted entirely when no heading metadata is present.

If the file was never ingested, or if the exact chunk index is not found in the store, the command exits with code 1 and prints an error to stderr.

| Flag              | Description                                                             |
|-------------------|-------------------------------------------------------------------------|
| `--window N`      | Also retrieve the N chunks before and after the target (default `0`).   |
| `--output-format` | Output format: `text` (default) or `json`.                              |
| `--store-dir`     | Path to the store directory (same precedence as `search` and `show`).   |

### status

Show store health, aggregate counts, and active configuration:

```sh
ez-rag status
```

Output:
```
Store: /path/to/.ez-rag/lucene
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

### search

Search returning raw chunks without LLM involvement. `search` returns the most relevant raw chunks from your documents — no LLM is called, no answer is generated.

```sh
ez-rag search "What is the chunk size?"
```

### query

Retrieve relevant chunks and pass them to an LLM to generate an answer. Unlike `search`, which returns raw chunks, `query` synthesizes a natural-language answer from the retrieved context.

```sh
ez-rag query "What is the chunk size?"
```

### shell

Start an interactive REPL session for multi-turn question answering:

```sh
ez-rag shell
```

Each prompt is answered using RAG, and the conversation history is accumulated across turns so the LLM sees prior exchanges as context:

```
> What is the chunk size?
The default chunk size is 512 tokens.

> And the overlap?
The default overlap is 50 tokens.

> /clear
conversation history cleared

> What is the chunk size?
...
```

**Slash commands available inside the shell:**

| Command           | Description                                                              |
|-------------------|--------------------------------------------------------------------------|
| `/clear`          | Reset the conversation history and start a fresh session.                |
| `/search <query>` | Run a hybrid search and print raw chunks without LLM involvement.        |
| `/search-bm25 <query>` | Run a BM25-only keyword search and print raw chunks.               |
| `/search-embedding <query>` | Run a vector-only semantic search and print raw chunks.       |
| `/help`           | Print available slash commands.                                          |
| `/exit`           | Exit the REPL.                                                           |

Slash-command turns are not added to the conversation history. Only successful RAG query turns are accumulated. Turns that produce an error are also not added, so the history always contains only complete, successful exchanges.

### to-markdown

Convert a local PDF file, a local HTML/HTM file, or an HTTP/HTTPS URL to Markdown and print it to stdout. This is useful for inspecting how a document will be represented before ingesting it.

**Supported inputs:**

- Local PDF file (`.pdf`)
- Local HTML file (`.html` or `.htm`)
- HTTP or HTTPS URL — the content type returned by the server determines the conversion pipeline (`text/html` → HTML converter, `application/pdf` → PDF converter)

**Examples:**

```sh
# Convert a local PDF file
ez-rag to-markdown report.pdf

# Convert a local HTML file
ez-rag to-markdown page.html

# Convert a web page via HTTP
ez-rag to-markdown https://example.com/docs/overview

# Convert a PDF hosted over HTTPS
ez-rag to-markdown https://example.com/report.pdf
```

The Markdown output for HTML inputs mirrors exactly what the ingestion pipeline produces when it ingests the same file, so you can preview the chunking-ready text before running `ingest`.

**Options (PDF inputs only):**

The following options apply only when the input is a PDF file or a URL that returns `application/pdf`. Passing any of them with an HTML input or a URL that returns HTML causes the command to exit 1 with an error.

| Flag               | Default    | Description                                                             |
|--------------------|------------|-------------------------------------------------------------------------|
| `--mode`           | `readable` | Conversion mode: `readable` (preserves formatting) or `rag` (strips bold/italic noise for cleaner chunk text) |
| `--max-pages N`    | unlimited  | Stop after converting the first N pages                                 |
| `--output-format`  | `markdown` | Output format: `markdown` or `xml`                                      |

```sh
# RAG-optimised mode (strips bold/italic noise)
ez-rag to-markdown report.pdf --mode rag

# Convert only the first 5 pages
ez-rag to-markdown report.pdf --max-pages 5

# XML output format
ez-rag to-markdown report.pdf --output-format xml
```

## Hybrid search

By default, `search` uses **hybrid mode**: it runs both a BM25 keyword search (powered by Lucene) and a vector similarity search in parallel, then fuses the two result lists using Reciprocal Rank Fusion (RRF). This combines the precision of keyword matching with the semantic coverage of embeddings.

```sh
# Default — hybrid (BM25 + embedding, RRF fusion)
ez-rag search "What is the chunk size?"

# Keyword-only BM25 search (no embedding model involved)
ez-rag search --mode bm25 "chunk size"

# Embedding-only vector similarity search
ez-rag search --mode embedding "chunk size"
```

The JSON output includes a `"mode"` field showing which pipeline produced the results:

```json
{
  "mode": "hybrid",
  "chunks": [...]
}
```

Both BM25 and embedding vectors are stored together in `<storeDir>/lucene/` — a single Lucene index that holds HNSW vector fields alongside BM25 text fields. It is populated automatically during `ingest` — no extra steps needed.

### Choosing an analyzer

The BM25 index uses a Lucene analyzer to tokenize text. The default is `standard` (Unicode-aware word splitting). Use `english` for English-language content to enable stemming (e.g. "running" matches "run"):

```sh
ez-rag --analyzer english search "running processes"
```

Or set it persistently in `~/.ez-rag/config.yml`:

```yaml
analyzer: english
```

## Search-specific flags

| Flag              | Default    | Description                                                          |
|-------------------|------------|----------------------------------------------------------------------|
| `--mode`          | `hybrid`   | Search mode: `hybrid` (BM25 + embedding), `bm25`, or `embedding`    |
| `--top-k N`       | `5`        | Maximum number of chunks to return                                   |
| `--min-score T`   | `0.0`      | Minimum similarity score; lower-scoring chunks are filtered out      |
| `--output-format` | `text`     | Output format: `text`, `json`, or `xml`                              |

## Global flags

These flags apply to all subcommands:

| Flag                      | Default                  | Description                                    |
|---------------------------|--------------------------|------------------------------------------------|
| `--provider`              | `passthrough`            | Chat provider: `passthrough`, `onnx`, `openai`, `anthropic`, `ollama` |
| `--embedding-provider`    | `onnx`                   | Embedding provider: `onnx`, `openai`, `ollama` |
| `--model`                 | provider default         | Override the chat model name                   |
| `--embedding-model`       | provider default         | Override the embedding model name              |
| `--rerank-model`          | `cross-encoder/ms-marco-MiniLM-L-6-v2` | Cross-encoder reranker model name. Set to `""` to disable reranking. |
| `--rerank-candidates`     | `topK * 3`               | Number of candidates fetched before reranking. Only relevant when `--rerank-model` is set. |
| `--ollama-url`            | `http://localhost:11434` | Ollama base URL                                |
| `--search-mode`           | `hybrid`                 | Search mode: `hybrid` (BM25 + embedding), `bm25`, or `embedding` |
| `--analyzer`              | `standard`               | Lucene analyzer for BM25: `standard` or `english` (enables stemming) |
| `--verbose` / `-v`        | off                      | Enable debug logging; for `query`, also prints each source file path, similarity score, and chunk index to stderr. When reranking is active also prints reranker name and candidate pool size. |
| `--stack-trace`           | off                      | Print the full Java stack trace when an error occurs. Useful for diagnosing unexpected failures. |
| `--lock-timeout`          | `30`                     | Seconds to retry acquiring the write lock before failing with an error (0 = fail immediately). Useful when two processes target the same store simultaneously — the second process waits up to this many seconds for the first to release the lock. |

## Configuration

## Providers

### Chat providers

| Provider      | Default model                    | API key (env var or credentials file) | Notes                                               |
|---------------|----------------------------------|---------------------------------------|-----------------------------------------------------|
| `passthrough` | —                                | —                                     | **Default.** Returns retrieved context chunks directly; no LLM call, no API key required. |
| `onnx`        | `Xenova/TinyLlama-1.1B-Chat-v1.0` | —                                     | Fully local LLM; model downloaded automatically on first use to `~/.ez-rag/models/`. No API key required. |
| `openai`      | `gpt-4o-mini`                    | `OPENAI_API_KEY`                      |                                                     |
| `anthropic`   | `claude-sonnet-4-6`              | `ANTHROPIC_API_KEY`                   |                                                     |
| `ollama`      | `llama3.2`                       | `OLLAMA_BASE_URL` (optional)          | Requires a running Ollama instance                  |

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
#store-dir: .ez-rag
#chunk-size: 1000
#chunk-overlap: 200
#top-k: 5
#system-prompt: ""
#output-format: text
#verbose: false
#rerank-model: cross-encoder/ms-marco-MiniLM-L-6-v2   # default: cross-encoder/ms-marco-MiniLM-L-6-v2 (reranking enabled by default)
#rerank-candidates: 15   # default: topK * 3 when rerank-model is set
#search-mode: hybrid      # default: hybrid (options: hybrid, bm25, embedding)
#analyzer: standard       # default: standard (options: standard, english, …)
```

All keys support both camelCase (`chunkSize`) and kebab-case (`chunk-size`) spelling.

## Store

The default store directory is `.ez-rag/` relative to the current working directory, following the same convention as `.git/` and `.claude/`. Override it with `--store-dir <path>` or the `store-dir` config key.

Inside the store directory:

| Path | Contents |
|------|----------|
| `lucene/` | Unified Lucene index: HNSW embedding vectors (COSINE similarity) and BM25 keyword index in a single on-disk store |

The store is populated automatically by `ingest` and kept in sync by `delete`. The entire `.ez-rag/` directory should be excluded from version control — `ez-rag init` adds it to `.gitignore` automatically.

## RAG settings

| Setting         | Default               | Description                          |
|-----------------|-----------------------|--------------------------------------|
| `chunk-size`    | 1000                  | Token count per chunk                |
| `chunk-overlap` | 200                   | Overlap between consecutive chunks   |
| `top-k`         | 5                     | Number of chunks retrieved per query |
| `system-prompt` | built-in RAG template | Override the LLM system prompt       |

## Agentic Coding Tools

## Using ez-rag with agentic coding tools

One of the primary use cases for ez-rag is giving agentic coding tools a local document knowledge base. Index your docs once; the agent answers questions from them without you having to paste file contents into the chat.

Any agent that can run shell commands can use ez-rag directly. For agents with a skill/instruction system — such as Claude Code — drop the skill file from this repository into the agent's skill directory and it will follow the full retrieve-and-synthesize workflow automatically.

### Installing the skill

Run `install-skill` from your project directory after `init`:

```sh
ez-rag install-skill
```

The command auto-detects which agentic coding tools are present (Claude Code, OpenCode, or a generic fallback) and installs the skill to the correct location for each. You can also do it in one step:

```sh
ez-rag init --install-skill
```

Or force a specific tool:

```sh
ez-rag install-skill --tool claude-code
```

Install globally (available in every project):

```sh
ez-rag install-skill --global
```

Once installed, trigger it with natural-language requests:

- "Ingest the `docs/` folder"
- "What does the architecture doc say about connection pooling?"
- "Search the knowledge base for retry configuration"
- "What do the docs say about authentication?"

### How the workflow works

When asked a document question, the agent runs:

1. **Check the store** — `ez-rag list` shows which files are indexed and flags any that have changed on disk since ingest (`[STALE]`).
2. **Ingest or refresh** — `ez-rag ingest <path>` for new files; `ez-rag reingest` to refresh stale ones.
3. **Search with multiple phrasings** — `ez-rag search <question>` returns the most relevant chunks using hybrid BM25 + embedding search. Running 2–3 different phrasings often surfaces different chunks, especially when scores are flat.
4. **Load surrounding context** — `ez-rag chunk <file> <chunkIndex> --window 1` retrieves adjacent chunks when a result is too short or references content that continues in a neighbouring chunk.
5. **Synthesize** — the agent reads the chunks and answers in its own words, citing which file each piece of information came from.

No API key is required: search uses a local embedding model by default and involves no LLM call.

## Advanced

### Reranking

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

#### How it works

When reranking is active, the pipeline fetches `rerankCandidates` chunks from the vector store (default: `topK * 3`) instead of just `topK`. The reranker then scores all candidates and the top `topK` are returned. The `score` field in results reflects the cross-encoder relevance score, not the original embedding similarity score.

```sh
# Fetch 15 candidates, rerank, return top 5 (reranking is on by default; topK * 3 = 15)
ez-rag search "What is X?"

# Override the candidate pool explicitly
ez-rag search --rerank-candidates 20 "What is X?"
```

#### Reranking flags and config keys

| Source          | Key / Flag               | Description                                                             |
|-----------------|--------------------------|-------------------------------------------------------------------------|
| CLI             | `--rerank-model`         | Cross-encoder model name; set to `""` to disable reranking              |
| CLI             | `--rerank-candidates N`  | Candidate pool size before reranking                                    |
| Environment     | `RERANK_MODEL`           | Same as `--rerank-model`                                                |
| Environment     | `RERANK_CANDIDATES`      | Same as `--rerank-candidates`                                           |
| Config file     | `rerank-model`           | Same as `--rerank-model`                                                |
| Config file     | `rerank-candidates`      | Same as `--rerank-candidates`                                           |

#### Recommended model

`cross-encoder/ms-marco-MiniLM-L-6-v2` is a 6-layer MiniLM model fine-tuned on MS MARCO passage ranking. It runs entirely on CPU (no GPU required), is fast enough for interactive use (sub-second for up to 25 candidates), and requires no API key.

#### Verbose output

Pass `--verbose` to see reranking diagnostics on stderr:

```
Reranker: cross-encoder/ms-marco-MiniLM-L-6-v2
Reranking: 15 candidates → top 5
```

### Output format

#### search output formats

`search` supports three output formats via `--output-format`:

**`text` (default)** — human-readable, one block per chunk:

```
[1] score=0.98  source=/abs/path/to/file.md  chunk=10
raw chunk content here

[2] score=0.74  source=https://example.com/page  chunk=3
raw chunk content here
```

**`json`** — machine-readable JSON with a top-level `mode` field and a `chunks` array:

```json
{
  "mode": "hybrid",
  "chunks": [
    {"path": "/abs/path/to/file.md", "chunkIndex": 10, "score": 0.9799999, "content": "raw chunk content here"},
    {"path": "https://example.com/page", "chunkIndex": 3, "score": 0.74, "content": "raw chunk content here"}
  ]
}
```

**`xml`** — XML-delimited format optimised for LLM consumption. Tags act as structural delimiters; chunk content is placed verbatim between them with no XML escaping. Suitable for piping `search` output directly into a Claude Code prompt or another agent:

```xml
<results mode="hybrid">
<result index="1" score="0.98" source="/abs/path/to/file.md" chunk="10">
raw chunk content here
</result>
<result index="2" score="0.74" source="https://example.com/page" chunk="3">
raw chunk content here
</result>
</results>
```

When no chunks are found, the XML output is `<results mode="..."></results>`.

The `source` attribute in XML (and the `path` key in JSON) holds the file path or URL exactly as it was passed to `ez-rag ingest`.

```sh
# Pipe XML output into another tool
ez-rag search --output-format xml "connection timeout configuration"

# JSON output for scripting with jq
ez-rag search --output-format json "retry policy" | jq '.chunks[].path'
```

### MCP Server

`ez-rag mcp-server` starts a long-running MCP server that communicates over stdio using the MCP protocol. Claude Code and any other MCP-compatible agentic tool can call `search`, `chunk`, `ingest`, `reingest`, and `list` as structured tools without parsing CLI text output.

> **Note:** You must ingest documents with the `ingest` tool (or the `ez-rag ingest` CLI command) before calling `search`. The server loads the vector store from disk at startup.

#### Registering ez-rag in Claude Code

Add the following to `.mcp.json` in your project root (create the file if it does not exist):

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
      "args": ["mcp-server", "--provider", "openai", "--store-dir", ".ez-rag"]
    }
  }
}
```

#### MCP server flags

| Flag                   | Default                          | Description                                            |
|------------------------|----------------------------------|--------------------------------------------------------|
| `--transport`          | `stdio`                          | Transport mode: `stdio` (default) or `http`            |
| `--port`               | `8080`                           | HTTP listening port (HTTP transport only)              |
| `--embedding-provider` | `onnx`                           | Embedding provider used by all tools                   |
| `--store-dir`          | `.ez-rag`                        | Path to the store directory                            |
| `--url-freshness-hours`| `24`                             | Freshness window in hours for URL sources (default: 24)|
| `--verbose` / `-v`     | off                              | Enable debug logging to stderr (does not affect stdout)|

#### HTTP transport mode

Use `--transport http` to start a persistent, network-accessible MCP server instead of the default stdio mode. On startup, the server prints the SSE endpoint URL and keeps running until killed:

```sh
ez-rag mcp-server --transport http
# MCP server listening on http://localhost:8080/sse
```

Use `--port` to change the listening port:

```sh
ez-rag mcp-server --transport http --port 9090
# MCP server listening on http://localhost:9090/sse
```

To stop the server, send it a signal (e.g. `Ctrl-C` or `kill <pid>`).

Register it in Claude Code by adding the following to `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "ez-rag": {
      "type": "see",
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Multiple MCP clients can connect to the same running HTTP server instance simultaneously.

#### Available MCP tools

##### `search`

Hybrid search (BM25 + embedding, RRF fusion). No LLM is involved. Equivalent to `--mode hybrid`.

| Parameter  | Type   | Required | Default | Description                                    |
|------------|--------|----------|---------|------------------------------------------------|
| `question` | String | yes      | —       | The search question or query text              |
| `topK`     | Int    | no       | `5`     | Maximum number of chunks to return             |
| `minScore` | Double | no       | `0.0`   | Minimum similarity score threshold (0.0–1.0)   |

| Return field | Type            | Description                                 |
|--------------|-----------------|---------------------------------------------|
| `chunks`     | List of objects | Each entry has `path`, `chunkIndex`, `score`, `text` |
| `mode`       | String          | Always `"hybrid"`                           |
| `error`      | String or null  | Set when an error occurred                  |

##### `chunk`

Retrieves one or more chunks by file path and chunk index. Use `chunkIndex` values from prior `search` results. Pass `window` to also fetch surrounding chunks for context.

| Parameter    | Type   | Required | Default | Description                                                                       |
|--------------|--------|----------|---------|-----------------------------------------------------------------------------------|
| `filePath`   | String | yes      | —       | Path to the file containing the chunk                                             |
| `chunkIndex` | Int    | yes      | —       | Index of the chunk to retrieve, as returned by search tools                       |
| `window`     | Int    | no       | `0`     | Number of chunks before and after the target to include; clamped at file boundaries |

| Return field | Type            | Description                                                                                          |
|--------------|-----------------|------------------------------------------------------------------------------------------------------|
| `file`       | String          | Absolute path that was matched against                                                               |
| `chunks`     | List of objects | Each entry has `chunkIndex`, `text`, and optionally `headingTitle`, `headingLevel`, `headingPath`    |
| `error`      | String or null  | Set when the file was not found or an error occurred; `chunks` is empty in error cases               |

##### `reingest`

Re-ingests stale documents (those whose filesystem mtime changed since the last ingest). Pass `forceAll: true` to re-ingest every document regardless of staleness. The store is saved to disk after each successful call.

| Parameter      | Type    | Required | Default | Description                                              |
|----------------|---------|----------|---------|----------------------------------------------------------|
| `forceAll`     | Boolean | no       | `false` | If true, re-ingest every document regardless of staleness |
| `chunkSize`    | Int     | no       | `1000`  | Chunk size in characters                                 |
| `chunkOverlap` | Int     | no       | `200`   | Overlap between consecutive chunks                       |

| Return field      | Type           | Description                                                               |
|-------------------|----------------|---------------------------------------------------------------------------|
| `staleFound`      | Int or null    | Number of documents identified as stale; `null` when `forceAll` is `true` |
| `filesReIngested` | Int            | Number of files successfully re-ingested                                  |
| `chunksCreated`   | Int            | Number of chunks added to the store                                       |
| `filesSkipped`    | Int            | Source files not found on disk (warned and skipped)                       |
| `error`           | String or null | Set when an error occurred                                                |

##### `ingest`

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

##### `list`

Lists all ingested documents with their chunk count and staleness status. A document is stale when its source file has been modified since last ingest.

No input parameters.

| Return field | Type            | Description                                                                         |
|--------------|-----------------|-------------------------------------------------------------------------------------|
| (array)      | List of objects | Each entry has `path` (String), `chunkCount` (Int), and `status` (String: `"FRESH"` or `"STALE"`) |

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

How to read the numbers:

- **Recall@3** ranges from 0.0 to 1.0. `0.94` means 94 % of questions had their expected chunk somewhere in the top 3 results; `1.00` means every question's answer was found. A value below 1.0 means some questions missed entirely — no amount of re-ranking can fix that.
- **MRR** also ranges from 0.0 to 1.0, but penalises answers that appear at rank 2 or 3. `1.00` would mean every answer appeared at rank 1. `0.63` means relevant chunks are typically found at rank 1–2 but not always first. The gap between Recall@3 and MRR is the key signal: `hard-negatives` has Recall@3 `1.00` but MRR `0.88`, meaning all answers are found but some land at rank 2 or 3 rather than rank 1.
- **Hit@3** equals Recall@3 in these examples because each question has a single expected source. It would diverge only if a question listed multiple expected sources (Hit@3 is binary; Recall@3 gives partial credit).

How to read the rows:

- Each scenario row covers **all questions** in that scenario directory, regardless of document role.
- The indented `hard-negative` sub-row covers only the **subset of questions whose expected source is a hard-negative document**. It has no Questions count (it is a subset, not an independent scenario) and no Status (thresholds apply to the scenario as a whole, not the subset).
- `Overall` averages the per-scenario metrics weighted equally across scenarios, not questions.
- `Status` is `PASS` when all configured thresholds are met, `FAIL (<metric> < <threshold>)` for the first failing metric, or blank when no thresholds are defined.

Exit code is `0` when all thresholds pass (or no thresholds are defined), `1` when any threshold fails. This makes `eval` suitable for use in CI pipelines.

Pass `--output-format json` for machine-readable output:

```sh
ez-rag eval --output-format json ./my-corpus
```

| Flag              | Description                               |
|-------------------|-------------------------------------------|
| `--output-format` | Output format: `text` (default) or `json`.|

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the technology stack, build instructions, testing guide, provider selection design, and notes on adding a new subcommand.
