# Tasks: Error Handling with `--stack-trace`

## Task [01-global-exception-handler]

Register a picocli `IExecutionExceptionHandler` on the `CommandLine` instance so
that any exception escaping a subcommand's `call()` is caught at the picocli
boundary. Print a clean one-line error to stderr followed by a discovery hint.
Return exit code 1. This replaces the raw Java stacktrace the user currently sees.

Tests use a test-only subcommand that throws a configurable exception, wired to a
`CommandLine(EzRagCommand())` with `StringWriter` instances capturing stdout/stderr
— the same pattern used in `SubcommandTest`.

### Implementation steps

- [x] Write a failing test: exception with a message → stderr contains "Error: <message>", exit code is 1
- [x] Register `IExecutionExceptionHandler` on the `CommandLine` instance in `EzRagApplication.run()`; print the error line and return 1
- [x] Write a failing test: exception with a null/blank message → stderr contains the exception class name and "(no message)"
- [x] Implement the null/blank message fallback in the handler (blank = null, empty, or whitespace-only)
- [x] Write a failing test: hint line always appears in stderr
- [x] Implement printing of `Use --stack-trace for full details.` after the error line
- [x] Write a failing test: no stacktrace frame lines appear in stderr
- [x] Confirm the handler does not print a stacktrace by default

### Acceptance criteria

- [x] An unhandled exception with a message prints `Error: <message>` to stderr
- [x] An unhandled exception whose message is null, empty, or whitespace-only prints `Error: ExceptionClassName (no message)` to stderr
- [x] The line `Use --stack-trace for full details.` always appears in stderr immediately after the error line
- [x] No stacktrace frames appear in stderr (no lines containing `at ` followed by a class/method reference)
- [x] Exit code is `1` for all unhandled exceptions
- [x] Normal command execution (no exception thrown) is unaffected: exit codes and output for successful commands remain unchanged

### Quality gates

- [x] No new compiler warnings introduced
- [x] All pre-existing tests pass

---

## Task [02-stack-trace-flag]

Extends Task 01. Add a `--stack-trace` boolean option to `EzRagCommand` with
`ScopeType.INHERIT` so it is accepted both before and after the subcommand name.
When the flag is set, the exception handler prints the full stacktrace to stderr
after the hint line.

### Implementation steps

- [x] Write a failing test: `--stack-trace` appears in `--help` output
- [x] Add `--stack-trace` boolean option to `EzRagCommand` with `scope = ScopeType.INHERIT`, mirroring the `--verbose` declaration
- [x] Write a failing test: `ez-rag --stack-trace <subcmd>` → stderr contains stacktrace frames
- [x] Update the handler to check the root command's `stackTrace` field and print the full stacktrace after the hint when set
- [x] Write a failing test: `ez-rag <subcmd> --stack-trace` → stderr contains stacktrace frames (verifies `ScopeType.INHERIT`)
- [x] Write a failing test: hint still present when `--stack-trace` is set

### Acceptance criteria

- [x] `--stack-trace` appears in the top-level `--help` output
- [x] With `ez-rag --stack-trace <subcmd>`, stderr contains stacktrace frames (lines containing `at ` followed by a class reference) when an exception occurs
- [x] With `ez-rag <subcmd> --stack-trace`, stderr contains the same stacktrace frames (confirms `ScopeType.INHERIT`)
- [x] Without `--stack-trace`, no stacktrace frames appear in stderr
- [x] The hint `Use --stack-trace for full details.` appears after the error line even when `--stack-trace` is already set; the stacktrace follows the hint

### Quality gates

- [x] No new compiler warnings introduced
- [x] All pre-existing tests pass
