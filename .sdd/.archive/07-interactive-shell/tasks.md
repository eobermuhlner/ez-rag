# Tasks: 07-interactive-shell

## Task [01-repl-loop]

The `shell` subcommand starts an interactive REPL that loads the vector store once at startup, reads questions line-by-line from stdin, delegates each non-blank non-`/`-prefixed line to `RagPipeline.query()`, and prints the answer to stdout. The prompt `> ` is written to stderr. Blank lines are silently skipped. Lines starting with `/` are silently skipped (not forwarded to the pipeline — actual dispatch is added in task 02). The loop exits on EOF, `exit`, or `quit`. A pipeline exception on one iteration prints the error to stderr and continues the loop. Flags `--store`, `--top-k`, and `--output` configure the session; `--verbose` and `--model` are inherited from the parent command.

### Implementation steps

- [x] Wire `ShellCommand` with injectable `RagPipeline`, `EmbeddingSearchPipeline`, `VectorStoreRepository`, `OutputFormatter`, `PrintWriter` (stdout/stderr), `InputStream`, and CLI flags (`--store`, `--top-k`, `--output`)
- [x] Load the vector store once at startup; exit with code 1 and stdout message containing "ingest" if the store file is missing
- [x] Implement the `BufferedReader` loop: print `> ` to stderr, read a line, handle EOF, blank lines, `exit`/`quit` keywords, and silently skip `/`-prefixed lines
- [x] For non-blank, non-`/`-prefixed lines, call `RagPipeline.query()` and format/print the result to stdout
- [x] Catch exceptions from `RagPipeline.query()`, print the error to stderr, and continue the loop

### Acceptance criteria

- [x] Given three non-blank questions as input lines, `RagPipeline.query()` is called exactly three times
- [x] Given input lines that include blank lines, `RagPipeline.query()` is NOT called for the blank lines
- [x] Given `exit` as input, the loop exits with return code 0 without calling `RagPipeline.query()`
- [x] Given `quit` as input, the loop exits with return code 0
- [x] Given EOF (empty input stream), the loop exits with return code 0
- [x] Given `/somecommand` as input, `RagPipeline.query()` is NOT called (the line is silently skipped)
- [x] Given a missing vector store file, `call()` returns 1 and stdout contains "ingest"
- [x] Given a `RagPipeline` that throws on the first call but succeeds on the second, the second call is still executed and its answer is printed to stdout
- [x] Given `--output json`, each answer is formatted as JSON (output starts with `{` and contains `"answer"`)
- [x] The `> ` prompt string is written to stderr and does NOT appear on stdout

### Quality gates

- [x] No Kotlin compiler warnings
- [x] `./gradlew test` passes with no failures

---

## Task [02-repl-slash-commands]

The REPL dispatches `/`-prefixed lines to meta-command handlers instead of silently skipping them. `/help` prints the list of available commands to stdout, `/status` prints vector store metadata to stdout, `/search <question>` runs a pure embedding search via `EmbeddingSearchPipeline` and formats results with `OutputFormatter`, `/verbose` toggles verbose mode and prints confirmation to stdout, `/exit` exits the loop cleanly. An unknown `/xxx` command prints an error to stderr and continues the loop. No `/`-prefixed line is ever forwarded to `RagPipeline.query()`.

### Implementation steps

- [x] Replace the silent skip of `/`-prefixed lines with a dispatch table
- [x] Implement `/exit` — exits the REPL with return code 0
- [x] Implement `/help` — prints to stdout the names and one-line descriptions of `/help`, `/status`, `/search`, `/verbose`, and `/exit`
- [x] Implement `/status` — calls `VectorStoreRepository.getMetadata()` and prints chunk count and document list to stdout
- [x] Implement `/search <question>` — builds a `SearchQuery` using the session's `topK` value, calls `EmbeddingSearchPipeline.search()`, and prints formatted results with `OutputFormatter`
- [x] Implement `/verbose` — toggles the verbose flag; prints "verbose on" to stdout when enabled and "verbose off" when disabled
- [x] For unknown `/xxx` commands, print an error message to stderr and continue the loop

### Acceptance criteria

- [x] Given `/help` as input, stdout contains all five command names: `/help`, `/status`, `/search`, `/verbose`, and `/exit`
- [x] Given `/exit` as input, the loop exits with return code 0 and `RagPipeline.query()` is never called
- [x] Given `/status` as input, stdout contains the chunk count as a number
- [x] Given `/search what is X` as input, `EmbeddingSearchPipeline.search()` is called with question `"what is X"` and the session's `topK` value, and `RagPipeline.query()` is NOT called
- [x] Given `/verbose` as input, stdout contains "verbose on"; given `/verbose` again, stdout contains "verbose off"
- [x] Given `/verbose` followed by a question that causes `RagPipeline` to return a `RagResult` with sources, source details are written to stderr (verbose mode active)
- [x] Given `/unknown` as input, an error message is written to stderr and the loop continues (does not exit)
- [x] A line starting with `/` is never passed to `RagPipeline.query()`

### Quality gates

- [x] No Kotlin compiler warnings
- [x] `./gradlew test` passes with no failures
