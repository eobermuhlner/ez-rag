# PRD: Shell Conversation History

## Problem Statement

The `shell` sub-command provides an interactive REPL for querying a RAG-backed knowledge base. Each question is treated as an isolated request: the LLM receives no memory of prior exchanges. This means users cannot ask natural follow-up questions like "Can you elaborate on point 2?" or "What about in the context of X?" without repeating all relevant background. The lack of conversational continuity makes the shell feel like a stateless lookup tool rather than an interactive assistant.

## Solution

Maintain an in-memory conversation history across the REPL session. Each time the user receives an answer, the question-and-answer pair is appended to the history. On the next question, the history is included in the LLM prompt as alternating user/assistant message turns before the current turn. A `/clear` command lets the user reset the history mid-session when they want to start an unrelated conversation without restarting the REPL.

RAG retrieval (vector search) remains per-turn: every question, including follow-ups, triggers a fresh search so that the retrieved context always matches the current question.

## User Stories

1. As a shell user, I want my follow-up questions to be understood in the context of the previous answer, so that I can ask "why?" or "tell me more" without repeating myself.
2. As a shell user, I want to ask a clarifying question about a specific claim in the last answer, so that I can drill into details naturally.
3. As a shell user, I want to refer to entities mentioned in a prior answer by pronoun ("it", "they", "that approach"), so that I can have a fluent conversation.
4. As a shell user, I want the LLM to remember what it said earlier in the same session, so that its answers are internally consistent across turns.
5. As a shell user, I want each follow-up question to still retrieve fresh relevant documents from the knowledge base, so that the context always matches what I'm currently asking about.
6. As a shell user, I want to type `/clear` to reset the conversation history, so that I can start a completely new topic without restarting the REPL.
7. As a shell user, I want confirmation printed after `/clear`, so that I know the history was actually reset.
8. As a shell user, I want `/help` to list `/clear` as an available command, so that I can discover the feature without reading documentation.
9. As a shell user, I want the conversation history to be held only in memory for the current session, so that sensitive exchanges are not written to disk.
10. As a shell user, I want the existing `/exit`, `/quit`, and EOF behaviours to remain unchanged, so that my existing scripts and muscle memory continue to work.
11. As a shell user, I want errors in one turn (LLM failure, search failure) not to corrupt the history, so that the next question still has a coherent conversation context.
12. As a shell user, I want the `/search`, `/search-bm25`, and `/search-embedding` slash commands to remain unaffected by conversation history, so that raw search results are always direct and uncontaminated.
13. As a developer, I want `RagPipeline` to remain stateless, so that the `query` sub-command and MCP tool continue to work without changes.
14. As a developer, I want the conversation history domain type to be free of Spring AI dependencies, so that `RagModel` is independently testable.

## Implementation Decisions

### Module: `RagModel` (data classes)

- A new `ConversationTurn` value type is added:
  ```
  ConversationTurn(userQuestion: String, assistantAnswer: String)
  ```
- `RagQuery` gains a new field `conversationHistory: List<ConversationTurn>` defaulting to an empty list. All existing callers (query sub-command, MCP tool) pass no history and continue to work unchanged.

### Module: `RagPipeline`

- The default RAG system prompt is extended with one sentence acknowledging multi-turn context, e.g.: *"The conversation history shows earlier exchanges; you may refer to them when answering follow-up questions."*
- When building the `Prompt`, `RagPipeline.query()` inserts the history turns between the `SystemMessage` and the current `UserMessage`, as alternating `UserMessage` / `AssistantMessage` pairs.
- The current turn's `UserMessage` still contains the retrieved RAG context documents plus the current question — unchanged from today.
- `RagPipeline` itself remains stateless: it does not accumulate any state between calls.

### Module: `ShellCommand`

- A `MutableList<ConversationTurn>` is maintained as a local variable within the `call()` method's loop.
- After each successful `RagPipeline.query()` call, the question and answer are appended to the list as a `ConversationTurn`.
- On error (exception), the turn is **not** appended, so the history stays coherent.
- Each `RagQuery` is constructed with `conversationHistory = history.toList()`.
- A new `/clear` slash command clears the list and prints `"conversation history cleared"` to stdout.
- `/help` output is updated to include `/clear`.

### Prompt structure (per turn with history)

```
SystemMessage(ragSystemPrompt + multi-turn sentence)
UserMessage(historyTurn[0].userQuestion)       ← from history
AssistantMessage(historyTurn[0].assistantAnswer) ← from history
...repeated for each prior turn...
UserMessage(contextDocuments + currentQuestion) ← current turn
```

### No interaction with search-only slash commands

`/search`, `/search-bm25`, and `/search-embedding` call search pipelines directly and do not touch the conversation history list. They are unaffected.

## Testing Decisions

**What makes a good test here:** test only externally observable behaviour — what the pipeline receives (which messages, in which order) and what the shell outputs — not internal field assignments. Avoid asserting on private state.

### `RagPipelineTest`

- Verify that when `conversationHistory` contains N turns, the `Prompt` passed to `ChatModel` contains the correct number of messages in the correct order (System, User, Assistant, ..., User).
- Verify that the current `UserMessage` still contains the RAG context documents.
- Verify that an empty `conversationHistory` produces the same prompt structure as before (backward compatibility).
- Prior art: `RagPipelineTest.kt` already stubs `ChatModel` and asserts on `RagResult` fields; extend it with a `CapturingChatModel` that records the `Prompt` it receives.

### `ShellCommandTest`

- Verify that after one successful question-answer exchange, the second call to `RagPipeline.query()` receives a `RagQuery` with one `ConversationTurn` in its history (the first Q&A).
- Verify that after a pipeline exception on turn 1, turn 2's `RagQuery` has an empty history (failed turns are not recorded).
- Verify that `/clear` resets the history: a question after `/clear` has empty history even after prior successful turns.
- Verify that `/clear` prints `"conversation history cleared"` to stdout.
- Verify that `/help` output contains `/clear`.
- Verify that `/search` after a multi-turn session does not cause errors and does not pass history anywhere.
- Prior art: `ShellCommandTest.kt` already uses a stub `RagPipeline` with a `calls: MutableList<RagQuery>` capture pattern; extend it to assert on `calls[n].conversationHistory`.

## Out of Scope

- **Persistence**: history is not saved to disk between sessions.
- **History depth cap / token windowing**: no maximum turn count or token budget trimming.
- **Exposing history in the `query` sub-command**: single-shot `query` will not gain a history flag.
- **History in MCP tool**: the `McpQueryTool` will not gain conversation history.
- **Named sessions or session management**: no session IDs, save, load, or list commands.
- **Per-turn system prompt override for history turns**: the system prompt is set once per query, not per history turn.

## Further Notes

- Spring AI's `Prompt` accepts a `List<Message>` in arbitrary order, so injecting history as alternating `UserMessage`/`AssistantMessage` pairs is idiomatic and requires no special API.
- The `RagResult.userMessage` field (added for debugging purposes) captures the current turn's user content; this remains unchanged.
- The `PassthroughChatModel` short-circuit path in `RagPipeline` does not involve the LLM, so history has no effect there and can be ignored silently.
