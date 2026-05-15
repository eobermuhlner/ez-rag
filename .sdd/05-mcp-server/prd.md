## Problem Statement

Agentic coding tools (Claude Code, and others supporting MCP) cannot natively search project documentation unless a tool integration exists. Shelling out to `ez-rag query` and parsing text output is fragile. A proper MCP server integration allows agents to call `ingest`, `query`, and `status` as structured tools with typed inputs and outputs, making `ez-rag` a first-class tool in any MCP-compatible agentic workflow.

## Solution

Implement the `mcp-server` subcommand. When invoked, `ez-rag mcp-server` starts a long-running MCP server that communicates over stdio using the MCP protocol. It exposes three MCP tools: `ingest`, `query`, and `status`. Agentic tools add `ez-rag mcp-server` to their MCP server config and invoke these tools directly without parsing CLI text output.

## User Stories

1. As a Claude Code user, I want to add `ez-rag mcp-server` to my MCP config, so that Claude Code can call `ingest` and `query` as structured tools.
2. As an agentic tool, I want to call the `query` MCP tool with `{ "question": "..." }` and receive `{ "answer": "...", "sources": [...] }`, so that I get structured data without parsing text.
3. As an agentic tool, I want to call the `ingest` MCP tool with `{ "path": "docs/" }`, so that I can trigger document ingestion programmatically.
4. As an agentic tool, I want to call the `status` MCP tool and receive structured store metadata, so that I can check whether ingestion has been done before querying.
5. As a user, I want `ez-rag mcp-server` to start instantly and stay running, accepting MCP requests over stdin and writing responses to stdout, so that the MCP host does not have to restart it between calls.
6. As a user, I want the MCP server to load the vector store from disk at startup, so that queries are fast without reloading on every call.
7. As a user, I want the MCP server to save the vector store after each `ingest` call, so that ingested data is persisted immediately.
8. As a user, I want all logging suppressed in MCP server mode (unless `--verbose`), so that log output does not corrupt the stdio MCP protocol stream.
9. As a developer, I want the MCP tool implementations to reuse the same `RagPipeline`, `DocumentLoader`, and `VectorStoreRepository` modules used by the CLI commands, so that behaviour is identical across integration modes.
10. As a user, I want the MCP server to handle errors gracefully and return MCP error responses rather than crashing, so that the agentic tool receives structured error information.
11. As a Claude Code user, I want documentation on how to add `ez-rag` to my MCP config (`.claude/mcp.json`), so that setup is straightforward.
12. As an agentic tool, I want to pass `--provider`, `--embedding-provider`, and `--store` flags when starting the MCP server, so that the server is configured for the project without requiring a config file.

## Implementation Decisions

- **MCP framework**: Use Spring AI's MCP server support (`spring-ai-mcp-server-spring-boot-starter`). Configure it for stdio transport in `application.yml` when the `mcp-server` subcommand is active.
- **MCP tool registration**: Each tool is a Spring bean annotated with Spring AI's `@Tool` annotation (or equivalent MCP tool registration API). The three tools are:
  - `ingest(path: String, chunkSize: Int?, chunkOverlap: Int?)` → `IngestResult`
  - `query(question: String, topK: Int?, provider: String?, model: String?)` → `RagResult`
  - `status()` → `StoreStatus`
- **Reuse**: `McpServerCommand` wires the same `RagPipeline`, `DocumentLoader`, `DocumentChunker`, `DirectoryWalker`, and `VectorStoreRepository` beans as the CLI commands. No logic is duplicated.
- **Startup vector store load**: `McpServerCommand` calls `VectorStoreRepository.load()` on startup, so the store is in memory for the lifetime of the server process.
- **Logging isolation**: When `mcp-server` is the active subcommand, the logging configuration must route all output to stderr (not stdout), since stdout is the MCP communication channel.
- **Error handling**: Exceptions thrown by tool implementations are caught and returned as MCP error responses with a human-readable message.
- **MCP config snippet** (documented in README):
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

## Testing Decisions

- **What makes a good test**: Test each MCP tool's logic via the underlying module (e.g., test `RagPipeline` for the `query` tool). The MCP protocol layer itself (JSON-RPC serialization) is tested by Spring AI's own test suite.
- **Tool implementations**: Unit-test with mocked `RagPipeline`, `DocumentLoader`, etc. Assert that tool input parameters are correctly forwarded to the underlying modules.
- **Error path**: Unit-test that an exception from `RagPipeline` results in a structured error response rather than an unhandled exception.
- **No live MCP protocol tests**: End-to-end MCP protocol testing (actual JSON-RPC over stdio) requires a running MCP host; this is integration testing outside the automated suite.

## Out of Scope

- HTTP/SSE transport (stdio only in this PRD).
- MCP tool for `status` returning document-level detail beyond what PRD 02's status command returns.
- Authentication or authorization on the MCP server.
- Claude Code skill wrapper (`.claude/skills/ez-rag.md`) — that is a simple markdown file, not a code artifact.

## Further Notes

The key constraint is that stdout must be exclusively used for MCP protocol messages. Any accidental log line to stdout will break the JSON-RPC framing and corrupt the session. This must be enforced by the logging configuration, not by discipline. Consider adding a startup assertion that the root logger's appender is not stdout when running in MCP server mode.
