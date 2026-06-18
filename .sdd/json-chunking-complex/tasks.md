# Tasks: json-chunking

## Task 01-json-array-ingestion

Tracer bullet that cuts through all four modules for the most common case: a `.json` file whose root is a JSON array produces budget-batched Markdown chunks. `JsonChunker` handles `ArrayNode` batching with an oversized-element escape hatch; `JsonDocumentReader` orchestrates file → parse → chunk → `Document`; `DocumentReaderRegistry` dispatches `.json` files. Error paths (invalid JSON, empty file) are also established here.

### Implementation steps

- [x] Write failing tests in `JsonChunkerTest`: empty array → empty list; small array fits one chunk; budget batching across multiple chunks; no element appears in more than one chunk; single oversized element emitted alone; heading format `## Items {start}–{end}` / `## Item {index}` (1-based)
- [x] Implement `JsonChunker` — `ArrayNode` dispatch only (object/text/nested/primitive paths come in later tasks)
- [x] Write failing tests in `JsonDocumentReaderTest`: `.json` file with a root array produces non-empty `Document` list whose combined text contains all element content; empty `.json` file returns empty list; syntactically invalid `.json` throws `IllegalArgumentException` containing the filename
- [x] Implement `JsonDocumentReader`: read UTF-8, Jackson `ObjectMapper().readTree()`, call `JsonChunker`, wrap as `Document` list; handle empty-file and parse-error cases
- [x] Write failing test in `DocumentReaderRegistryTest`: `supports("json")` returns `true`; `registry.read(tempJsonFile)` returns non-empty (test creates an inline temp `.json` file via `@TempDir`)
- [x] Add `"json"` entry to `DocumentReaderRegistry`
- [x] Increment version in `gradle.properties` (minor bump — new user-visible extension)

### Acceptance criteria

- [x] `JsonChunker.chunk(emptyArrayNode)` returns an empty list
- [x] An array of small objects produces multiple chunks each within the configured token budget
- [x] No object appears in more than one chunk (non-overlapping batches)
- [x] A single element that alone exceeds the budget is emitted as its own chunk
- [x] Batch heading format is `## Items {start}–{end}` (1-based); a single-element batch uses `## Item {index}`
- [x] `JsonDocumentReader` on a `.json` array file produces `Document`s whose combined text contains every element's content
- [x] `JsonDocumentReader` on an empty `.json` file returns an empty `Document` list without throwing
- [x] `JsonDocumentReader` on a syntactically invalid `.json` file throws `IllegalArgumentException` whose message contains the filename
- [x] `DocumentReaderRegistry.supports("json")` returns `true` and `registry.read(jsonFile)` returns non-empty

### Quality gates

- [x] `./gradlew test --tests "*.JsonChunkerTest"` passes with no failures
- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions in existing tests

---

## Task 02-json-object-batching

Extends `JsonChunker` with `ObjectNode` dispatch using an accumulator/batching model. Scalar fields (primitives, short strings, small nested objects) are accumulated into a shared chunk and flushed when the next field would exceed the budget. A flat object with many scalar fields produces a small number of well-filled chunks — not one micro-chunk per key.

### Implementation steps

- [x] Write failing tests in `JsonChunkerTest`: empty object → empty list; all scalars fit in one chunk → 1 chunk containing all field values; many short scalars span multiple chunks (chunk count < field count); each chunk starts with a `##` heading; small nested object rendered as fenced `` ```json `` block inside the accumulator chunk (not a separate chunk); each chunk within token budget
- [x] Implement `ObjectNode` dispatch in `JsonChunker`: accumulator (heading + list of `**key**: value` lines); flush when adding next field would exceed `chunkSize`; primitives and short strings inline; small nested value as fenced `` ```json `` block (budget check includes fence tokens)
- [x] Write failing tests in `JsonDocumentReaderTest`: `.json` file with an object root produces `Document`s containing all scalar field values
- [x] Increment version in `gradle.properties` (minor bump)

### Acceptance criteria

- [x] `JsonChunker.chunk(emptyObjectNode)` returns an empty list
- [x] Object with 5 short scalar fields all fitting within budget produces exactly 1 chunk containing all 5 field values
- [x] Object with many short scalar fields produces fewer chunks than there are fields (batching demonstrated)
- [x] Each produced chunk stays within the configured token budget
- [x] Every produced chunk begins with a `##` heading matching the parent heading path
- [x] A small nested object is rendered as a fenced `` ```json `` block within the accumulator chunk, not as a separate chunk
- [x] `JsonDocumentReader` on a `.json` object file produces `Document`s whose combined text contains all scalar field values

### Quality gates

- [x] `./gradlew test --tests "*.JsonChunkerTest"` passes with no failures
- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions

---

## Task 03-json-recursive-rechunking

Extends `JsonChunker` with the recursive path for oversized nested values. When a nested `ObjectNode` or `ArrayNode` would exceed the budget, the accumulator is flushed and the value is dispatched recursively with the heading path extended by the current key — producing sub-chunks whose headings reflect their structural position. Long `TextNode` values are split via `TokenTextSplitter` (wrapped as `Document`, split, text extracted — follow the pattern in `SectionSplitter.splitWithFallback()`).

### Implementation steps

- [x] Write failing tests in `JsonChunkerTest`: nested object too large → sub-chunks with heading `## parent → childKey`; content from fields before the oversized key and the recursed sub-chunks appear in separate chunks; nested array too large → array-batching applied at the nested level; long string value → multiple chunks each carrying the parent key's heading; heading path at depth 3 renders as `## level1 → level2 → level3`
- [x] Implement recursive dispatch in `JsonChunker`: budget check on nested nodes before accumulating; flush accumulator before recursion; extend heading path; recurse with updated path; `TextNode` long → `TokenTextSplitter` (wrap in `Document`, apply splitter, extract `.text` from results)
- [x] Write failing tests in `JsonDocumentReaderTest`: deeply nested `.json` file produces multiple chunks whose combined text contains the deeply nested value
- [x] Increment version in `gradle.properties` (minor bump)

### Acceptance criteria

- [x] A nested object that exceeds budget produces at least one sub-chunk with heading `## parent → childKey`
- [x] Content from fields accumulated before the oversized key appears in a separate chunk from the recursed key's sub-chunks (no mixing across the flush boundary)
- [x] No chunk produced by recursion exceeds the configured token budget (except a single indivisible value that alone exceeds budget)
- [x] A long string value produces multiple chunks each whose heading matches the parent key's path
- [x] Heading path at depth 3 renders as `## level1 → level2 → level3`
- [x] `JsonDocumentReader` on a deeply nested `.json` file produces multiple `Document`s whose combined text contains the deeply nested value

### Quality gates

- [x] `./gradlew test --tests "*.JsonChunkerTest"` passes with no failures
- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions

---

## Task 04-jsonc-comment-stripping

New utility `JsoncCommentStripper` scans JSONC text character-by-character, tracking string-literal state, and removes `//` and `/* */` comments while leaving comment-like text inside string literals untouched. Wired into `JsonDocumentReader` for `.jsonc` and registered.

### Implementation steps

- [x] Write failing tests in `JsoncCommentStripperTest`: `//` line comment removed; `/* */` block comment removed; comment-like text inside a string literal preserved unchanged; empty string returns empty string; input with no comments returns output identical to input; comment at end of file without a trailing newline is stripped correctly
- [x] Implement `JsoncCommentStripper.strip(input: String): String` as a character scanner with string-literal tracking
- [x] Write failing tests in `JsonDocumentReaderTest`: `.jsonc` file with `//` and `/* */` comments produces zero chunks whose `text` contains the comment literal; valid JSON content is present in the chunks; `.jsonc` file whose JSON is invalid after stripping produces an error and returns no chunks
- [x] Add `.jsonc` branch in `JsonDocumentReader` to call `JsoncCommentStripper.strip()` before `ObjectMapper().readTree()`
- [x] Write failing test in `DocumentReaderRegistryTest`: `supports("jsonc")` returns `true`; `registry.read(jsoncFile)` returns non-empty for a valid file
- [x] Add `"jsonc"` entry to `DocumentReaderRegistry`
- [x] Increment version in `gradle.properties` (minor bump — new user-visible extension)

### Acceptance criteria

- [x] `JsoncCommentStripper.strip` removes `// comment` from the end of a line
- [x] `JsoncCommentStripper.strip` removes `/* block comment */` spanning multiple lines
- [x] Comment-like text inside a string literal (e.g. `"http://example.com"`, `"/* not a comment */"`) is preserved unchanged
- [x] Empty string input returns empty string without error
- [x] Input with no comments returns output byte-for-byte identical to input
- [x] Comment at end of file with no trailing newline is stripped without error
- [x] `JsonDocumentReader` on a `.jsonc` file produces zero chunks whose `text` contains the comment literal (e.g. the word `secret` from `// secret`)
- [x] `JsonDocumentReader` on a `.jsonc` file whose content is invalid JSON after stripping produces an error and no chunks
- [x] `DocumentReaderRegistry.supports("jsonc")` returns `true`

### Quality gates

- [x] `./gradlew test --tests "*.JsoncCommentStripperTest"` passes with no failures
- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions

---

## Task 05-jsonl-ingestion

Extends `JsonDocumentReader` to handle JSONL (newline-delimited JSON): each non-blank line is parsed independently; invalid lines are skipped with a warning to `System.err`; the set of parsed nodes is passed to the existing `JsonChunker` array path exactly as if the records were a root JSON array. Registered under `.jsonl`. README updated to document all three new extensions.

### Implementation steps

- [x] Write failing tests in `JsonDocumentReaderTest`: `.jsonl` with 3 valid lines produces the same chunk count and combined text as a `.json` file with a 3-element root array of the same records; `.jsonl` where line 2 of 4 is malformed JSON produces chunks from lines 1, 3, 4 — no exception thrown; `.jsonl` where every line is malformed returns an empty `Document` list — no exception thrown; blank lines between records are skipped without affecting chunk output
- [x] Implement JSONL branch in `JsonDocumentReader`: detect `.jsonl` extension, split on `\n`, skip blank lines, parse each non-blank line with `ObjectMapper`, skip-and-warn to `System.err` on `JsonParseException`, synthesise `ArrayNode` from valid parsed nodes, pass to `JsonChunker`
- [x] Write failing test in `DocumentReaderRegistryTest`: `supports("jsonl")` returns `true`; `registry.read(jsonlFile)` returns non-empty for a valid file
- [x] Add `"jsonl"` entry to `DocumentReaderRegistry`
- [x] Update `README.md` to list `.json`, `.jsonl`, and `.jsonc` as supported ingest formats
- [x] Increment version in `gradle.properties` (minor bump — new user-visible extension)

### Acceptance criteria

- [x] `.jsonl` with 3 valid lines produces the same chunk count and combined text as a `.json` file with a 3-element root array of the same records
- [x] When line 2 of a 4-line `.jsonl` file is malformed JSON, the chunks from lines 1, 3, and 4 are produced normally and no exception is thrown
- [x] A `.jsonl` file where every line is malformed returns an empty `Document` list without throwing
- [x] Blank lines between records are silently skipped and do not affect chunk output
- [x] `DocumentReaderRegistry.supports("jsonl")` returns `true` and `registry.read(jsonlFile)` returns non-empty for a valid file
- [x] `README.md` lists `.json`, `.jsonl`, and `.jsonc` as supported formats

### Quality gates

- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test --tests "*.DocumentReaderRegistryTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions
