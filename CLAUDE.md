The goal of this project is a simple command line tool for RAG (retrieval-augmented generation).


## Build & Test

```
./gradlew test          # run all unit tests (required before every commit)
./gradlew build         # compile + test + package
./gradlew test --tests "ch.obermuhlner.ezrag.command.McpSearchToolTest"  # single test class
```

Tests tagged `integration` and `eval` are excluded by default. Do not run them unless explicitly asked.

## Test Driven Development

You MUST use test driven development (TDD) to build this project.
This means you will write a failing test before you write any implementation code, and then write just enough code to make the test pass. You will repeat this cycle for each new feature or piece of functionality.

Write one test at a time, and make it pass before moving on to the next test.

Verify the test fails before writing implementation code (`./gradlew test`), then make it pass.

## Documentation

Update README.md when CLI behaviour, options, or output formats change. Update CLAUDE.md when architecture constraints or the MCP tool inventory change.

## Architecture

### Storage: LuceneRepository

All chunk data — embedding vectors and BM25 text — lives in a single `LuceneRepository` backed by an on-disk Lucene index at `<storeDir>/lucene/`.

- **Semantic search**: HNSW `KnnFloatVectorField` with COSINE similarity.
- **BM25 search**: `TextField` with Lucene's `QueryParser`.
- **Single writer**: Lucene enforces one `IndexWriter` at a time; always use `LuceneRepository.open(...).use { }` and never hold two instances open on the same directory simultaneously.
- **Dimension validation**: `open()` reads the stored embedding dimension from index metadata and throws `IllegalStateException` if the current model's dimension mismatches. Pass `dimension = 0` for read-only callers (e.g. `status`) that do not embed anything.

`VectorStoreRepository` and `BM25Repository` have been deleted. Do not recreate them.

### MCP Tools

The MCP server registers exactly these five tools (see `McpServerCommand.mcpToolCallbackProvider()`):

| Tool | Class | Mode | Description |
|------|-------|------|-------------|
| `list` | `McpListTool` | read | List ingested documents with chunk count and staleness |
| `search` | `McpSearchTool` | read | Hybrid search (BM25 + embedding via RRF) |
| `ingest` | `McpIngestTool` | write | Ingest a file, directory, or HTTP/HTTPS URL |
| `reingest` | `McpReIngestTool` | write | Re-ingest stale (or all) documents |
| `chunk` | `McpChunkTool` | read | Retrieve chunk text with optional surrounding window |

The MCP `search` tool always uses hybrid mode. There are no separate `search_embedding` or `search_bm25` MCP tools (those names refer to CLI `--mode` flags only). When updating tool descriptions, reference only tools in this table.

### MCP server lifecycle

`ez-rag mcp-server --transport http` starts a long-running embedded web server. It does not exit on its own — you must kill the process (`Ctrl-C` or `kill <pid>`) to stop it. When writing tests or integration scripts that start the HTTP server, always ensure the process is terminated in cleanup; otherwise it will keep holding the port.