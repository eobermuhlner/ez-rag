# Tasks: Shell Conversation History

## Task 01-core-multi-turn-prompt

Extend the domain model and `RagPipeline` so that a non-empty `conversationHistory` in `RagQuery` is included in the LLM prompt as alternating `UserMessage`/`AssistantMessage` turns before the current turn. Update the default system prompt to acknowledge multi-turn context.

### Implementation steps

- [x] Write failing test: with 1 history turn, a capturing `ChatModel` stub (not `PassthroughChatModel`) verifies prompt message count, order, and content — use a real document in the store so retrieval succeeds and the pipeline reaches the LLM call
- [x] Add `ConversationTurn(userQuestion: String, assistantAnswer: String)` data class to the domain model (no Spring AI imports)
- [x] Add `conversationHistory: List<ConversationTurn> = emptyList()` to `RagQuery`
- [x] Update `RagPipeline.query()` to insert history as alternating `UserMessage`/`AssistantMessage` pairs after `SystemMessage` and before the current `UserMessage`
- [x] Extend the default system prompt constant with one sentence acknowledging multi-turn context
- [x] Make tests pass

### Acceptance criteria

- [x] Empty `conversationHistory` produces the same prompt structure as before: `[SystemMessage, UserMessage]`
- [x] With N history turns the prompt contains exactly `2N+2` messages, in order: `[System, User₁, Asst₁, …, UserN, AsstN, UserCurrent]`
- [x] Each history `UserMessage` contains only the prior question text — no RAG context documents
- [x] The current `UserMessage` still contains the retrieved RAG context documents and the current question
- [x] The default system prompt string contains the new multi-turn sentence
- [x] All existing callers of `RagQuery` (query sub-command, MCP tool) compile unchanged — `conversationHistory` has a default value

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass

---

## Task 02-shell-history-accumulation

`ShellCommand` maintains an in-memory `MutableList<ConversationTurn>` across the REPL session and passes it to `RagPipeline` via `RagQuery`. Successful turns are appended; errored turns are not.

### Implementation steps

- [x] Write failing test: after one successful turn, the second `RagQuery.conversationHistory` has size 1 with the correct question and answer
- [x] Write failing test: after two successful turns, the third `RagQuery.conversationHistory` has size 2
- [x] Write failing test: when turn 1 throws an exception, turn 2's `RagQuery.conversationHistory` is empty
- [x] Initialise an empty `MutableList<ConversationTurn>` at the start of the REPL loop in `ShellCommand.call()`
- [x] Pass `history.toList()` as `conversationHistory` in every `RagQuery` construction
- [x] Append `ConversationTurn(question, answer)` only inside the success path (after `ragPipeline.query()` returns without throwing)
- [x] Make tests pass

### Acceptance criteria

- [x] After one successful turn, the second `RagQuery` has `conversationHistory.size == 1` with the exact question and answer from turn 1
- [x] After two successful turns, the third `RagQuery` has `conversationHistory.size == 2`
- [x] When turn 1 throws an exception, turn 2's `conversationHistory` is empty
- [x] Slash command turns (`/search`, `/search-bm25`, `/search-embedding`) do not add any entries to the history

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass

---

## Task 03-clear-command

Add a `/clear` slash command that resets the conversation history and prints a confirmation message. Update `/help` to list `/clear`. Update the existing `/help` test that asserts an exact command count.

### Implementation steps

- [x] Write failing test: after two successful turns, `/clear` causes the next `RagQuery` to have empty `conversationHistory`
- [x] Write failing test: `/clear` prints `"conversation history cleared"` to stdout
- [x] Write failing test: `/help` output contains `/clear`
- [x] Update the existing Test 11 (`slash help prints all five command names`) to expect six commands now that `/clear` is added
- [x] Add `history: MutableList<ConversationTurn>` as an explicit parameter to `handleSlashCommand` so the `/clear` case can call `history.clear()`
- [x] Implement the `/clear` case in `handleSlashCommand`: clear the list and print `"conversation history cleared"` to stdout
- [x] Update the `/help` case in `handleSlashCommand` to include the `/clear` line
- [x] Update the `call()` method to pass the history list when invoking `handleSlashCommand`
- [x] Make tests pass

### Acceptance criteria

- [x] After two successful turns, `/clear` causes the next `RagQuery.conversationHistory` to be empty
- [x] `/clear` prints `"conversation history cleared"` to stdout
- [x] `/clear` on an already-empty history completes without error and still prints the confirmation
- [x] `/help` output contains the string `/clear`

### Quality gates

- [x] Project compiles with no errors or warnings
- [x] All pre-existing tests pass (including the updated `/help` test)
