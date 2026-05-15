# ez-rag

A command-line tool for RAG (retrieval-augmented generation). Ingest documents into a local vector store, then query them using any supported LLM provider.

## Requirements

- JDK 21 or later (the build scripts embed a JDK 21 toolchain as fallback)
- One of the following to run queries:
  - An `OPENAI_API_KEY` for OpenAI
  - An `ANTHROPIC_API_KEY` for Anthropic
  - A running [Ollama](https://ollama.ai) instance for fully local inference

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

```sh
export OPENAI_API_KEY=sk-...

# Ingest a directory of documents
ez-rag ingest ./docs

# Ask a question
ez-rag query "What is the project's license?"

# Multi-word questions can be passed without quotes
ez-rag query What is the project license?

# Use Anthropic for chat, OpenAI for embeddings
ez-rag query --provider anthropic --embedding-provider openai "Summarize the architecture."

# Fully local setup with Ollama
ez-rag ingest --embedding-provider ollama ./docs
ez-rag query --provider ollama --embedding-provider ollama "What are the main features?"

# Pipe a question from stdin
echo "What is X?" | ez-rag search
```

## Commands

| Command                        | Description                                                                                        |
|--------------------------------|----------------------------------------------------------------------------------------------------|
| `ingest <file\|dir>`           | Ingest files or directories (recursive) into the vector store. Supports `.txt`, `.pdf`, `.md`.    |
| `query [<word>...]`            | Retrieve relevant chunks and answer using an LLM. Reads question from positional args or stdin.    |
| `status`                       | Show the vector store path, chunk count, and list of ingested documents.                           |
| `search [<word>...]`           | Pure embedding search returning raw chunks without LLM involvement. Reads question from positional args or stdin. |
| `mcp-server`                   | Run as an MCP server over stdio (for Claude Code and other agentic tools).                         |
| `shell`                        | _(not yet implemented)_ Interactive REPL mode.                                                     |

Every command accepts `--help` for details.

## Search-specific flags

| Flag            | Default | Description                                      |
|-----------------|---------|--------------------------------------------------|
| `--top-k N`     | `5`     | Maximum number of chunks to return               |
| `--min-score T` | `0.0`   | Minimum similarity score; lower-scoring chunks are filtered out |
| `--output`      | `text`  | Output format: `text` or `json`                  |

## Global flags

These flags apply to all subcommands:

| Flag                   | Default                  | Description                                    |
|------------------------|--------------------------|------------------------------------------------|
| `--provider`           | `openai`                 | Chat provider: `openai`, `anthropic`, `ollama` |
| `--embedding-provider` | `openai`                 | Embedding provider: `openai`, `ollama`, `onnx` |
| `--model`              | provider default         | Override the chat model name                   |
| `--embedding-model`    | provider default         | Override the embedding model name              |
| `--ollama-url`         | `http://localhost:11434` | Ollama base URL                                |
| `--verbose` / `-v`     | off                      | Enable debug logging; for `query`, also prints each source file path, similarity score, and chunk index to stderr |

## Providers

### Chat providers

| Provider    | Default model       | Environment variable         |
|-------------|---------------------|------------------------------|
| `openai`    | `gpt-4o-mini`       | `OPENAI_API_KEY`             |
| `anthropic` | `claude-sonnet-4-6` | `ANTHROPIC_API_KEY`          |
| `ollama`    | `llama3.2`          | `OLLAMA_BASE_URL` (optional) |

### Embedding providers

| Provider | Default model            | Notes                                                                           |
|----------|--------------------------|---------------------------------------------------------------------------------|
| `openai` | `text-embedding-3-small` | Requires `OPENAI_API_KEY`                                                       |
| `ollama` | `nomic-embed-text`       | Requires a running Ollama instance with the model pulled                        |
| `onnx`   | `all-MiniLM-L6-v2`       | Fully local; model downloaded automatically on first use to `~/.ez-rag/models/` |

Anthropic has no embedding API. If you use `--provider anthropic`, set `--embedding-provider openai` or `--embedding-provider onnx`.

The ONNX provider requires no API key and sends no data to external services. It is suitable for air-gapped or privacy-sensitive environments.

## Configuration file

Persistent defaults can be set in `~/.ez-rag/config.yml` so you do not have to repeat flags on every invocation. CLI flags always override the config file.

```yaml
provider: openai
embedding-provider: onnx
model: gpt-4o
embedding-model: text-embedding-3-small
ollama-url: http://localhost:11434
store-path: .ez-rag/vector-store.json
chunk-size: 1000
chunk-overlap: 200
top-k: 5
system-prompt: ""
output-format: text
verbose: false
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

To use a specific provider or a non-default store location:

```json
{
  "mcpServers": {
    "ez-rag": {
      "command": "ez-rag",
      "args": ["mcp-server", "--provider", "anthropic", "--embedding-provider", "onnx", "--store", ".ez-rag/vector-store.json"]
    }
  }
}
```

### MCP server flags

| Flag                   | Default                          | Description                                            |
|------------------------|----------------------------------|--------------------------------------------------------|
| `--provider`           | `openai`                         | Chat provider used by the `query` tool                 |
| `--embedding-provider` | `openai`                         | Embedding provider used by all tools                   |
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

Retrieves relevant chunks and generates an answer using the configured LLM (RAG).

| Parameter  | Type   | Required | Default          | Description                          |
|------------|--------|----------|------------------|--------------------------------------|
| `question` | String | yes      | —                | The question to ask                  |
| `topK`     | Int    | no       | `5`              | Maximum number of chunks to retrieve |
| `provider` | String | no       | server default   | Override the chat provider           |
| `model`    | String | no       | provider default | Override the chat model              |

| Return field | Type            | Description                                                              |
|--------------|-----------------|--------------------------------------------------------------------------|
| `answer`     | String          | LLM-generated answer                                                     |
| `sources`    | List of objects | Each entry has `filePath`, `chunkIndex`, `similarityScore`, `excerpt`    |
| `error`      | String or null  | Set when an error occurred                                               |

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
