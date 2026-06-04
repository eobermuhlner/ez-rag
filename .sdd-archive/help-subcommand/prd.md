# PRD: `help` Subcommand

## Problem Statement

Users of `ez-rag` have no single command to see a comprehensive reference of every subcommand at once. The existing `--help` flag on the root command shows only a brief one-line summary per subcommand. To understand what a specific subcommand does and what options it accepts, the user must run `ez-rag <subcommand> --help` for each one individually. There is also no way to look up a subcommand's help by name without already knowing its exact name.

## Solution

Add an `ez-rag help` subcommand that, when invoked without arguments, prints a short general description of the tool followed by the full help text of every subcommand, separated by `---` dividers. When invoked with a subcommand name (`ez-rag help <subcommand>`), it prints only the full help text for that subcommand. This gives users a single entry point for complete reference documentation.

## User Stories

1. As a new user, I want to run `ez-rag help` and see a description of what ez-rag does, so that I can quickly understand the tool's purpose.
2. As a new user, I want `ez-rag help` to print all subcommand help texts in one output, so that I can read complete reference documentation without running multiple commands.
3. As a user, I want each subcommand's help block to be visually separated by a `---` divider, so that I can easily distinguish where one subcommand's help ends and the next begins.
4. As a user, I want the general tool description in `ez-rag help` to be the same text shown by `ez-rag --help`, so that the information is consistent and not duplicated.
5. As a user, I want to run `ez-rag help <subcommand>` to print only the help for that one subcommand, so that I can quickly look up a specific command without scrolling through everything.
6. As a user, I want `ez-rag help init` to show the same help text as `ez-rag init --help`, so that I get consistent information regardless of how I ask for it.
7. As a user, I want `ez-rag help ingest` to show the ingest subcommand's full help including all options, so that I know which flags to use.
8. As a user, I want `ez-rag help query` to show the query subcommand's full help, so that I know how to ask questions against my RAG store.
9. As a user, I want `ez-rag help search` to show the search subcommand's full help, so that I can learn about the available search options.
10. As a user, I want `ez-rag help status` to show the status subcommand's full help, so that I can see which output formats are supported.
11. As a user, I want `ez-rag help shell` to show the shell subcommand's help, so that I understand the interactive mode.
12. As a user, I want `ez-rag help mcp-server` to show the mcp-server subcommand's help, so that I can configure the MCP server correctly.
13. As a user, I want `ez-rag help delete` to show the delete subcommand's help, so that I know how to remove documents.
14. As a user, I want `ez-rag help list` to show the list subcommand's help, so that I know how to enumerate ingested documents.
15. As a user, I want `ez-rag help show` to show the show subcommand's help, so that I know how to inspect a specific document.
16. As a user, I want `ez-rag help re-ingest` to show the re-ingest subcommand's help, so that I understand how to refresh ingested content.
17. As a user, I want `ez-rag help chunk` to show the chunk subcommand's help, so that I can understand chunking options.
18. As a user, I want `ez-rag help eval` to show the eval subcommand's help, so that I can run evaluations correctly.
19. As a user, I want `ez-rag help download-eval-corpus` to show its help, so that I know how to download evaluation data.
20. As a user, I want `ez-rag help install-skill` to show its help, so that I know how to install the AI coding skill.
21. As a user, I want `ez-rag help` to appear in the list of subcommands shown by `ez-rag --help`, so that I can discover it naturally.
22. As a user, I want `ez-rag help --help` to work (standard PicoCLI behavior), so that the `help` command itself is self-describing.

## Implementation Decisions

- **New command class**: A new `HelpCommand` is added in the `command` package, following the same conventions as all other command classes.
- **Spring component**: `HelpCommand` is annotated with `@Component` and `@Command(name = "help", ...)` like every other command.
- **Callable vs Runnable**: `HelpCommand` implements `Callable<Int>` (return value 0 on success) to match the convention of all other commands in the project.
- **Output writer**: The command accepts a `PrintWriter` constructor parameter (defaulting to `PrintWriter(System.out, true)`) so that tests can inject a `StringWriter`-backed writer without Spring context — the same pattern used by `StatusCommand`, `ListCommand`, etc.
- **PicoCLI spec access**: The command declares a `@Spec` field of type `CommandLine.Model.CommandSpec` to access its parent command's spec and iterate its registered subcommands.
- **No-arg behavior**: When called without a subcommand name argument, the command:
  1. Renders the root command's full help text (which contains the existing tool description from `@Command`).
  2. Iterates all sibling subcommands registered on the parent spec in declaration order.
  3. Renders each subcommand's full help text.
  4. Prints a `---` separator line between the root help block and the first subcommand block, and between each pair of consecutive subcommand blocks.
- **Single-arg behavior**: When called with one positional argument (a subcommand name), the command looks up that name in the parent's subcommand map and renders only that subcommand's full help text. If the name is not found, it prints an error message to stderr and returns exit code 1.
- **Subcommand registration**: `HelpCommand::class` is added to the `subcommands` list in `EzRagCommand`'s `@Command` annotation, after the existing subcommands.
- **Help rendering**: PicoCLI's built-in `CommandLine.Help` and `CommandLine.Help.Ansi.AUTO.string(...)` are used to render each subcommand's full help text into a string, which is then written to the output writer.
- **`help` excluded from its own listing**: When iterating sibling subcommands in the no-arg case, the `help` subcommand itself is excluded to avoid circular/redundant output.

## Testing Decisions

**What makes a good test**: Tests exercise observable output — the text written to the `PrintWriter` and the exit code returned. Tests do not assert on internal state, which subcommands were iterated, or how PicoCLI rendering works internally.

**Module under test**: `HelpCommand` is the primary subject. `SubcommandTest` is also updated to verify that `help` appears in the root `--help` listing.

**Prior art**: All direct command tests (e.g., `StatusCommandTest`, `ListCommandTest`, `InitCommandTest`) instantiate the command class directly with a `StringWriter`-backed `PrintWriter`, call `call()` or use `CommandLine(EzRagCommand()).execute(...)`, and assert on the string output. `HelpCommandTest` follows the same pattern.

**Tests to write**:
- `ez-rag help` exits with code 0.
- `ez-rag help` output starts with the root command's description text (`"A command-line tool for RAG"`).
- `ez-rag help` output contains the full help text for every registered subcommand (assert on the presence of each subcommand name in the output).
- `ez-rag help` output contains `---` separator(s).
- `ez-rag help` output does not include a block for the `help` subcommand itself.
- `ez-rag help <subcommand>` exits with code 0 for each known subcommand.
- `ez-rag help <subcommand>` output contains the subcommand name and at least one option from that subcommand's help.
- `ez-rag help <unknown>` exits with a non-zero code.
- `ez-rag help <unknown>` writes an error message to stderr.
- `SubcommandTest`: `--help` on root command output contains `help` in the subcommand list.

## Out of Scope

- Paging or interactive scrolling of the help output.
- Colored/ANSI-styled output (follows whatever PicoCLI's `Ansi.AUTO` decides based on the terminal).
- `help` accepting multiple subcommand names in one invocation.
- Markdown or HTML rendering of help text.
- Any changes to the `--help` flag behavior on the root command or existing subcommands.

## Further Notes

- The `@Spec` injection is a standard PicoCLI mechanism: PicoCLI injects a `CommandSpec` before `call()` is invoked, even in non-Spring test contexts, as long as the command is executed through a `CommandLine` instance.
- Because `HelpCommand` does not interact with the file system, the embedding model, or any Spring-managed service, it can be fully tested with a plain `CommandLine(EzRagCommand())` setup (no Spring context, no `@TempDir`), keeping the tests fast and hermetic.
