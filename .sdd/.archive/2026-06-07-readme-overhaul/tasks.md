# Tasks: README Overhaul + Output Flag Unification

## Task 01-rename-output-flags

Rename the output-format flag to the single canonical name `--output-format` across the four commands that currently use inconsistent names (`SearchCommand`, `ShowCommand`, `ChunkCommand`, `EvalCommand`), and add picocli-level CLI integration tests that verify the new flag is accepted and the old flag is rejected by the parser.

### Implementation steps

- [x] In `SearchCommandTest`: add a picocli-level test that passes `--output-format json` to `CommandLine.execute(...)` and asserts picocli exits with code ≠ 2 (i.e. not a USAGE/unknown-option error); add a test that passes `--output json` and asserts exit code 2
- [x] Rename `--output` → `--output-format` in `SearchCommand`
- [x] In `ShowCommandTest`: same pattern — `--output-format json` accepted (exit ≠ 2), `--output json` rejected (exit = 2)
- [x] Rename `--output` → `--output-format` in `ShowCommand`
- [x] In `ChunkCommandTest`: same pattern — `--output-format json` accepted (exit ≠ 2), `--output json` rejected (exit = 2)
- [x] Rename `--output` → `--output-format` in `ChunkCommand`
- [x] In `EvalCommandTest`: add a picocli-level test supplying a valid temp corpus dir alongside `--output-format json` and assert exit ≠ 2; add a test supplying `--format json` and assert exit = 2
- [x] Rename `--format` → `--output-format` in `EvalCommand`

### Acceptance criteria

- [x] `CommandLine.execute("--output-format", "json", ...)` on `SearchCommand` returns exit code ≠ 2 (picocli parse succeeds)
- [x] `CommandLine.execute("--output", "json", ...)` on `SearchCommand` returns exit code 2 (unknown option)
- [x] Same acceptance criterion holds for `ShowCommand` with `--output-format json` / `--output json`
- [x] Same acceptance criterion holds for `ChunkCommand` with `--output-format json` / `--output json`
- [x] `CommandLine.execute("--output-format", "json", <tempCorpusDir>)` on `EvalCommand` returns exit code ≠ 2
- [x] `CommandLine.execute("--format", "json", <tempCorpusDir>)` on `EvalCommand` returns exit code 2
- [x] `./gradlew test` passes with all tests green

### Quality gates

- [x] `./gradlew build` completes without compiler errors or warnings
- [x] No Kotlin type errors introduced in the four changed command classes

---

## Task 02-extract-contributing

Extract the "Developer guide" section from README.md verbatim into a new `CONTRIBUTING.md` at the project root. README.md is **not** modified by this task — removal of the Developer guide section from the README is owned by Task 03.

### Implementation steps

- [x] Create `CONTRIBUTING.md` at the project root, opening with a `# Contributing` heading
- [x] Copy into `CONTRIBUTING.md` the content of the following README subsections verbatim: Technology stack, Build, Testing, Provider selection design, Adding a new subcommand
- [x] Verify no relative links in the copied content are broken from the new file's location

### Acceptance criteria

- [x] `CONTRIBUTING.md` exists at the project root
- [x] `CONTRIBUTING.md` contains a "Technology stack" subsection
- [x] `CONTRIBUTING.md` contains a "Build" subsection
- [x] `CONTRIBUTING.md` contains a "Testing" subsection
- [x] `CONTRIBUTING.md` contains a "Provider selection design" subsection
- [x] `CONTRIBUTING.md` contains an "Adding a new subcommand" subsection
- [x] `README.md` is unchanged by this task (the Developer guide section still exists in it — removal happens in Task 03)

### Quality gates

- [x] `CONTRIBUTING.md` is valid Markdown with no unclosed code fences or malformed headings
- [x] Any relative links in the extracted content still resolve from the project root

---

## Task 03-restructure-readme

Overhaul the README end-to-end: add elevator pitch and table of contents, improve Quick Start with file-types callout and search-vs-query explanation, add a "Keeping the store up to date" section, reorder all sections per spec, fix the MCP `--store` → `--store-dir` documentation bug, comment out `rerank-model` in the config sample, add an "Advanced" section grouping, and replace the Developer guide with a single pointer line to `CONTRIBUTING.md`.

Depends on: `01-rename-output-flags` (README flag references must match renamed code) and `02-extract-contributing` (CONTRIBUTING.md must exist before README references it).

Target section order:
1. Title + elevator pitch
2. Table of contents
3. Requirements
4. Build and install
5. Quick Start (with file-types callout + search-vs-query sentence)
6. Keeping the store up to date (new)
7. Commands (core commands)
8. Hybrid search
9. Search-specific flags
10. Global flags
11. Providers
12. API keys
13. Configuration file
14. Store
15. RAG settings
16. Using ez-rag with agentic coding tools
17. Advanced (Reranking, Output format, MCP Server, eval)
18. See CONTRIBUTING.md

### Implementation steps

- [x] Add a two-sentence elevator pitch and one-line RAG explanation immediately after the title
- [x] Add a table of contents near the top with anchor links to all major sections
- [x] In Quick Start, add an inline callout listing supported ingest file types (`.txt`, `.pdf`, `.md`, `.html`/`.htm`)
- [x] In Quick Start, add a sentence distinguishing `search` (returns raw chunks) from `query` (passes chunks to an LLM to generate an answer)
- [x] Add a new "Keeping the store up to date" section describing the `list` → `reingest` workflow
- [x] Reorder sections to match the target order above, moving "Using ez-rag with agentic coding tools" from its current early position to after "RAG settings"
- [x] Add an "Advanced" heading grouping Reranking, Output format, MCP Server, and eval
- [x] In the MCP server JSON example, change `"--store"` to `"--store-dir"`
- [x] In the Configuration file section, ensure `rerank-model` (and all other keys) are commented out with their default values shown
- [x] Replace the "Developer guide" section with a single line pointing to `CONTRIBUTING.md`
- [x] Remove any flag descriptions in the Commands section that reference `--output` (for search/show/chunk) or `--format` (for eval); replace with `--output-format`

### Acceptance criteria

- [x] The first visible content after the README title is a two-sentence description of ez-rag plus a one-line explanation of RAG
- [x] A table of contents appears near the top with anchor links to all major sections
- [x] The Quick Start section mentions `.txt`, `.pdf`, `.md`, `.html`/`.htm` as supported ingest file types
- [x] The Quick Start section contains a sentence explaining `search` returns raw chunks while `query` generates an LLM answer
- [x] A section named "Keeping the store up to date" exists and describes the `list` → `reingest` workflow
- [x] The "Commands" section appears before the "Using ez-rag with agentic coding tools" section
- [x] The "Using ez-rag with agentic coding tools" section appears after the "Global flags" section
- [x] `eval` and MCP server appear under an "Advanced" section that follows the "Using ez-rag with agentic coding tools" section
- [x] The config file sample has `rerank-model` commented out
- [x] The MCP JSON example uses `"--store-dir"` (not `"--store"`)
- [x] The "Developer guide" section is replaced by a single pointer line referencing `CONTRIBUTING.md`
- [x] The README contains no `--output` flag description for `show`, `chunk`, or `search`; no `--format` flag description for `eval`

### Quality gates

- [x] README is valid Markdown with no unclosed code fences
- [x] All anchor links in the table of contents resolve to actual headings in the document
