# MCP Tool Surface Reduction

## Problem Statement

The ez-rag MCP server currently exposes 11 tools to any connected agentic coding client (such as Claude Code). Several of these tools are redundant or counterproductive when Claude is the agent:

- **`query`** calls an embedded LLM to synthesise an answer on behalf of the agent — but the agent *is* an LLM and should reason over raw chunks itself. The tool adds latency, burns extra tokens, and introduces a redundant reasoning hop.
- **`search_bm25`** and **`search_embedding`** expose the internal sub-strategies of `search`. An agent does not need to choose between retrieval strategies; the hybrid `search` tool already selects the best combination automatically.
- **`show`** and **`status`** are administrative/diagnostic tools with no practical value during agentic retrieval workflows.
- **`delete`** is an irreversible store-modification operation that risks accidental data loss when exposed to an automated agent.

A smaller, focused MCP surface is easier for the agent to reason about, produces shorter tool-choice prompts, and removes the risk of unintended side-effects.

## Solution

Remove the six tools listed above from the MCP server entirely — both the source files and their associated tests. The five tools that remain (`search`, `chunk`, `ingest`, `reingest`, `list`) cover the complete agentic workflow: populate the store, keep it current, retrieve relevant chunks, and fetch surrounding context.

The CLI commands (`ez-rag delete`, `ez-rag show`, `ez-rag status`, `ez-rag query`) are unaffected; they continue to serve human operators.

## User Stories

1. As an agentic coding tool, I want the MCP server to offer only the tools I actually need, so that tool-choice overhead is minimised and I do not accidentally trigger destructive operations.
2. As an agentic coding tool, I want to retrieve relevant document chunks using a single `search` tool, so that I do not have to decide between BM25 and embedding strategies.
3. As an agentic coding tool, I want to ingest new documents via the `ingest` tool, so that I can expand the knowledge base during a session.
4. As an agentic coding tool, I want to refresh stale documents via the `reingest` tool, so that I always retrieve up-to-date content.
5. As an agentic coding tool, I want to list ingested documents via the `list` tool, so that I can discover what knowledge is available before searching.
6. As an agentic coding tool, I want to fetch the full text of a specific chunk (with optional surrounding context) via the `chunk` tool, so that I can read more than the snippet returned by `search`.
7. As a developer integrating ez-rag, I want the MCP tool list to be documented accurately, so that I know which tools to build workflows around.
8. As a developer integrating ez-rag, I want the CLI commands to remain fully functional, so that I can still perform administrative operations manually.

## User Acceptance Tests

1. Given a running ez-rag MCP server, when a client lists the available tools, then exactly five tools are present: `search`, `chunk`, `ingest`, `reingest`, and `list`.
2. Given a running ez-rag MCP server, when a client lists the available tools, then none of the following appear: `query`, `search_bm25`, `search_embedding`, `show`, `status`, `delete`.
3. Given a document has been ingested, when the agent calls `search` with a relevant query, then the response contains matching chunks ranked by hybrid score.
4. Given a document has been ingested, when the agent calls `list`, then the document appears in the result with its chunk count and staleness status.
5. Given a document has been ingested and then modified on disk, when the agent calls `reingest`, then the stale document is re-processed and the list no longer shows it as stale.
6. Given a search result containing a chunk index, when the agent calls `chunk` with that index and a window of 1, then the response includes the target chunk and its immediate neighbours.
7. Given the project README, when a developer reads the "Available MCP tools" section, then only the five remaining tools (`search`, `chunk`, `ingest`, `reingest`, `list`) are documented.
8. Given the CLI, when a developer runs `ez-rag delete`, `ez-rag show`, `ez-rag status`, or `ez-rag query`, then the command executes normally (CLI commands are unaffected).

## Definition of Done

- The MCP server exposes exactly five tools: `search`, `chunk`, `ingest`, `reingest`, `list`.
- The six removed tool source files no longer exist in the codebase.
- The six removed tool test files no longer exist in the codebase.
- All remaining automated tests pass.
- The README "Available MCP tools" section documents only the five remaining tools.
- No CLI commands are broken or changed.

## Out of Scope

- Changes to the CLI commands (`ez-rag delete`, `ez-rag show`, `ez-rag status`, `ez-rag query`).
- Changes to the interfaces or behaviour of the five retained MCP tools.
- Any opt-in mechanism to re-enable the removed tools (e.g., `--all-tools` flag).
- Changes to the underlying pipeline classes (`BM25SearchPipeline`, `EmbeddingSearchPipeline`, `RagPipeline`) — these are internal infrastructure and may still be used by the CLI or other pipelines.

## Further Notes

- `HybridSearchPipeline` instantiates `BM25SearchPipeline` and `EmbeddingSearchPipeline` internally, so removing the two specialised MCP tools does not affect the `search` tool's behaviour.
- `McpServerCommandTest` currently contains positive assertions for the six tools being removed. These tests must also be removed (or converted to negative assertions) as part of this task.

---

## Technical Annex
> Written against codebase as of: 2026-06-10

### Architectural Decisions

**Files to delete:**

| Source file | Test file |
|---|---|
| `src/main/kotlin/.../command/McpQueryTool.kt` | `src/test/kotlin/.../command/McpQueryToolTest.kt` |
| `src/main/kotlin/.../command/McpBm25SearchTool.kt` | `src/test/kotlin/.../command/McpBm25SearchToolTest.kt` |
| `src/main/kotlin/.../command/McpEmbeddingSearchTool.kt` | `src/test/kotlin/.../command/McpEmbeddingSearchToolTest.kt` |
| `src/main/kotlin/.../command/McpShowTool.kt` | `src/test/kotlin/.../command/McpShowToolTest.kt` |
| `src/main/kotlin/.../command/McpStatusTool.kt` | `src/test/kotlin/.../command/McpStatusToolTest.kt` |
| `src/main/kotlin/.../command/McpDeleteTool.kt` | `src/test/kotlin/.../command/McpDeleteToolTest.kt` |

**`McpServerCommand.mcpToolCallbackProvider()` — variables to remove:**

```kotlin
// Remove these lines (only used by deleted tools):
val embeddingSearchPipeline = EmbeddingSearchPipeline(luceneRepository)
val bm25SearchPipeline = BM25SearchPipeline(luceneRepository)
val bm25SearchTool = McpBm25SearchTool(bm25SearchPipeline)
val embeddingSearchTool = McpEmbeddingSearchTool(embeddingSearchPipeline)
val queryTool = chatModel?.let { McpQueryTool(RagPipeline(embeddingSearchPipeline, it)) }
val deleteTool = McpDeleteTool(embeddingModel, storeDir)
val showTool = McpShowTool(luceneRepository)
val statusTool = McpStatusTool(luceneRepository)

// The `chatModel` local variable and the `springChatModel` @Autowired field
// become unused and should also be removed.
```

After removal, `mcpToolCallbackProvider()` only needs:
- `luceneRepository` (already used by `listTool`, `chunkTool`, and as input to `HybridSearchPipeline`)
- `embeddingModel` + `storeDir` (used by `ingestTool` and `reIngestTool`)
- `hybridSearchPipeline` → `searchTool`

**Imports to remove from `McpServerCommand.kt`:**
- `BM25SearchPipeline`, `EmbeddingSearchPipeline`, `RagPipeline`
- `ChatModel` and its Spring import (`org.springframework.ai.chat.model.ChatModel`)

**`McpServerCommandTest` changes:**

Remove these individual tool-presence tests (the named tools no longer exist):
- `` `ToolCallbackProvider exposes a tool named status` ``
- `` `ToolCallbackProvider exposes a tool named query` ``
- `` `ToolCallbackProvider exposes a tool named search_bm25` ``
- `` `ToolCallbackProvider exposes a tool named search_embedding` ``
- `` `ToolCallbackProvider exposes a tool named delete` ``
- `` `ToolCallbackProvider exposes a tool named show` ``

Add a single replacement test that asserts the exact tool set:

```kotlin
@Test
fun `ToolCallbackProvider exposes exactly the expected tools`() {
    val toolNames = mcpToolCallbackProvider.toolCallbacks
        .map { it.toolDefinition.name() }
        .toSet()
    assertThat(toolNames).containsExactlyInAnyOrder(
        "search", "chunk", "ingest", "reingest", "list"
    )
}
```

**`README.md` changes:**

- Remove the `##### status`, `##### query`, `##### search_bm25`, `##### search_embedding`, `##### delete`, and `##### show` subsections from the "Available MCP tools" section (lines ~1061–1163).
- Update the intro sentence on line 983 (currently lists `ingest`, `query`, `search`, `status`) to list only `search`, `chunk`, `ingest`, `reingest`, `list`.
- Remove the reference to `search_embedding` and `search_bm25` in the `chunk` tool description (line 1167).

### Automated Testing Decisions

**What makes a good test here:** assert on observable behaviour — specifically which tool names the `ToolCallbackProvider` exposes — not on internal wiring or class instantiation order.

**Modules with automated tests:**

| Module | Test type | Notes |
|---|---|---|
| `McpServerCommand.mcpToolCallbackProvider()` | Spring integration test | Existing `McpServerCommandTest` — update to assert exact 5-tool set; remove 6 obsolete per-tool tests |

No new test files are needed. The deleted tool test files are simply removed. The retained per-tool tests (`McpSearchToolTest`, `McpChunkToolTest`, `McpIngestToolTest`, `McpReIngestToolTest`, `McpListToolTest`) are unaffected.

**Prior art:** `McpServerCommandTest` already uses `@ExtendWith(SpringExtension::class)` with a minimal Spring context (`spring.ai.mcp.server.enabled=false`) and asserts `toolCallbacks.map { it.toolDefinition.name() }.contains(...)`. The replacement test follows the same pattern.
