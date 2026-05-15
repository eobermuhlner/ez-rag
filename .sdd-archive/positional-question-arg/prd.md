## Problem Statement

Users must type `--question` (or `-q`) every time they run `ez-rag search` or `ez-rag query`, making quick interactive use verbose. Every other question-taking CLI tool (e.g. `grep`, `git log --grep`, `curl`) lets users pass the primary input positionally. Requiring a named flag for the main argument creates friction and violates the principle of least surprise.

## Solution

Make the question a positional parameter on the `search` and `query` subcommands. Users can then type:

```
ez-rag search What is the project license?
ez-rag query "Summarize the architecture"
```

All tokens after the subcommand name and any flags are joined with a single space to form the question, so quoting is optional. If no positional tokens are given, the command reads the question from stdin as before (enabling pipe-friendly use).

The `--question` / `-q` named option is removed entirely.

## User Stories

1. As a CLI user, I want to pass my search query as a bare positional argument after `ez-rag search`, so that I can run searches without typing `--question`.
2. As a CLI user, I want to pass my RAG query as a bare positional argument after `ez-rag query`, so that I can ask questions without typing `--question`.
3. As a CLI user, I want to write `ez-rag search What is X?` without quotes, so that multi-word questions do not require shell quoting.
4. As a CLI user, I want to write `ez-rag search "What is X?"` with quotes, so that I can still quote when I need to.
5. As a CLI user, I want flags like `--top-k` and `--output` to still work before or after the question, so that my existing flag usage is unchanged.
6. As a CLI user, I want `ez-rag search` with no arguments to read from stdin, so that I can pipe questions: `echo "What is X?" | ez-rag search`.
7. As a CLI user, I want `ez-rag query` with no arguments to read from stdin, so that I can compose pipelines involving the query command.
8. As a CLI user, I want an error message if I provide no positional argument and stdin is empty, so that I understand what went wrong.
9. As a CLI user, I want `ez-rag search --help` to describe the positional question argument, so that I can discover the syntax from the built-in help.
10. As a CLI user, I want `ez-rag query --help` to describe the positional question argument, so that I can discover the syntax from the built-in help.
11. As a developer, I want unit tests that cover the positional argument resolution, so that regressions are caught automatically.
12. As a developer, I want an integration-style picocli test that parses actual CLI tokens, so that the join-without-quotes behaviour is verified end-to-end.

## Implementation Decisions

### Affected commands
Only `SearchCommand` and `QueryCommand` are modified. `ShellCommand` already parses its questions as free-form REPL input and is not affected.

### Picocli annotation change
The `@Option(names = ["--question", "-q"])` annotation on both commands is replaced by `@Parameters(index = "0..*")`. The field type changes from `String?` to `List<String>`, conventionally named `questionArgs`.

### Question resolution
In `call()`, the resolved question is derived as:
- If `questionArgs` is non-empty: `questionArgs.joinToString(" ")`
- Otherwise: read all bytes from the injected `InputStream`; if empty, print an error and return exit code 1.

This logic replaces the existing `question ?: run { … }` block.

### Removal of `--question` / `-q`
The named option is removed with no backwards-compatibility shim. Callers that used `--question` will receive a picocli "Unmatched argument" error and must migrate to the positional form.

### Test field references
Unit tests that previously set `cmd.question = "hello"` directly on the command instance must be updated to set `cmd.questionArgs = listOf("hello")`.

### SubcommandTest picocli-level tests
The two existing tests `query accepts --question flag` and `search accepts --question flag` are replaced with equivalent tests using positional tokens (e.g., `commandLine.execute("query", "who", "are", "you?")`).

### New multi-word picocli parse tests
One new test is added per command (in `SearchCommandTest` and `QueryCommandTest`) that constructs the command via `CommandLine` and calls `execute` with multiple unquoted tokens, asserting that the pipeline receives them joined as a single string.

### README updates
Usage examples in the Quick start section and the Commands table are updated to show the positional syntax. The `--question` flag is removed from all examples and the flags table.

## Testing Decisions

Good tests verify external, observable behaviour (exit code, output writer content, captured pipeline arguments) rather than internal implementation details. They do not assert on private field values or call private methods.

**Modules under test:**

- `SearchCommand` — unit tests using a fake `EmbeddingSearchPipeline`, updated field references, one new picocli parse test.
- `QueryCommand` — unit tests using a stub `RagPipeline`, updated field references, one new picocli parse test.
- `SubcommandTest` — integration-style tests using real `CommandLine` construction; two tests replaced.

**Prior art:** Existing tests in `SearchCommandTest` and `QueryCommandTest` follow the pattern of constructing the command with injected fakes (no Spring context), calling `cmd.call()` directly, and asserting on `StringWriter` output. The new picocli parse tests follow the pattern already established by `SubcommandTest` (constructing `CommandLine(EzRagCommand())` and calling `execute(...)`).

## Out of Scope

- `ShellCommand` REPL — already handles questions as free-form input per-line; no change needed.
- MCP tools (`McpSearchTool`, `McpQueryTool`) — these accept structured JSON parameters, not CLI arguments; unaffected.
- `IngestCommand` — already uses a positional file-path argument; no change needed.
- `StatusCommand` — takes no question argument; unaffected.
- Configuration file (`~/.ez-rag/config.yml`) — does not support a `question` key; unaffected.

## Further Notes

picocli collects `@Parameters(index = "0..*")` into a `List<String>` that is empty (not null) when no positional tokens are present. The stdin fallback check should therefore test `questionArgs.isEmpty()` rather than a null check.

Flags that appear before the positional tokens (e.g., `ez-rag search --top-k 3 What is X?`) are parsed by picocli before positional collection and do not become part of the joined question string.
