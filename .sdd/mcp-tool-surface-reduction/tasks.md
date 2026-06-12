# Tasks: MCP Tool Surface Reduction

## Task 01-remove-mcp-tools

Remove six MCP tools (`query`, `search_bm25`, `search_embedding`, `show`, `status`, `delete`) end-to-end: write a failing test for the new exact tool set, delete the six tool source and individual test files, strip their registrations and dead code from `McpServerCommand`, then clean up the now-redundant per-tool presence tests.

### Implementation steps

- [x] In `McpServerCommandTest`, add a new test asserting the tool set is exactly `{search, chunk, ingest, reingest, list}` — this is the **red step** because 11 tools are currently registered
- [x] Delete the six MCP tool source files: `McpQueryTool.kt`, `McpBm25SearchTool.kt`, `McpEmbeddingSearchTool.kt`, `McpShowTool.kt`, `McpStatusTool.kt`, `McpDeleteTool.kt`
- [x] Delete the six individual tool test files: `McpQueryToolTest.kt`, `McpBm25SearchToolTest.kt`, `McpEmbeddingSearchToolTest.kt`, `McpShowToolTest.kt`, `McpStatusToolTest.kt`, `McpDeleteToolTest.kt`
- [x] In `McpServerCommand.mcpToolCallbackProvider()`, remove the registrations of the six deleted tools and all now-unused local variables: `embeddingSearchPipeline`, `bm25SearchPipeline`, `chatModel`, `queryTool`, `deleteTool`, `showTool`, `statusTool`
- [x] Remove the `@Autowired springChatModel` field and its import (`ChatModel`) from `McpServerCommand` — it is solely used by the deleted `query` tool
- [x] Remove the now-unused imports `BM25SearchPipeline`, `EmbeddingSearchPipeline`, and `RagPipeline` from `McpServerCommand`
- [x] Remove the four redundant per-tool presence tests (`status`, `query`, `search_bm25`, `search_embedding`) from `McpServerCommandTest` — the new exact-set assertion supersedes them

### Acceptance criteria

- [x] `McpServerCommandTest` passes, asserting the tool set is exactly `{search, chunk, ingest, reingest, list}`
- [x] The `ToolCallbackProvider` does not expose any of: `query`, `search_bm25`, `search_embedding`, `show`, `status`, `delete`
- [x] All retained tool tests (`McpSearchToolTest`, `McpChunkToolTest`, `McpIngestToolTest`, `McpReIngestToolTest`, `McpListToolTest`) continue to pass

### Quality gates

- [x] Project compiles without errors: `./gradlew compileKotlin`
- [x] No `UNUSED_IMPORT` warnings for `McpServerCommand.kt`: run `./gradlew compileKotlin 2>&1 | grep -i "unused"` and confirm no output for that file
- [x] Full test suite passes: `./gradlew test`

---

## Task 02-update-readme

Update the README `### MCP Server` section so it accurately documents only the five retained tools, removing all references to the six deleted tools from tool listings, flag tables, inline notes, and the table of contents.

### Implementation steps

- [x] Remove the six tool subsections from `#### Available MCP tools`: `##### status`, `##### query`, `##### search_bm25`, `##### search_embedding`, `##### delete`, `##### show`
- [x] Remove the corresponding six anchor lines from the table of contents at the top of the README
- [x] Update the opening sentence of `### MCP Server` (currently: "can call `ingest`, `query`, `search`, and `status`") to list the five retained tools: `search`, `chunk`, `ingest`, `reingest`, `list`
- [x] Update the `> **Note:**` below the opening sentence (currently mentions `status` and `query`) to reference only `search`
- [x] Remove the `--provider` row from the MCP server flags table — with `query` removed, that flag has no effect on any MCP tool
- [x] Update the `chunk` tool description to reference only `search` results (currently also mentions `search_embedding` and `search_bm25`)

### Acceptance criteria

- [x] The `#### Available MCP tools` section contains exactly five subsections: `search`, `chunk`, `ingest`, `reingest`, `list`
- [x] No occurrence of `query`, `search_bm25`, `search_embedding`, `show`, `status`, or `delete` (as tool names) remains anywhere in the `### MCP Server` section
- [x] The intro sentence, the note, and the `chunk` tool description all reflect the current five-tool surface
- [x] The table of contents contains no dead anchors for the removed tool subsections

### Quality gates

- [x] All six removed tool names are absent from the MCP Server section: `grep -n "search_bm25\|search_embedding\|\`query\`\|\`show\`\|\`status\`\|\`delete\`" README.md` returns no matches in the MCP Server section
