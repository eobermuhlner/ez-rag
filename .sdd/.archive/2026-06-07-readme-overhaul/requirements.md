# README Overhaul + Output Flag Unification

## Problem Statement

New users arriving at the ez-rag repository face a README that reads like a reference manual rather than a welcoming guide. There is no elevator pitch, no table of contents on a ~1,240-line document, and the distinction between `search` (raw chunks) and `query` (LLM answer) is not explained upfront. Additionally, the output-format flag has three inconsistent names across commands (`--output`, `--output-format`, `--format`), which causes confusion and scripting errors. One MCP example uses a flag (`--store`) that does not exist in the code. The developer guide for contributors is mixed in with user-facing content, diluting both.

## Solution

Restructure the README to lead with value, be navigable via a table of contents, and present a clear linear flow from installation through the core workflow to advanced features. Extract the developer guide to `CONTRIBUTING.md`. Simultaneously rename all output-format flags in the source code to the single canonical name `--output-format`, eliminating the inconsistency at its root. Fix the MCP documentation bug. These changes together make ez-rag significantly easier to discover, use, and script against.

## User Stories

1. As a first-time visitor, I want to understand what ez-rag does and what RAG means in two sentences, so that I can decide in seconds whether this tool is relevant to me.
2. As a new user, I want a table of contents at the top of the README, so that I can jump directly to the section I need without scrolling through the entire document.
3. As a new user reading the Quick Start, I want to know which file types are supported by `ingest`, so that I do not waste time trying to ingest unsupported formats.
4. As a new user, I want a clear one-sentence explanation of the difference between `search` and `query` near the top of the README, so that I choose the right command from the start.
5. As a user who has just edited some documents, I want a dedicated "Keeping the store up to date" section, so that I can find the `list` → `reingest` workflow without reading through two separate command descriptions.
6. As a user who scripts ez-rag, I want every command that supports output format selection to use the same flag name (`--output-format`), so that I do not have to remember different flag names per command.
7. As a user consulting `--help`, I want consistent flag naming, so that tab-completing `--output-format` works across all commands.
8. As a user following the MCP server setup guide, I want the JSON configuration example to use the correct flag name (`--store-dir`), so that my configuration actually works without trial and error.
9. As a user looking at the config file sample, I want all keys to be commented out uniformly, so that I understand the file is a pure reference template rather than an active configuration.
10. As a CLI user focused on the core ingest/search/query workflow, I want the commands section to appear before the agentic-tools integration section, so that I can learn the fundamentals without wading through agent-specific material first.
11. As a user who rarely needs eval or MCP, I want those advanced features grouped in an "Advanced" section near the end, so that the main body of the README stays focused on everyday use.
12. As a developer wanting to build or contribute to ez-rag, I want a dedicated `CONTRIBUTING.md` covering build instructions, technology stack, and architecture notes, so that the user-facing README is not cluttered with developer-only content.
13. As a developer, I want the README to link to `CONTRIBUTING.md` with a single pointer line, so that contributors can find it easily.

## User Acceptance Tests

1. Given the README, when a reader opens it on GitHub, then the first visible section after the title contains a two-sentence description of what ez-rag does and a one-line explanation of RAG.
2. Given the README, when a reader opens it, then a table of contents appears near the top with anchor links to all major sections.
3. Given the Quick Start section of the README, when a reader looks at the `ingest` example, then the supported file types (`.txt`, `.pdf`, `.md`, `.html`/`.htm`) are mentioned inline.
4. Given the Quick Start section, when a reader reads through it, then they encounter a sentence explaining that `search` returns raw chunks while `query` passes those chunks to an LLM to produce a generated answer.
5. Given the README, when a reader looks for how to refresh their store after editing documents, then they find a dedicated section named "Keeping the store up to date" that describes the `list` → `reingest` workflow.
6. Given a user running `ez-rag search --output json "test"`, when they check the flag name against the show, chunk, list, status, and eval commands, then all of those commands use `--output-format` as the flag name.
7. Given a user running `ez-rag show --output json docs/file.md`, then the command fails with an "unmatched argument" or unknown-option error because `--output` no longer exists.
8. Given the MCP server registration example in the README, when a user copies it and runs ez-rag with the listed flags, then the configuration is accepted without errors (i.e. `--store-dir` is used, not `--store`).
9. Given the config file sample in the README, when a reader looks at it, then every key including `rerank-model` is commented out with its default value shown in the comment.
10. Given the README structure, when a reader scrolls through it, then the Commands section appears before the "Using ez-rag with agentic coding tools" section.
11. Given the README, when a reader scrolls through it, then `eval` and `mcp-server` appear in an "Advanced" section after the main command reference and the agentic-tools section.
12. Given a developer looking for build and architecture information, when they look at the README, then they see a single pointer line referencing `CONTRIBUTING.md` rather than full build instructions.
13. Given the `CONTRIBUTING.md` file, when a developer opens it, then it contains the technology stack, build commands, testing instructions, provider selection design, and the "adding a new subcommand" guide.

## Out of Scope

- Changes to the `--output-format` values themselves (e.g. adding new formats such as `csv`).
- Changing the output format of any command (only the flag name changes).
- Adding a `--help` short-form alias (`-f`) or any other new flag aliases.
- Any changes to the MCP server's JSON protocol or tool definitions.
- Content changes to the skill file installed by `install-skill`.
- Adding new commands or new features.
- Changing chunk size defaults, model defaults, or any runtime behaviour.

## Further Notes

- The `--output-format` rename is a breaking change for any existing scripts that use `ez-rag show --output`, `ez-rag search --output`, `ez-rag chunk --output`, or `ez-rag eval --format`. No deprecated aliases will be added; this is intentional given the early stage of the project.
- `to-markdown` already uses `--output-format` and is not affected by the rename.
- `list` and `status` already use `--output-format` and are not affected by the rename.
- The `reingest` command has no output-format flag and is not affected.

---

## Technical Annex
> Written against codebase as of: 2026-06-07

### Architectural Decisions

#### Flag rename — affected command classes

Four command classes need their output flag renamed. No other code changes are required for the flag rename:

| Command class | Current flag | New flag |
|---|---|---|
| `SearchCommand` | `--output` | `--output-format` |
| `ShowCommand` | `--output` | `--output-format` |
| `ChunkCommand` | `--output` | `--output-format` |
| `EvalCommand` | `--format` | `--output-format` |

In each class the change is a one-line edit to the `@Option(names = [...])` annotation. The backing field name and type do not need to change.

Already correct (no change needed): `ListCommand`, `StatusCommand`, `ToMarkdownCommand`.

#### MCP documentation bug

In the README MCP server section, the JSON example passes `"--store"` as an arg. The correct flag (as declared in `McpServerCommand` / global `CliFlags`) is `--store-dir`. This is a documentation-only fix.

#### README structure after changes

Proposed section order:

1. Title + elevator pitch (new)
2. Table of contents (new)
3. Requirements
4. Build and install
5. Quick Start (extended: file types callout + search-vs-query sentence)
6. **Keeping the store up to date** (new section)
7. Commands (core: init, install-skill, ingest, delete, list, reingest, show, chunk, status, search, query, shell, to-markdown)
8. Hybrid search
9. Search-specific flags
10. Global flags
11. Providers
12. API keys
13. Configuration file (config sample fix: comment out rerank-model)
14. Store
15. RAG settings
16. Using ez-rag with agentic coding tools (moved from position 2)
17. **Advanced** (new heading)
    - Reranking
    - Output format
    - MCP Server (bug fix: `--store-dir`)
    - eval
18. See CONTRIBUTING.md (replaces the Developer guide section)

#### CONTRIBUTING.md

New file. Contains verbatim extraction of the current "Developer guide" section from README.md:
- Technology stack
- Build
- Testing
- Provider selection design
- Adding a new subcommand

### Automated Testing Decisions

**What makes a good test here:** Tests verify the observable CLI behaviour (exit code, stdout content, flag acceptance/rejection) rather than internal field values. They do not test that a private field was set to a particular enum value.

#### Tests to add or update

**`SearchCommandTest`** — add a test asserting that `--output-format json` is accepted and produces JSON output; add a test asserting that `--output json` is rejected (unknown option). Pattern: follows existing `SearchCommandTest` which wires a `LuceneRepository` with a fake `EmbeddingModel` and runs `CommandLine.execute(...)`.

**`ShowCommandTest`** — same pattern: `--output-format json` accepted, `--output json` rejected.

**`ChunkCommandTest`** — same pattern: `--output-format json` accepted, `--output json` rejected.

**`EvalCommandTest`** (if it exists, otherwise new) — `--output-format json` accepted, `--format json` rejected.

**Prior art for command tests:** `SearchCommandTest`, `ShowCommandTest`, `ChunkCommandTest`, `ListCommandTest` in `src/test/kotlin/ch/obermuhlner/ezrag/command/`. All follow the same pattern: construct a temp `LuceneRepository`, wire a minimal `CommandLine`, capture stdout via `StringWriter`, assert on the output string.

No integration test changes are needed for the documentation changes (README, CONTRIBUTING.md).
