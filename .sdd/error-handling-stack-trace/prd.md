# PRD: User-Friendly Error Messages with `--stack-trace` Option

## Problem Statement

When an unexpected exception occurs in ez-rag, the CLI prints a raw Java stacktrace
directly to the terminal. For a command-line tool, this is noisy and confusing: the
user sees dozens of internal framework lines when all they need is a plain error
message. There is no way to suppress the stacktrace for normal use while still
making full details available when debugging.

## Solution

Catch all unhandled exceptions at the top-level picocli execution boundary and
replace the raw Java stacktrace with a concise, user-readable error message.
Add a global `--stack-trace` flag that, when set, restores the full stacktrace
output for debugging purposes. A hint pointing to `--stack-trace` is always
printed so users know how to get more details.

## User Stories

1. As a CLI user, I want unexpected errors to show a single clean error message,
   so that I am not overwhelmed by a raw Java stacktrace.
2. As a CLI user, I want the error message to contain the exception's description,
   so that I can understand what went wrong without reading framework internals.
3. As a CLI user, I want to be told how to get more details when an error occurs,
   so that I know to re-run with `--stack-trace` when I need to dig deeper.
4. As a CLI user, I want to pass `--stack-trace` before the subcommand
   (e.g. `ez-rag --stack-trace query "…"`), so that it works consistently with
   other global flags like `--verbose`.
5. As a CLI user, I want to pass `--stack-trace` after the subcommand
   (e.g. `ez-rag query --stack-trace "…"`), so that I can add it without
   restructuring the whole command line.
6. As a CLI user, I want the exit code to be `1` on unexpected errors,
   so that scripts can detect failures without distinguishing error types.
7. As a CLI user encountering an exception with no message (e.g. a
   `NullPointerException`), I want to see the exception class name in the error
   output, so that I have at least a minimal clue to work with.
8. As a developer, I want the full stacktrace printed to stderr when `--stack-trace`
   is set, so that I can diagnose bugs without adding extra logging.
9. As a developer, I want existing per-command `exitWithError()` messages to be
   unaffected by this change, so that clean handled-error paths remain as they are.
10. As a developer, I want `--stack-trace` to be listed in `--help` output alongside
    `--verbose`, so that it is discoverable.

## Implementation Decisions

- **Scope:** Only unhandled exceptions that escape a picocli command's `call()` method
  are intercepted. Existing per-command `exitWithError()` calls are not modified.

- **Global exception handler:** A picocli `IExecutionExceptionHandler` is registered
  on the `CommandLine` instance inside `EzRagApplication.run()`. This is the single
  place where all unhandled exceptions are caught.

- **Handler behaviour:**
  - Print `Error: <exception message>` to stderr.
  - If the exception message is `null` or blank, print
    `Error: <ExceptionClassName> (no message)` instead.
  - Always follow with: `Use --stack-trace for full details.`
  - If `--stack-trace` is `true` on the root command, also print the full stacktrace
    to stderr.
  - Return exit code `1`.

- **`--stack-trace` option:** Added to `EzRagCommand` as a `Boolean` option with
  `scope = ScopeType.INHERIT`, matching the pattern of the existing `--verbose` option.
  This makes it available in both `ez-rag --stack-trace <subcmd>` and
  `ez-rag <subcmd> --stack-trace` positions.

- **Output stream:** Error output goes to the `CommandLine` error writer (stderr),
  consistent with how picocli routes parse errors.

- **Exit code:** `1` for all unhandled exceptions, consistent with existing
  `exitWithError()` convention. No new exit code is introduced.

## Testing Decisions

A good test asserts only observable external behaviour — what the user sees (error
message text, exit code, stacktrace presence/absence) — not internal implementation
details (which class catches the exception, how the handler is wired).

**Module under test:** The `IExecutionExceptionHandler` logic, exercised through
`CommandLine.execute()` without Spring. A test-only subcommand that throws a
configurable exception on demand serves as the trigger.

**Prior art:** `SubcommandTest` and `EzRagCommandTest` demonstrate the pattern:
construct `CommandLine(EzRagCommand())`, wire `StringWriter` instances as out/err,
call `execute(…)`, assert on the captured strings and return code.

**Test cases:**
- Without `--stack-trace`: error message contains "Error: <message>", contains
  the `--stack-trace` hint, does not contain a stacktrace, exit code is `1`.
- With `--stack-trace` before subcommand: error message contains the stacktrace.
- With `--stack-trace` after subcommand (`ScopeType.INHERIT`): same as above.
- Exception with `null` message: error message contains the exception class name
  and "(no message)".
- `--stack-trace` flag appears in `--help` output.

## Out of Scope

- Modifying existing per-command `exitWithError()` calls or adding stacktrace
  support to handled errors.
- Changing exit codes for any existing error path.
- Adding `--stack-trace` support to the MCP server command (MCP tools return
  structured error results; they do not propagate exceptions to the CLI layer).
- Integrating `--stack-trace` with the `--verbose` / logging system.

## Further Notes

- The `--verbose` flag (already `ScopeType.INHERIT`) serves as the exact template
  for the `--stack-trace` declaration in `EzRagCommand`.
- Picocli's `IExecutionExceptionHandler` receives the parsed command spec, so the
  handler can read `ezRagCommand.stackTrace` directly from the root command object.
- The hint `Use --stack-trace for full details.` should be printed even when
  `--stack-trace` is already set, as it costs nothing and keeps the output format
  consistent.
