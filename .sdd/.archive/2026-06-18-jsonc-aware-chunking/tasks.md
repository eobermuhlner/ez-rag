# Tasks: jsonc-aware-chunking

## Task 01-dependency-and-plain-object-chunking

Add the `tree-sitter-json` dependency and create `JsoncChunker` handling plain JSON objects (no comments). This establishes the new module and verifies Tree-sitter JSON parses JSONC input without error; comment rendering is added in Task 04. No wiring to `JsonDocumentReader` in this task.

### Implementation steps

- [x] Add `io.github.bonede:tree-sitter-json:0.24.8` to `build.gradle.kts`
- [x] Create `JsoncChunker(chunkSize, chunkOverlap, tokenCounter)` with `fun chunk(source: String): List<String>`
- [x] Parse source with `TreeSitterJson` via `TSParser`; confirm JSONC input (source with `//` comment) parses without exception
- [x] Return empty list for blank/empty source
- [x] Handle root `object` node: render each primitive field as `**key**: value`; small nested objects/arrays as fenced JSON code block; large nested objects/arrays recurse with extended heading path
- [x] Root-object heading is `##` (bare); nested-object heading is `## k1 → k2` (arrow-separated ancestor path)
- [x] Budget-aware field batching: accumulate fields until next field would exceed budget, then flush
- [x] Write `JsoncChunkerTest` covering the acceptance criteria below

### Acceptance criteria

- [x] `JsoncChunker.chunk("")` and `JsoncChunker.chunk("  ")` each return an empty list
- [x] `JsoncChunker.chunk("{}")` returns an empty list
- [x] A flat object `{"a":"x","b":"y"}` produces one chunk whose text starts with `##` and contains `**a**: x` and `**b**: y`
- [x] A JSONC string `{ "x": 1 } // trailing` passed to `chunk()` produces the same chunk count and `**x**: 1` content as `{ "x": 1 }` (comment silently ignored at this stage; no exception thrown)
- [x] A nested object `{"store":{"dir":".ez-rag"}}` produces a chunk with heading `## store` and field line `**dir**: .ez-rag`
- [x] When an object's fields exceed the token budget, each produced chunk stays within budget (verified with a word-count token counter in the test)

### Quality gates

- [x] `./gradlew test --tests "*.JsoncChunkerTest"` passes with no failures
- [x] `./gradlew compileKotlin` produces no warnings or errors

---

## Task 02-array-chunking-and-budget-batching

Extend `JsoncChunker` to handle root `array` nodes with budget-aware batching and oversized-element escape hatch. End-to-end wiring through `JsonDocumentReader` is covered in Task 03.

### Implementation steps

- [x] Handle root `array` node: render object elements via recursive object rendering (prose fields); primitive elements rendered inline
- [x] Heading for multi-element batch: `## Items N–M` (1-based, en-dash U+2013)
- [x] Heading for single-element batch: `## Item N`
- [x] When a heading path exists from a parent key: `## parent → Items N–M`
- [x] Budget batching: accumulate elements until next element would exceed budget, flush
- [x] Oversized escape hatch: a single element whose token count exceeds the budget is emitted alone as `## Item N`
- [x] Extend `JsoncChunkerTest` covering the acceptance criteria below

### Acceptance criteria

- [x] A 3-element array where all elements fit the budget produces exactly one chunk with heading `## Items 1–3`
- [x] When the budget forces a 2+1 split, the first chunk heading is `## Items 1–2` and the second is `## Item 3`
- [x] A single element whose serialised size exceeds the budget is emitted as one chunk headed `## Item 1` without truncation
- [x] An array nested under object key `"servers"` produces headings prefixed `## servers → Items …`
- [x] `JsoncChunker.chunk("[]")` returns an empty list

### Quality gates

- [x] `./gradlew test --tests "*.JsoncChunkerTest"` passes with no failures
- [x] `./gradlew compileKotlin` produces no warnings or errors

---

## Task 03-wire-json-jsonl-delete-old-chunker

Wire `JsonDocumentReader` to route `.json` and `.jsonl` through `JsoncChunker`. Assemble JSONL lines into a synthetic array string. Delete `JsonChunker` and its tests. `JsoncCommentStripper` is NOT deleted yet — `.jsonc` still uses the old path until Task 04.

### Implementation steps

- [x] `JsonDocumentReader`: replace the Jackson `ObjectMapper` parse path for `.json` with `JsoncChunker.chunk(rawText)`
- [x] For `.jsonl`: collect non-blank lines, parse-validate each with Jackson (to catch malformed lines), skip invalid lines with a `System.err` warning containing the line number and filename, reassemble valid lines as a JSON array string `[line1,line2,...]`, pass to `JsoncChunker.chunk()`
- [x] Error handling: if `JsoncChunker.chunk()` throws (malformed JSON), catch and rethrow as `IllegalArgumentException` whose message contains the filename
- [x] Delete `JsonChunker.kt` and `JsonChunkerTest.kt`
- [x] Update `JsonDocumentReaderTest`: all existing `.json` and `.jsonl` regression tests must pass; add tests for malformed-line warning and special-character JSONL lines

### Acceptance criteria

- [x] All previously-passing `JsonDocumentReaderTest` tests for `.json` files pass unchanged
- [x] All previously-passing `JsonDocumentReaderTest` tests for `.jsonl` files pass unchanged
- [x] A `.jsonl` file with 3 valid lines and 1 malformed line produces chunks for all 3 valid items without throwing
- [x] The malformed line emits a warning to stderr containing the line number
- [x] A `.jsonl` file whose valid lines contain embedded quote characters and backslashes is assembled and parsed correctly (no corruption of field values)
- [x] A syntactically invalid `.json` file throws `IllegalArgumentException` whose message contains the filename
- [x] `JsonChunker.kt` and `JsonChunkerTest.kt` no longer exist in the codebase

### Quality gates

- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test` (full suite) passes — no regressions in other formats
- [x] `./gradlew compileKotlin` produces no warnings or errors

---

## Task 04-preceding-comments-and-wire-jsonc

Extend `JsoncChunker` to render preceding comment nodes (line and block) as prose paragraphs. Wire `.jsonc` through `JsoncChunker` and delete `JsoncCommentStripper`.

### Implementation steps

- [x] In the CST walker, detect `comment` sibling nodes that appear immediately before a `pair` node (object field) or an array element node
- [x] Line comment (`// text`): strip `//` prefix, trim → emit as a prose paragraph on its own line before the `**key**: value` line
- [x] Block comment (`/* text */`): strip `/*` and `*/` delimiters, collapse all internal whitespace runs (including newlines) to a single space, trim → emit as a prose paragraph before the `**key**: value` line
- [x] Same rules apply for comments preceding array elements
- [x] `JsonDocumentReader`: route `.jsonc` files through `JsoncChunker.chunk(rawText)` (pass raw file text); remove `JsoncCommentStripper` call
- [x] Delete `JsoncCommentStripper.kt` and `JsoncCommentStripperTest.kt`
- [x] Extend `JsoncChunkerTest` and `JsonDocumentReaderTest` covering the acceptance criteria below

### Acceptance criteria

- [x] A line comment `// path where stored` immediately before `"directory": ".ez-rag"` produces a chunk where `path where stored` appears on a line before `**directory**: .ez-rag`
- [x] A block comment `/* line1\n   line2 */` before a key is rendered as `line1 line2` (single space, no leading/trailing whitespace, no indentation artefacts)
- [x] Raw comment markers (`//`, `/*`, `*/`) do not appear anywhere in any produced chunk
- [x] A `.jsonc` file routed through `JsonDocumentReader` produces chunks containing the comment text
- [x] A comment before an array element produces prose before that element's fields
- [x] `JsoncCommentStripper.kt` and `JsoncCommentStripperTest.kt` no longer exist in the codebase

### Quality gates

- [x] `./gradlew test --tests "*.JsoncChunkerTest"` passes with no failures
- [x] `./gradlew test --tests "*.JsonDocumentReaderTest"` passes with no failures
- [x] `./gradlew test` (full suite) passes — no regressions
- [x] `./gradlew compileKotlin` produces no warnings or errors

---

## Task 05-trailing-inline-comments

Extend `JsoncChunker` to detect trailing comment nodes (on the same line as a value) and append their text to the rendered value line.

### Implementation steps

- [x] In the CST walker, detect a `comment` node that is a sibling immediately following a `pair` (or array element) and whose start line equals the end line of the value node
- [x] Render: `**key**: value — comment text` (` — ` separator; `//` prefix stripped and text trimmed)
- [x] Handle all primitive value types: string, number, boolean, null
- [x] A key that has both a preceding comment AND a trailing comment: preceding comment renders as a prose paragraph; trailing comment appended to the value line — both appear in the same chunk
- [x] Extend `JsoncChunkerTest` covering the acceptance criteria below

### Acceptance criteria

- [x] `"mode": "hybrid"  // combines BM25 and embeddings` renders as `**mode**: hybrid — combines BM25 and embeddings`
- [x] `"topK": 10  // result count` renders as `**topK**: 10 — result count`
- [x] `"enabled": true  // default on` renders as `**enabled**: true — default on`
- [x] A key with both a preceding block comment and a trailing line comment produces a chunk containing both: the block comment text as a preceding paragraph and the trailing comment appended to the value line
- [x] A trailing comment on an array element is appended to that element's last rendered field line with ` — ` separator

### Quality gates

- [x] `./gradlew test --tests "*.JsoncChunkerTest"` passes with no failures
- [x] `./gradlew compileKotlin` produces no warnings or errors

---

## Task 06-file-level-comments-docs-and-version

Extend `JsoncChunker` to prepend file-level (pre-root) comments to every chunk, deducting their token cost from the per-chunk budget. Update `CHUNKING.md` and bump the minor version.

### Implementation steps

- [x] Collect all `comment` nodes that appear before the root `object` or `array` node in the CST
- [x] Normalise and join into a single preamble string using the same rules as preceding comments (strip markers, collapse whitespace, trim; join multiple comments with a space)
- [x] Deduct `tokenCounter(preamble)` from `chunkSize` before batching, so all content chunks still fit within the original budget after the preamble is prepended
- [x] Prepend the preamble string (followed by a blank line) to every produced chunk
- [x] If no pre-root comments exist, output is unchanged (no preamble, no budget reduction)
- [x] Extend `JsoncChunkerTest` covering the acceptance criteria below
- [x] Update `CHUNKING.md` section on JSONC: describe comment preservation, all four comment positions (preceding line, preceding block, trailing inline, file-level), and whitespace normalisation
- [x] Bump minor version in `gradle.properties` (this is a `feat:` commit)

### Acceptance criteria

- [x] A JSONC file with `// ez-rag config` before the root object produces every chunk prefixed with `ez-rag config` (followed by a blank line before the heading)
- [x] Two file-level comments `// line one` and `// line two` are joined into a single preamble `line one line two` prepended to all chunks
- [x] When file-level comments consume T tokens and `chunkSize` is C, each content chunk contains at most `C - T` tokens of field/element content
- [x] A plain `.json` file with no pre-root comments produces chunks with no preamble (output identical to Task 03 behaviour)
- [x] `CHUNKING.md` contains a description of all four comment positions for JSONC
- [x] `gradle.properties` `version` reflects a minor bump relative to the version before this feature branch

### Quality gates

- [x] `./gradlew test --tests "*.JsoncChunkerTest"` passes with no failures
- [x] `./gradlew test` (full suite) passes — no regressions
- [x] `./gradlew compileKotlin` produces no warnings or errors
