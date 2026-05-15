Setup this project as follows:

## Project

- Kotlin
- JDK 21
- Spring Boot 3.4.x
- Gradle with Kotlin DSL
- Gradle Application Plugin + Spring Boot fat JAR
- Group ID: `ch.obermuhlner`, Artifact ID: `ez-rag`, base package: `ch.obermuhlner.ezrag`
- Argument parsing: picocli

## CLI Subcommands

- `ingest <file|dir>` — ingest files or directories (recursive by default), supports `.txt`, `.pdf`, `.md`
- `query [--question "..."]` — query the vector store; reads from stdin if `--question` is omitted
- `search [--question "..."]` — pure embedding search returning raw chunks; no LLM involved; reads from stdin if `--question` is omitted
- `status` — show vector store path, number of chunks, and list of ingested documents
- `mcp-server` — run as an MCP server using stdio transport (for Claude Code and other agentic tools)
- `shell` — interactive REPL mode (secondary use case)

## LLM Providers

Selectable via `--provider` flag. All three included as dependencies.

| Provider  | Default model         |
|-----------|-----------------------|
| openai    | `gpt-4o-mini`         |
| anthropic | `claude-sonnet-4-6`   |
| ollama    | `llama3.2`            |

## Embedding Providers

Selectable via `--embedding-provider` flag. When using Anthropic for chat, default to OpenAI for embeddings.

| Provider | Default model            | Notes                               |
|----------|--------------------------|-------------------------------------|
| openai   | `text-embedding-3-small` |                                     |
| ollama   | `nomic-embed-text`       |                                     |
| onnx     | `all-MiniLM-L6-v2`      | local, via `TransformersEmbeddingModel` |

## Vector Store

- `SimpleVectorStore` with file persistence
- Default location: `.ez-rag/vector-store.json` (project-local, mirrors `.git/`, `.claude/` conventions)
- Override via `--store <path>`

## RAG Defaults (all configurable)

- Chunk size: 1000 tokens
- Chunk overlap: 200 tokens
- Top-k retrieved chunks: 5
- System prompt: default RAG template, overridable via `--system-prompt` or config file

## Configuration

- Secrets via environment variables: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`
- Persistent defaults in `~/.ez-rag/config.yml` (provider, model, store path, chunk settings, system prompt)
- CLI flags override all config

## Output

- Default: human-readable with sources/citations
- `--output json` for machine-readable output (for agentic tool integration)

## Logging

- All Spring Boot/framework logs suppressed by default
- `--verbose` flag to enable debug logging

## Packaging & Distribution

- `./gradlew installDist` produces `build/install/ez-rag/bin/ez-rag` (Unix) and `ez-rag.bat` (Windows)
- Symlink or add `build/install/ez-rag/bin/` to PATH for global use

## Integration

- **MCP**: add `ez-rag mcp-server` to Claude Code MCP config for tool-based integration
- **CLI**: invoke directly from shell or agentic pipelines via stdin/stdout
- **Skill**: optional `.claude/skills/ez-rag.md` wrapper skill for Claude Code
