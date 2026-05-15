## Problem Statement

When exploring a document corpus interactively, running `ez-rag query` repeatedly incurs JVM startup overhead on each invocation and reloads the vector store from disk every time. Users doing exploratory Q&A sessions want a persistent interactive mode where they can ask multiple questions in sequence without per-query startup cost.

## Solution

Implement the `shell` subcommand. When invoked, `ez-rag shell` starts an interactive REPL that loads the vector store once, reads questions line by line from stdin (with a prompt displayed to stderr), runs each question through the RAG pipeline, prints the answer and sources, and loops until the user types `exit`, `quit`, or sends EOF.

## User Stories

1. As a user, I want to run `ez-rag shell` and get an interactive prompt, so that I can ask multiple questions without restarting the tool.
2. As a user, I want the vector store loaded once at REPL startup, so that subsequent queries are fast.
3. As a user, I want to type a question and press Enter to get an answer, so that the interaction feels natural.
4. As a user, I want to type `exit` or `quit` to cleanly exit the REPL, so that I have an explicit way to stop.
5. As a user, I want Ctrl+D (EOF) to exit the REPL cleanly, so that the tool behaves like a standard Unix interactive program.
6. As a user, I want the prompt (`> `) displayed on stderr so it doesn't pollute stdout when piping output, so that I can still use `ez-rag shell` in scripts.
7. As a user, I want `--provider`, `--embedding-provider`, `--model`, `--top-k`, and `--store` flags to work with `shell` the same way they work with `query`, so that I can configure the REPL session.
8. As a user, I want to type `/help` in the REPL to see available commands, so that I can discover REPL-specific commands.
9. As a user, I want to type `/status` in the REPL to see vector store info without exiting, so that I can check the store state during exploration.
10. As a user, I want to type `/verbose` to toggle verbose mode on/off during a session, so that I can inspect retrieval details for a specific question.
11. As a user, I want blank lines ignored rather than treated as empty queries, so that accidental Enter presses don't produce unhelpful responses.
12. As a user, I want errors (e.g., LLM API failure) on a single question to print an error message and continue the REPL rather than exiting, so that one bad query doesn't end the session.

## Implementation Decisions

- **ShellCommand**: A picocli `@Command` that starts the REPL loop. Reuses `RagPipeline`, `VectorStoreRepository`, and `OutputFormatter` from PRDs 02–04 — no new core logic.
- **REPL loop**: Uses `java.io.BufferedReader` on `System.in`. Prompt string (`> `) is written to `System.err`. EOF from `System.in` exits cleanly. Exceptions from `RagPipeline` are caught per iteration, printed to stderr, and the loop continues.
- **REPL commands**: Lines starting with `/` are treated as REPL meta-commands (`/help`, `/status`, `/verbose`, `/exit`). All other lines are passed to `RagPipeline` as questions.
- **No readline/history**: No dependency on JLine or similar in this PRD. Basic `BufferedReader` is sufficient for the initial implementation. JLine can be added as a follow-up for history and completion.
- **Output format**: Answers are always printed to stdout. The prompt and error messages go to stderr. `--output json` applies to each answer, same as in `query`.

## Testing Decisions

- **What makes a good test**: The REPL loop logic is thin — it reads input, delegates to `RagPipeline`, and writes output. Test the delegation, not the loop itself.
- **ShellCommand**: Unit-test with a mock `RagPipeline`. Provide a sequence of input lines via a `ByteArrayInputStream`. Assert that `RagPipeline.query()` is called for each non-blank, non-command line and not called for blank lines or `/`-commands.
- **Error resilience**: Test that a `RagPipeline` exception on one call does not prevent subsequent calls from being processed.
- **No interactive terminal tests**: Tests do not use a real TTY.

## Out of Scope

- Command history and line editing (JLine integration).
- Tab completion for REPL commands.
- Multi-line question input within the REPL.
- Session-level context / follow-up questions that reference previous answers.
- This is a secondary use case; it should be implemented after PRDs 01–05 are stable.

## Further Notes

The `shell` subcommand adds user-facing value with minimal new logic — it is essentially a loop around the existing `query` pipeline. Its main constraint is correct stdio/stderr separation so that the prompt does not pollute piped output. Keeping the implementation thin here also makes it easy to layer in JLine later for a polished readline experience.
