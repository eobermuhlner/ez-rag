# Tasks: `help` Subcommand

## Task [01-help-full-reference]

Wire a new `HelpCommand` that responds to `ez-rag help` (no arguments) by printing: the root command's full help text, a `---` separator, then each sibling subcommand's full help text with `---` separators between them. The `help` subcommand itself is excluded from the listing. Register the command in `EzRagCommand`. Follow TDD: write failing tests before adding implementation.

### Implementation steps

- [x] Write failing tests: `ez-rag help` exits 0, output contains root description text, output contains each sibling subcommand name, output contains `---` separators, `help`'s own usage line does not appear among the blocks.
- [x] Create a `HelpCommand` stub (annotated with `@Command(name = "help")` and `@Component`, registered in `EzRagCommand`) that makes tests compile but still fail.
- [x] Implement `HelpCommand.call()` using a `@Spec`-injected `CommandLine.Model.CommandSpec` to render the parent command's help, then each sibling subcommand's help, with `---` separators. Exclude the `help` subcommand itself from iteration.
- [x] Add a test to `SubcommandTest` verifying that `ez-rag --help` output contains `help` in the subcommand listing.
- [x] Make all new tests pass.

### Acceptance criteria

- [x] `ez-rag help` exits with code 0.
- [x] Output of `ez-rag help` contains the text "A command-line tool for RAG".
- [x] Output of `ez-rag help` contains each of the following subcommand names: `init`, `ingest`, `chunk`, `reingest`, `delete`, `list`, `show`, `query`, `search`, `status`, `mcp-server`, `shell`, `eval`, `download-eval-corpus`, `install-skill`.
- [x] Output of `ez-rag help` contains at least two occurrences of `---`.
- [x] Output of `ez-rag help` does not contain the line `Usage:  ez-rag help` (the help command must not list itself as a block).
- [x] Output of `ez-rag --help` contains `help` in the subcommand listing.

### Quality gates

- [x] Project compiles without errors (`./gradlew compileKotlin compileTestKotlin`).
- [x] All pre-existing tests continue to pass (`./gradlew test`).

---

## Task [02-help-targeted-subcommand]

Extend `HelpCommand` so that `ez-rag help <subcommand>` prints only the full help text for the named subcommand. An unknown name writes an error message to stderr that contains the unknown name, and exits with code 1. Follow TDD: write failing tests before extending the implementation.

### Implementation steps

- [x] Write failing tests for the known-name case: `ez-rag help ingest` exits 0 and output contains `ingest` and at least one of its options. Also test `ez-rag help status` similarly. Wire `commandLine.err = PrintWriter(errWriter)` alongside `commandLine.out` in test setup to capture stderr separately.
- [x] Write a failing test for the unknown-name case: `ez-rag help no-such-command` exits non-zero and the captured stderr contains `no-such-command`.
- [x] Add an optional `@Parameters(arity = "0..1")` field for the subcommand name to `HelpCommand`.
- [x] Implement the lookup-and-render branch: look up the name in the parent spec's subcommand map and render that subcommand's full help text to the output writer.
- [x] Implement the not-found error branch: write an error message containing the unknown name to the error writer and return exit code 1.
- [x] Make all new tests pass.

### Acceptance criteria

- [x] `ez-rag help ingest` exits 0 and output contains `ingest` and `--store-dir`.
- [x] `ez-rag help status` exits 0 and output contains `status` and `--output-format`.
- [x] `ez-rag help query` exits 0 and output contains `query`.
- [x] `ez-rag help no-such-command` exits with a non-zero code.
- [x] `ez-rag help no-such-command` writes to stderr and the message contains `no-such-command`.

### Quality gates

- [x] Project compiles without errors (`./gradlew compileKotlin compileTestKotlin`).
- [x] All pre-existing tests and task-01 tests continue to pass (`./gradlew test`).
