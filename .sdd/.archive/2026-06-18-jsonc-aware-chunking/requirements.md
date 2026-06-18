# JSONC-Aware Chunking

## Problem Statement

When JSON with Comments (JSONC) files are ingested, all comment text is discarded before chunking. Comments in JSONC files are often the most valuable content for retrieval — they explain configuration options, annotate schema fields, and document intent. A query such as "where is the index stored?" should match the comment `// path where the Lucene index is stored`, but currently cannot because that text never reaches the chunk.

Beyond comments, the current JSON chunking infrastructure is split across two code paths (Jackson-based for `.json`/`.jsonl`, comment-stripper + Jackson for `.jsonc`), making it harder to evolve consistently.

## Solution

Replace the Jackson-based JSON chunker with a Tree-sitter-based chunker that parses the full concrete syntax tree, treating comments as first-class nodes positioned exactly where they appear in the source. Comments are rendered as readable prose alongside the key-value pairs they annotate. All three JSON variants (`.json`, `.jsonl`, `.jsonc`) are handled by this single unified chunker, so structural improvements and rendering changes apply everywhere automatically.

## User Stories

1. As a RAG user, I want comments in JSONC config files to appear in retrieved chunks, so that queries about documented settings return the explanatory comment text.
2. As a RAG user, I want a trailing inline comment (e.g. `"key": value  // explanation`) to appear in the same chunk as its key-value pair, so that the explanation is never separated from what it describes.
3. As a RAG user, I want a preceding block or line comment to appear in the same chunk as the key it annotates, so that multi-line documentation attached to a field is co-located with the field value.
4. As a RAG user, I want file-level comments (before the root JSON node) to appear in every chunk from that file, so that a file-wide description provides context for any retrieved chunk.
5. As a RAG user, I want block comment whitespace to be normalised (collapsed to single spaces), so that accidental indentation inside `/* */` comments does not pollute embeddings.
6. As a RAG user, I want comments inside array elements to be rendered as prose alongside the element's fields, so that annotated arrays (e.g. server lists, rule sets) are as readable as annotated objects.
7. As a RAG user, I want plain `.json` files to continue working exactly as before, so that switching to the unified chunker is transparent for comment-free files.
8. As a RAG user, I want plain `.jsonl` files to continue working exactly as before, so that log and event stream files are unaffected.
9. As a RAG user, I want malformed JSONL lines to be skipped with a warning rather than aborting ingestion, so that partially broken log files are still partially indexed.
10. As a RAG user, I want invalid `.json` or `.jsonc` files to produce a clear error message that includes the filename, so that ingestion failures are easy to diagnose.
11. As a RAG user, I want deeply nested JSONC objects to produce structured heading paths (e.g. `## store → directory`), so that I can search by key path even in complex config files.
12. As a RAG user, I want oversized JSON objects or arrays to be split across multiple chunks each within the token budget, so that large data files are fully indexed.
13. As a RAG user, I want each chunk to be self-contained with its heading path, so that a retrieved chunk is interpretable without reading surrounding chunks.

## User Acceptance Tests

1. Given a JSONC config file containing `"directory": ".ez-rag"` preceded by a line comment `// path where the Lucene index is stored`, when the file is ingested, then a search for "where is the index stored" returns a chunk containing both `directory` and the comment text.

2. Given a JSONC file with a trailing inline comment `"mode": "hybrid"  // combines BM25 and embeddings`, when the file is ingested, then the chunk containing `mode` also contains "combines BM25 and embeddings".

3. Given a JSONC file with a multi-line block comment above a key, when the file is ingested, then the block comment text appears as a single normalised paragraph in the same chunk as that key (no raw `/*` or `*/` markers, no extra indentation).

4. Given a JSONC file with two file-level comments before the root object (`// ez-rag config` and `// Adjust for your environment`), when the file is ingested, then every produced chunk contains both comment lines as a preamble.

5. Given a plain `.json` file with no comments, when ingested, then the chunk text and count are identical to what the previous chunker produced for the same file.

6. Given a `.jsonl` file with eight valid log lines and one malformed line, when the file is ingested, then eight items are chunked, a warning is printed to stderr for the malformed line, and no exception is thrown.

7. Given a `.jsonc` file whose root is an array where each element has a preceding comment, when the file is ingested, then each element's chunk contains its comment text as prose alongside the element's fields.

8. Given a JSONC file with a deeply nested object `{ "a": { "b": { "c": 1 } } }`, when ingested, then the chunk heading reads `## a → b`.

9. Given a JSON array file large enough that not all elements fit within the token budget, when ingested, then multiple chunks are produced, each within the budget, with headings `## Items N–M`.

10. Given a `.jsonc` file with a syntactically invalid JSON structure (after comments are accounted for), when ingested, then an `IllegalArgumentException` is thrown whose message includes the filename.

## Definition of Done

- All user acceptance tests pass.
- Plain `.json` and `.jsonl` ingestion behaviour is unchanged (verified by regression tests).
- JSONC comments appear in chunk text for all comment positions: preceding line, preceding block, trailing inline, and file-level.
- File-level comments are prepended to every chunk from the same file.
- All existing `JsonDocumentReaderTest` regression tests pass against the new implementation.
- No regression in ingestion of any other format.
- `CHUNKING.md` updated to reflect the new JSONC comment-preservation behaviour.
- Minor version bumped in `gradle.properties`.

## Out of Scope

- Comments inside string literals (these are data, not annotations, and are preserved as-is by definition).
- JSON5 format (a different superset of JSON; not addressed here).
- Producing structured metadata fields from comment text (comments are rendered as prose only).
- Modifying the rendering of `.json` or `.jsonl` files beyond what is required for unified chunker compatibility.
- Password-protected JSON files (not applicable to the format).

## Further Notes

JSONC is the format used by VS Code settings files, TypeScript `tsconfig.json`, and many developer-facing configuration files. The primary motivation for this change is that such files are heavily commented, and the comments carry the bulk of the human-readable documentation. Stripping them before chunking makes these files nearly useless in a RAG corpus.

The unified chunker approach (all three JSON variants through Tree-sitter) is a deliberate architectural simplification. It means that future improvements to JSON rendering — heading formats, array batching, object field ordering — only need to be made once.

---

## Technical Annex
> Written against codebase as of: 2026-06-18

### Architectural Decisions

#### New dependency

Add to `build.gradle.kts`:
```
implementation("io.github.bonede:tree-sitter-json:0.24.8")
```
Same library family (`io.github.bonede`) as the existing Kotlin, Java, TypeScript, and JavaScript Tree-sitter bindings.

#### Deletions

- `JsonChunker` — replaced entirely by `JsoncChunker`.
- `JsoncCommentStripper` — no longer needed; Tree-sitter parses comments natively.

#### New class: `JsoncChunker`

Replaces `JsonChunker`. Accepts raw source text (not a Jackson `JsonNode`). Parses with `TreeSitterJson` grammar via the existing `TSParser` / `TSNode` API used by source-code parsers.

Key responsibilities:
- Walk the CST, collecting `comment` nodes as siblings of `pair` (object field) and `value` nodes.
- Render preceding comment nodes (line or block) as a prose paragraph before the associated key-value line.
- Render trailing comment nodes (same line as a value) appended to the value line with ` — ` separator.
- Normalise block comment text: strip `/*`, `*/`, `//` markers; collapse internal whitespace runs to single space; trim.
- Collect top-level comments (before the root node) and prepend them to every chunk; deduct their token cost from the chunk budget.
- Budget-aware batching: pack as many fields/elements as fit within `chunkSize` tokens; flush when the budget is exceeded.
- Heading format for objects: `## key1 → key2` (full ancestor path, no per-field suffix).
- Heading format for arrays: `## Items N–M` (1-based, en-dash) or `## Item N` for single-element batches.
- Oversized single elements/fields emitted alone (escape hatch, same as current behaviour).

Signature (approximate):
```kotlin
class JsoncChunker(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val tokenCounter: (String) -> Int = TokenCounter::countTokens,
) {
    fun chunk(source: String): List<String>
}
```

Input is raw source text for all three variants (`.json`, `.jsonl`, `.jsonc`). The caller is responsible for assembling `.jsonl` lines into a synthetic JSON array string before calling `chunk`.

#### Modified class: `JsonDocumentReader`

- Remove the `JsoncCommentStripper` call and the Jackson `ObjectMapper` parse.
- For `.jsonl`: assemble non-blank lines into a synthetic `[ line1, line2, ... ]` string (same logic as today, but produces a string rather than a Jackson `ArrayNode`). Skip and warn on malformed lines.
- For `.json` and `.jsonc`: pass the raw file text directly.
- Call `JsoncChunker.chunk(source)` for all three variants.
- Error handling: catch `TSParser` / Tree-sitter exceptions and rethrow as `IllegalArgumentException` with the filename in the message, consistent with current behaviour.

#### Comment rendering rules (summary)

| Position | Rendering |
|---|---|
| Preceding line comment `// text` | Prose paragraph before the key-value line |
| Preceding block comment `/* text */` | Prose paragraph before the key-value line; whitespace normalised |
| Trailing inline comment `value  // text` | Appended to value line: `**key**: value — text` |
| File-level comments (before root node) | Prepended to every chunk; token cost deducted from budget |

#### Data flow

```
.jsonc / .json / .jsonl file
        │
        ▼
JsonDocumentReader
  (assemble .jsonl lines → synthetic array string)
        │
        ▼
JsoncChunker.chunk(source: String)
  Tree-sitter parse → CST
  Walk CST: collect comment nodes, pair nodes, array nodes
  Budget-aware batching with comment rendering
        │
        ▼
List<String> (Markdown chunk texts)
        │
        ▼
JsonDocumentReader wraps each in Document.builder().text(chunk).build()
```

### Automated Testing Decisions

**What makes a good test here:** test the rendered Markdown text that `JsoncChunker.chunk()` produces for a given input string. Do not test internal CST node types or private rendering helpers. The contract is: given source text in, get Markdown chunk strings out.

**Modules with automated tests:**

- **`JsoncChunker` — full unit test suite** (`JsoncChunkerTest`). This is the deep module; all comment-rendering logic lives here and should be tested exhaustively. Test cases should cover:
  - Empty input → empty list.
  - Plain JSON object (no comments) → same heading/field structure as before.
  - Plain JSON array (no comments) → same batch headings as before.
  - Preceding line comment appears as prose before its key.
  - Preceding block comment normalised and appears as prose.
  - Trailing inline comment appended with ` — ` separator.
  - File-level comments prepended to all chunks.
  - File-level comment token cost deducted (budget test).
  - Array elements with preceding comments rendered as prose.
  - Budget batching: elements split across chunks when budget exceeded.
  - Oversized single element emitted alone.
  - Deeply nested object produces heading path `## a → b → c`.
  - JSONL synthetic array input (caller-assembled) works correctly.

- **`JsonDocumentReader` — updated integration tests** (`JsonDocumentReaderTest`). Keep existing regression tests (plain `.json`, `.jsonl`, empty file, malformed file). Add:
  - `.jsonc` file with comments produces chunks containing comment text.
  - `.jsonl` with one malformed line: remaining lines chunked, warning to stderr, no exception.

**Prior art:** `JsonChunkerTest` (word-count token counter pattern, inline JSON strings) and `JsonDocumentReaderTest` (`@TempDir` file writing pattern). Follow the same patterns in new tests.

**Deleted tests:** `JsonChunkerTest` and `JsoncCommentStripperTest` are removed along with their production classes.
