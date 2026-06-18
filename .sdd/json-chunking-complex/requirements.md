# JSON Chunking

## Problem Statement

Users who maintain knowledge bases containing JSON or JSONL files cannot ingest those files into ez-rag. Attempting to ingest a `.json`, `.jsonl`, or `.jsonc` file either falls back to unstructured plain-text splitting (producing poor chunks that break in the middle of JSON syntax) or is rejected as binary. This makes JSON-based datasets, API response archives, and configuration documentation inaccessible for retrieval.

## Solution

Extend the ingest pipeline to natively understand JSON. The reader auto-detects the logical structure of each file — an array of records, a single structured document, or a mix — and produces chunks that preserve semantic coherence. Long nested values that exceed the chunk budget are recursively re-chunked rather than truncated or emitted as oversized chunks. JSONC comment syntax is stripped transparently before parsing. The feature requires no configuration from the user: file extension determines the parser, and structure determines the chunking strategy.

## User Stories

1. As a user, I want to ingest a `.json` file containing an array of objects, so that each object (or a budget-bounded batch of objects) becomes a retrievable chunk.
2. As a user, I want to ingest a `.json` file containing a single JSON object, so that scalar fields are batched into budget-bounded chunks and large nested values become their own retrievable sections.
3. As a user, I want to ingest a `.jsonl` file (newline-delimited JSON), so that each line is treated as an independent JSON object and batched into chunks by the same budget-aware logic.
4. As a user, I want to ingest a `.jsonc` file (JSON with comments), so that comments are stripped transparently and the remaining JSON is chunked normally.
5. As a user, I want nested JSON objects and arrays that are small enough to fit in a single chunk to appear as formatted JSON code blocks, so that the structure is preserved and readable.
6. As a user, I want nested JSON objects and arrays that are too large to fit in a single chunk to be recursively re-chunked, so that no chunk exceeds the budget and every part of the data is retrievable.
7. As a user, I want long string values within a JSON document to be split at token boundaries, so that very long prose fields do not produce oversized chunks.
8. As a user, I want primitive JSON values (numbers, booleans, nulls) to be rendered inline within their parent chunk, so that atomic values are never split.
9. As a user, I want the heading path of each chunk to reflect its position in the JSON structure (e.g., `users → 0 → address`), so that search results clearly identify where the content came from.
10. As a user, I want JSON ingestion to respect the same `chunkSize` and `chunkOverlap` settings as all other document types, so that my tuning applies uniformly.
11. As a user, I want an empty JSON file or a JSON file with an empty root array/object to produce no chunks and no error, so that degenerate inputs are handled gracefully.
12. As a user, I want a JSON file with invalid syntax to produce a clear error message, so that I can identify and fix corrupt files.
13. As a user, I want JSONL files where individual lines contain invalid JSON to skip the invalid line and continue processing valid lines, so that one corrupt record does not block an entire dataset.

## User Acceptance Tests

1. Given a `.json` file whose root is an array of 100 small objects, when the file is ingested, then the result contains multiple chunks each within the configured token budget, and every object appears in exactly one chunk.
2. Given a `.json` file whose root is a single object with many short string or primitive keys, when the file is ingested, then the scalar fields are batched into a small number of well-filled chunks (not one chunk per key), each within the configured token budget, and each chunk's heading reflects the object's position in the JSON structure.
3. Given a `.jsonl` file with 50 objects, one per line, when the file is ingested, then the result is equivalent to ingesting a `.json` file with the same 50 objects in a root array.
4. Given a `.jsonc` file containing `//` line comments and `/* */` block comments, when the file is ingested, then the comment text does not appear in any chunk, and the remaining JSON is chunked as normal.
5. Given a `.json` file containing a nested object small enough to fit within the chunk budget, when the file is ingested, then the nested object appears in the chunk as a fenced JSON code block.
6. Given a `.json` file containing a deeply nested array with hundreds of elements, when the file is ingested, then no chunk exceeds the configured token budget (except a single element that is itself over budget), and every element is retrievable.
7. Given a `.json` file containing a key whose value is a very long plain string, when the file is ingested, then that string is split across multiple chunks at token boundaries, and each chunk carries the parent key's heading.
8. Given an empty `.json` file, when the file is ingested, then zero chunks are produced and no error is reported.
9. Given a `.json` file with a syntax error, when the file is ingested, then an error message is reported and no partial chunks are stored.
10. Given a `.jsonl` file where line 3 is malformed JSON, when the file is ingested, then lines 1, 2, 4, and beyond produce chunks normally, and a warning is logged for line 3.

## Definition of Done

- All user acceptance tests pass.
- `.json`, `.jsonl`, and `.jsonc` files are recognised by the ingest subcommand without any additional flags or configuration.
- No chunk exceeds the configured token budget, except a single indivisible array element or object value that is itself over budget.
- Recursive re-chunking correctly propagates the heading path at every nesting level.
- JSONC comment stripping handles both line comments and block comments, including comments inside strings left untouched.
- JSONL files with one or more malformed lines log a warning per bad line and continue processing.
- All automated tests listed in the Technical Annex pass.
- No regression in existing file-type ingestion.
- README updated to list `.json`, `.jsonl`, and `.jsonc` as supported formats.

## Out of Scope

- JSON Schema validation or type-checking of ingested documents.
- Configurable key-selection for headings (e.g., using a specific field like `id` or `name` as the chunk title rather than the structural path).
- Support for JSON Pointer or JSONPath queries to select which parts of a document to ingest.
- Streaming ingestion of extremely large JSON files that do not fit in memory.
- Binary JSON formats (BSON, CBOR, MessagePack).

## Further Notes

- Jackson `ObjectMapper` is already available transitively via `spring-boot-starter-web` and is the natural choice for JSON parsing.
- The recursive re-chunking strategy mirrors the mental model already established by `XmlDocumentReader` (structured sections → Markdown → `SectionSplitter`) and `TableChunker` (row budget compliance with an oversized-row escape hatch).
- JSONC is used extensively by tooling configuration files (VS Code settings, TypeScript configs). Supporting it increases the utility of ez-rag for developer-oriented knowledge bases.

---

## Technical Annex
> Written against codebase as of: 2026-06-17

### Architectural Decisions

#### Module 1 — `JsoncCommentStripper` (new utility)

**Location:** `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/JsoncCommentStripper.kt`

Pure function — no I/O, no state. Strips `//` line comments and `/* */` block comments from a JSONC string. Must correctly handle:
- Comments inside string literals (must not strip them)
- Nested `/* */` (not supported by JSON spec; treat as non-nested)
- Unicode edge cases

```kotlin
object JsoncCommentStripper {
    fun strip(input: String): String
}
```

#### Module 2 — `JsonChunker` (new deep module)

**Location:** `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/JsonChunker.kt`

Recursive engine. Accepts a Jackson `JsonNode` and a heading path; returns `List<String>` where each string is a ready-to-embed chunk of Markdown text.

```kotlin
class JsonChunker(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    fun chunk(node: JsonNode, headingPath: List<String> = emptyList()): List<String>
}
```

**Dispatch logic (called recursively):**

| Node type | Fits in budget? | Action |
|-----------|----------------|--------|
| `ArrayNode` | — | Budget-aware batching: accumulate elements (serialized as pretty JSON) until adding the next would exceed `chunkSize`; emit each batch as one chunk. Oversized single element emitted alone. |
| `ObjectNode` | — | See object-key batching below. |
| `TextNode` (long) | No | Token-split using `TokenTextSplitter` with `chunkSize` / `chunkOverlap`. Each piece emitted as a chunk under the current heading. |
| `TextNode` (short) | Yes | Accumulated into the current object-key batch (see below). |
| Nested `ObjectNode` / `ArrayNode` | Yes | Accumulated into the current object-key batch as a pretty-printed fenced ` ```json ` code block (budget check includes fence tokens). |
| Nested `ObjectNode` / `ArrayNode` | No | Flush the current batch first, then recurse with heading path extended by current key. |
| Primitive (`NumberNode`, `BooleanNode`, `NullNode`) | — | Accumulated into the current object-key batch (never split). |

**Object-key batching:** When dispatching an `ObjectNode`, maintain a mutable accumulator (heading + list of `key: value` lines). Iterate keys in declaration order:

1. Render the candidate line: `**key**: <value>` (primitives and short strings inline; small nested structures as a fenced ` ```json ` block on its own line).
2. If adding the candidate line would cause the accumulator to exceed `chunkSize`, flush the accumulator as one chunk and start a new one under the same heading path.
3. For large nested `ObjectNode`/`ArrayNode` values: flush the accumulator, then recurse with the heading path extended by the current key.
4. For long `TextNode` values: flush the accumulator, then token-split the string under a heading path extended by the current key.
5. After all keys are processed, flush any remaining accumulator content.

This ensures that a flat object with many scalar fields produces a small number of well-filled chunks rather than one micro-chunk per key.

**Heading format:** `## level1 → level2 → level3` (levels joined with ` → `). For array batches, heading suffix is `Items {start}–{end}` (1-based). For single-element batches, `Item {index}`. Object-key batch chunks use the parent heading path without a per-key suffix.

**Token counting:** use the existing `TokenCounter.countTokens(text)` utility — same as `TableChunker` and `SectionSplitter`.

#### Module 3 — `JsonDocumentReader` (new reader)

**Location:** `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/JsonDocumentReader.kt`

Orchestrates the full pipeline for a single file. Handles all three extension variants.

```kotlin
class JsonDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    fun read(): List<Document>
}
```

**Internal flow:**

1. Read file as UTF-8 string.
2. If extension is `jsonc`: call `JsoncCommentStripper.strip(raw)`.
3. If extension is `jsonl`: split on `\n`, parse each non-blank line as a `JsonNode`, wrap all parsed nodes in a synthetic `ArrayNode`, proceed as if root were an array. Skip and warn on parse errors.
4. Otherwise: parse with `ObjectMapper().readTree(text)`. On parse error, throw `IllegalArgumentException` with filename and cause.
5. Call `JsonChunker(chunkSize, chunkOverlap).chunk(root)`.
6. Wrap each chunk string in a `Document.builder().text(chunkText).build()`.

**Note:** `chunkOverlap` is passed to `JsonChunker` for use in string-value splitting, consistent with `CsvDocumentReader`'s approach of accepting it for API consistency even where not all paths use it.

#### Module 4 — `DocumentReaderRegistry` (modification)

**Location:** `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/DocumentReaderRegistry.kt`

Add three entries to the `readers` map, parallel to the existing `"csv"` entry:

```kotlin
"json"  to { file -> JsonDocumentReader(file, chunkSize, chunkOverlap).read() },
"jsonl" to { file -> JsonDocumentReader(file, chunkSize, chunkOverlap).read() },
"jsonc" to { file -> JsonDocumentReader(file, chunkSize, chunkOverlap).read() },
```

### Automated Testing Decisions

**What makes a good test:** Tests assert on the observable output of a module (returned `List<String>` or `List<Document>`, chunk count, chunk content, heading prefixes, token budget compliance) — not on internal implementation steps. Tests use inline string literals or programmatically constructed `JsonNode` trees rather than external fixture files where possible, to keep them self-contained.

**Prior art:** `TableChunkerTest` (budget compliance, non-overlapping guarantee, empty input, oversized-row escape hatch) and `KotlinSourceCodeParserTest` (heading structure, metadata fields) are the closest existing examples.

#### `JsoncCommentStripperTest`
- Unit tests on string inputs.
- Cases: line comment, block comment, nested strings with comment-like text, empty input, no comments, comment at end of file without newline, comment inside string literal left untouched.

#### `JsonChunkerTest`
- Unit tests using `ObjectMapper().readTree(...)` to construct inputs.
- Cases:
  - Empty array → empty list.
  - Empty object → empty list.
  - Array of small objects → multiple chunks, all within budget.
  - Array where a single element exceeds budget → element emitted alone.
  - Object with many short string/primitive values → scalar fields batched into fewer chunks than there are keys; no chunk exceeds budget.
  - Object with string values that collectively fit in one chunk → single chunk, all keys present.
  - Object with nested object that fits budget → nested rendered as fenced JSON block, accumulated into the current batch.
  - Object with nested object that exceeds budget → current batch flushed first, then recursed with heading path extended by current key.
  - Long string value → split into multiple chunks, all carry parent heading.
  - Primitive values (number, boolean, null) → appear inline, never split.
  - Array batch heading format: `## Items 1–10`, `## Item 1` for single.

#### `JsonDocumentReaderTest`
- Integration-level tests on file inputs (use `File.createTempFile` with inline content).
- Cases: plain `.json` array, plain `.json` object, `.jsonl` multi-line, `.jsonc` with comments, empty file, invalid JSON, `.jsonl` with one bad line (remainder processed, warning not tested at this level).
- Assert on chunk count, `Document.text` content, absence of comment text in JSONC output.
- Prior art: `CsvDocumentReaderTest`, `XmlDocumentReaderTest`.
