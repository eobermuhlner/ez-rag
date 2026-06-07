## Task [01-search-positional-question]

SearchCommand accepts the question as positional arguments, joining multiple unquoted tokens with a single space. If no positional tokens are given, the command falls back to reading from stdin. The `--question`/`-q` named option is removed entirely.

### Implementation steps

- [x] Write a failing unit test: positional words joined into single question string (set `cmd.questionArgs = listOf("What", "is", "X?")`, assert pipeline receives `"What is X?"`)
- [x] Write a failing unit test: absence of positional args reads stdin until EOF and uses as question
- [x] Write a failing unit test: empty stdin with no positional args exits code 1 with non-empty error message
- [x] Write a failing picocli-level test: `commandLine.execute("search", "What", "is", "X?")` exits without USAGE error code
- [x] Replace `@Option(names = ["--question", "-q"]) var question: String?` with `@Parameters(index = "0..*") var questionArgs: List<String> = emptyList()`
- [x] Update question resolution: `if (questionArgs.isNotEmpty()) questionArgs.joinToString(" ") else readFromStdin()`
- [x] Update all existing SearchCommandTest field assignments from `cmd.question = "foo"` to `cmd.questionArgs = listOf("foo")`

### Acceptance criteria

- [x] `cmd.questionArgs = listOf("What", "is", "X?")` causes the pipeline to receive the question `"What is X?"`
- [x] `cmd.questionArgs = listOf("What is X?")` (single quoted token) causes the pipeline to receive `"What is X?"`
- [x] `cmd.questionArgs = emptyList()` with stdin `"What is X?"` causes the pipeline to receive `"What is X?"`
- [x] `cmd.questionArgs = emptyList()` with empty stdin exits code 1 with a non-empty error message
- [x] `commandLine.execute("search", "--top-k", "3", "What", "is", "X?")` exits without USAGE exit code (flags before positional tokens are parsed separately)
- [x] `commandLine.execute("search", "--help")` output mentions the positional question argument
- [x] `commandLine.execute("search", "--question", "foo")` exits with `CommandLine.ExitCode.USAGE` (option removed)

### Quality gates

- [x] No compiler warnings or errors
- [x] All SearchCommandTest tests pass, including existing stdin and error-path tests, after updating `question` → `questionArgs` field references

---

## Task [02-query-positional-question]

QueryCommand accepts the question as positional arguments, joining multiple unquoted tokens with a single space. If no positional tokens are given, the command falls back to reading from stdin. The `--question`/`-q` named option is removed entirely.

### Implementation steps

- [x] Write a failing unit test: positional words joined into single question string (set `cmd.questionArgs = listOf("Summarize", "the", "architecture")`, assert pipeline receives `"Summarize the architecture"`)
- [x] Write a failing unit test: absence of positional args reads stdin until EOF and uses as question
- [x] Write a failing unit test: empty stdin with no positional args exits code 1 with non-empty error message
- [x] Write a failing picocli-level test: `commandLine.execute("query", "who", "are", "you?")` exits without USAGE error code
- [x] Replace `@Option(names = ["--question", "-q"]) var question: String?` with `@Parameters(index = "0..*") var questionArgs: List<String> = emptyList()`
- [x] Update question resolution: `if (questionArgs.isNotEmpty()) questionArgs.joinToString(" ") else readFromStdin()`
- [x] Update all existing QueryCommandTest field assignments from `cmd.question = "foo"` to `cmd.questionArgs = listOf("foo")`

### Acceptance criteria

- [x] `cmd.questionArgs = listOf("Summarize", "the", "architecture")` causes the pipeline to receive `"Summarize the architecture"`
- [x] `cmd.questionArgs = listOf("Summarize the architecture")` (single quoted token) causes the pipeline to receive `"Summarize the architecture"`
- [x] `cmd.questionArgs = emptyList()` with stdin `"Summarize the architecture"` causes the pipeline to receive `"Summarize the architecture"`
- [x] `cmd.questionArgs = emptyList()` with empty stdin exits code 1 with a non-empty error message
- [x] `commandLine.execute("query", "--top-k", "2", "Who", "is", "the", "author?")` exits without USAGE exit code (flags before positional tokens parsed separately)
- [x] `commandLine.execute("query", "--help")` output mentions the positional question argument
- [x] `commandLine.execute("query", "--question", "foo")` exits with `CommandLine.ExitCode.USAGE` (option removed)

### Quality gates

- [x] No compiler warnings or errors
- [x] All QueryCommandTest tests pass, including existing stdin, error-path, and option-forwarding tests, after updating `question` → `questionArgs` field references

---

## Task [03-integration-tests-and-docs]

Replace the two SubcommandTest tests that verified `--question` flag acceptance with equivalent tests using positional syntax, add negative assertions that `--question` is now rejected, and update README usage examples to show positional syntax throughout.

### Implementation steps

- [x] In SubcommandTest: replace `query accepts --question flag` with `query accepts positional question` using `commandLine.execute("query", "who", "are", "you?")`
- [x] In SubcommandTest: add `query rejects --question flag` asserting `commandLine.execute("query", "--question", "who are you?")` returns `CommandLine.ExitCode.USAGE`
- [x] In SubcommandTest: replace `search accepts --question flag` with `search accepts positional question` using `commandLine.execute("search", "who", "are", "you?")`
- [x] In SubcommandTest: add `search rejects --question flag` asserting `commandLine.execute("search", "--question", "who are you?")` returns `CommandLine.ExitCode.USAGE`
- [x] Update README Quick start section: replace `--question` / `-q` examples with positional syntax
- [x] Update README Commands table: remove `--question`/`-q` row from search and query flag tables; add positional argument description

### Acceptance criteria

- [x] SubcommandTest contains no reference to `--question` in test names or execute calls (grep finds zero matches)
- [x] `commandLine.execute("query", "who", "are", "you?")` exits with code other than `CommandLine.ExitCode.USAGE`
- [x] `commandLine.execute("query", "--question", "who are you?")` exits with `CommandLine.ExitCode.USAGE`
- [x] `commandLine.execute("search", "who", "are", "you?")` exits with code other than `CommandLine.ExitCode.USAGE`
- [x] `commandLine.execute("search", "--question", "who are you?")` exits with `CommandLine.ExitCode.USAGE`
- [x] README Quick start examples use positional syntax (no `--question` or `-q` flags present)
- [x] README flags table for `search` and `query` commands does not list `--question`/`-q`

### Quality gates

- [x] No compiler warnings or errors
- [x] All SubcommandTest tests pass
