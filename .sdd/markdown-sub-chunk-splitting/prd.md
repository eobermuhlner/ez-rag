# PRD: Markdown Sub-Chunk Splitting

## Problem Statement

When ingesting markdown files, the current chunker creates exactly one chunk per heading section, regardless of how large that section is. Sections that exceed the configured `chunkSize` are stored as oversized chunks, which degrades embedding quality and retrieval accuracy — embedding models have fixed token windows, and oversized chunks cause truncation or dilution of semantic signal.

## Solution

Introduce layout-aware sub-splitting inside each heading section. After identifying a section's body content, parse it into semantic layout blocks (paragraphs, tables, code blocks, lists, block quotes). Accumulate these blocks greedily into sub-chunks, only splitting at a block boundary when the token budget would be exceeded. As a last resort, apply sliding-window splitting to individual blocks that are themselves oversized.

Every sub-chunk retains the full heading hierarchy prefix and metadata so that retrieval quality is preserved regardless of which sub-chunk matches a query.

## User Stories

1. As a developer ingesting large markdown documentation, I want sections that exceed `chunkSize` to be split into smaller sub-chunks, so that embedding models receive text within their effective token window.
2. As a developer, I want sub-chunks to always include the full heading hierarchy prefix, so that retrieved chunks carry enough context for the LLM to understand what section they came from.
3. As a developer, I want sections that fit within `chunkSize` to remain as a single chunk, so that the current chunking behaviour is preserved for small sections.
4. As a developer, I want split boundaries to occur between paragraphs, tables, code blocks, list items, and block quotes rather than mid-sentence, so that sub-chunks are semantically coherent.
5. As a developer, I want two small paragraphs to remain in the same sub-chunk if they fit within the token budget, so that related content is not split unnecessarily.
6. As a developer, I want tables to never be split mid-row, so that retrieved chunks contain structurally valid table content.
7. As a developer, I want fenced code blocks to never be split mid-block, so that retrieved chunks contain syntactically valid code.
8. As a developer, I want horizontal rules to trigger a forced chunk flush, so that the author's explicit thematic separation is respected even when the token budget has not been reached.
9. As a developer, I want horizontal rule markers to be excluded from all chunk texts, so that retrieved chunks do not contain meaningless separator syntax.
10. As a developer, I want bullet and numbered list items to serve as sub-boundaries within an oversized list, so that list content splits between items rather than mid-sentence.
11. As a developer, I want a single oversized list item to fall back to sliding-window splitting, so that the token limit is still respected even for very long list items.
12. As a developer, I want block quotes to be treated as atomic units, so that quote attribution and context are not separated across chunks.
13. As a developer, I want content appearing before the first heading to be sub-split with the same algorithm, so that all document content obeys the token limit consistently.
14. As a developer, I want the heading prefix token cost to be counted against the chunk budget, so that emitted sub-chunks never silently exceed `chunkSize` due to prefix overhead.
15. As a developer, I want token counting to use the same `cl100k_base` encoding as the rest of the pipeline, so that size estimates are accurate and consistent.
16. As a developer, I want sub-chunks that must be force-split to use the configured `chunkOverlap` for their sliding-window fallback, so that semantic continuity is maintained across forced splits.
17. As a developer, I want layout-boundary splits to carry no overlap, so that content is not needlessly duplicated between semantically distinct blocks.
18. As a developer, I want all sub-chunks within a section to share the same `heading_title`, `heading_level`, and `heading_path` metadata, so that retrieval filtering by heading still works correctly.
19. As a developer, I want the existing eval corpus tests to continue passing after this change, so that I have confidence the new algorithm does not regress retrieval quality.
20. As a developer, I want the YAML front matter stripping to remain unaffected, so that ingestion of Jekyll/Hugo documents continues to work correctly.

## Implementation Decisions

### Module: `LayoutBlockParser`

New pure-function module that parses a raw markdown body string into an ordered list of typed `LayoutBlock` objects.

Block types (sealed hierarchy):
- `Paragraph` — consecutive non-blank lines that do not match any other type
- `FencedCodeBlock` — lines between opening and closing ` ``` ` or `~~~` fences
- `Table` — consecutive lines containing `|`
- `BulletList` — consecutive lines starting with `- `, `* `, `+ `, or a digit followed by `. ` (individual items exposed as a sub-list for splitting)
- `BlockQuote` — consecutive lines starting with `> `
- `HorizontalRule` — a single line matching `---+`, `___+`, or `\*\*\*+` (after trimming); signals a forced flush

Blank lines are used as paragraph separators and are not themselves emitted as blocks. Block detection is applied in priority order: fenced code blocks are detected first (to prevent `|` inside code being misread as a table row), then tables, then lists, then block quotes, then horizontal rules, then paragraphs.

### Module: `TokenCounter`

New thin utility wrapping `jtokkit`. Provides a single function:

```
countTokens(text: String): Int
```

Uses `cl100k_base` encoding, consistent with Spring AI's `TokenTextSplitter`. Intended to be injected as a dependency (or passed as a function reference) so tests can substitute a simple word-count approximation.

### Module: `SectionSplitter`

New module that implements the greedy layout-aware accumulation algorithm. Constructor parameters mirror `MarkdownDocumentReader`: `chunkSize` and `chunkOverlap`. Primary entry point:

```
splitSection(bodyText: String, headingPrefix: String): List<String>
```

Returns a list of complete chunk texts (heading prefix already prepended to each). Algorithm:

1. Compute `prefixTokenCost = countTokens(headingPrefix + "\n")` (0 if headingPrefix is empty).
2. Effective body budget = `chunkSize - prefixTokenCost`.
3. Parse `bodyText` into `LayoutBlock` list via `LayoutBlockParser`.
4. Accumulate blocks into a mutable buffer:
   - `HorizontalRule` → force-flush buffer (rule text discarded); budget resets.
   - Any other block → if `countTokens(buffer + block) ≤ budget`: append to buffer.
   - Over budget and buffer non-empty → flush buffer as a sub-chunk, start fresh with the current block.
   - Over budget and buffer empty (single block exceeds budget):
     - `BulletList` → split at item boundaries (recurse; single oversized item falls back to `TokenTextSplitter`).
     - All other atomic blocks → apply `TokenTextSplitter(chunkSize, chunkOverlap)` to the block text.
5. Flush any remaining buffer as the final sub-chunk.
6. Prepend `headingPrefix + "\n"` to each sub-chunk text before returning (skipped when prefix is empty).

### Module: `MarkdownDocumentReader` (modified)

The `flushBuffer()` inner function is modified to call `SectionSplitter.splitSection()` and emit one `Document` per returned string, all sharing the same heading metadata. The rest of the reader (heading stack management, YAML stripping, fallback path for headingless files) is unchanged.

### Token Counting Dependency

`jtokkit` is already on the classpath transitively via Spring AI. No new dependencies are required.

### Sliding-Window Fallback

The existing Spring AI `TokenTextSplitter` is reused as-is for the sliding-window fallback path. Overlap is applied only within this fallback; layout-boundary splits carry no overlap.

### Metadata

No new metadata fields are introduced. All sub-chunks from a section carry identical `heading_title`, `heading_level`, and `heading_path` values. The global `chunk_index` (assigned by `IngestService`) differentiates sub-chunks within a section.

## Testing Decisions

### What makes a good test

Tests should assert observable output (the list of chunk texts and their count) given a specific markdown input string and chunk size. Tests should not assert on internal block representations or intermediate parsing steps. Use small, explicit `chunkSize` values (e.g. 20–50 tokens) to force splitting with short inputs.

### Modules to test

**`LayoutBlockParser` — unit tests (new test class)**

Verify that each block type is correctly identified and delimited:
- Paragraph separated by blank lines
- Fenced code block (both ` ``` ` and `~~~` fences)
- Table (lines with `|`)
- Bullet list and numbered list; individual items accessible
- Block quote
- Horizontal rule variants (`---`, `___`, `***`)
- Mixed content (multiple block types in sequence)
- Code block containing `|` characters (must not be mis-parsed as table)

**`SectionSplitter` — unit tests (new test class)**

Verify the accumulation and splitting logic:
- Section under budget → single chunk returned
- Section exactly at budget → single chunk returned
- Two paragraphs that together exceed budget → split between them
- Two small paragraphs that together fit → remain in one chunk
- Horizontal rule forces flush even when under budget
- Oversized table emitted as single over-budget chunk (atomicity)
- Oversized code block emitted as single over-budget chunk (atomicity)
- Oversized bullet list split at item boundaries
- Single oversized list item falls back to sliding-window (produces multiple chunks with overlap)
- Heading prefix tokens counted against budget
- Empty heading prefix (pre-heading content) handled correctly
- Pre-heading content sub-split correctly

**`MarkdownDocumentReader` — extend existing `MarkdownDocumentReaderTest`**

Integration-level tests that verify end-to-end behaviour through the full reader:
- A large section produces multiple chunks, all with the correct heading metadata
- Each sub-chunk text starts with the heading prefix
- A small section still produces exactly one chunk (no regression)
- Horizontal rule within a section produces separate chunks
- Existing tests continue to pass unchanged

**Prior art:** `MarkdownDocumentReaderTest` shows the pattern: construct a `MarkdownDocumentReader` with a small `chunkSize`, call `read()`, and assert on `documents.size`, `documents[i].text`, and `documents[i].metadata`.

## Out of Scope

- PDF sub-splitting (PDF chunking improvements are a separate effort)
- Plain text layout-aware splitting (currently uses `TokenTextSplitter` directly)
- Inline markdown element handling (bold, italic, links — these are within paragraphs and not split boundaries)
- Footnote/citation reference tracking
- Nested block quotes or nested lists beyond item-level splitting
- Changes to the `show` command display of sub-chunks
- Changes to BM25 or semantic search ranking

## Further Notes

- The eval corpus in `.sdd/chunking-eval-corpus/` should be used to verify that retrieval quality does not regress after this change.
- A section whose content is entirely one oversized atomic block (e.g. a 5000-token table) will produce a chunk that exceeds `chunkSize`. This is intentional — splitting mid-table destroys meaning.
- The `chunkOverlap` parameter is only meaningful for the sliding-window fallback path. Layout-boundary splits are clean cuts with no overlap.
