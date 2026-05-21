# Tasks: Markdown Sub-Chunk Splitting

## Task 01-token-counter

Create a `TokenCounter` utility that wraps the `jtokkit` library (`cl100k_base` encoding) to count tokens in a string. This is the shared measurement primitive used by `SectionSplitter` to decide when a budget is exceeded. It must be callable as a plain `(String) -> Int` function reference so higher-level modules can accept it as an injectable dependency, allowing tests to substitute a deterministic approximation.

### Implementation steps

- [x] Create `TokenCounter` object with a `countTokens(text: String): Int` function using jtokkit `cl100k_base` encoding
- [x] Write a smoke test asserting `countTokens("hello world")` returns a small positive integer (≥ 1, ≤ 10)
- [x] Verify jtokkit is already on the classpath transitively via Spring AI; add one explicit dependency only if it is not present

### Acceptance criteria

- [x] `countTokens("hello world")` returns a value between 1 and 10 inclusive
- [x] `countTokens("")` returns 0
- [x] `countTokens` is callable as `(String) -> Int` without instantiation (plain function or singleton object)

### Quality gates

- [x] If jtokkit is not already a transitive dependency, exactly one explicit dependency line is added and documented in the commit message
- [x] Zero compiler warnings in new code
- [x] All tests pass with `./gradlew test`

---

## Task 02-layout-block-parser

Create `LayoutBlockParser` — a pure function that parses a raw markdown body string into an ordered list of typed `LayoutBlock` objects. This is the structural foundation `SectionSplitter` uses to identify semantic split points.

Block types (sealed hierarchy):
- `Paragraph` — consecutive non-blank lines not matching any other rule
- `FencedCodeBlock` — lines between ` ``` ` or `~~~` fences (atomic)
- `Table` — consecutive lines containing `|` (atomic)
- `BulletList(items: List<String>)` — consecutive lines starting with `- `, `* `, `+ `, or `N. `; each item is individually accessible
- `BlockQuote` — consecutive lines starting with `> ` (atomic)
- `HorizontalRule` — a single line matching `---+`, `___+`, or `\*\*\*+` after trimming (signals forced flush in the splitter)

Detection priority (highest first): `FencedCodeBlock` > `Table` > `BulletList` > `BlockQuote` > `HorizontalRule` > `Paragraph`. Blank lines are separators between blocks, not blocks themselves.

### Implementation steps

- [x] Define sealed class `LayoutBlock` with subtypes `Paragraph`, `FencedCodeBlock`, `Table`, `BulletList`, `BlockQuote`, `HorizontalRule`
- [x] Implement `LayoutBlockParser.parse(text: String): List<LayoutBlock>` with priority-ordered detection
- [x] Write unit tests for: each block type in isolation, mixed content sequence, priority conflict (pipe characters inside a fenced code block must not produce a `Table`)

### Acceptance criteria

- [x] Two paragraphs separated by a blank line parse as exactly two `Paragraph` blocks
- [x] A fenced code block containing `|` characters parses as `FencedCodeBlock`, not `Table`
- [x] A bullet list parses as a single `BulletList` with each item individually accessible via `items`
- [x] A numbered list (`1. item`, `2. item`) parses as a `BulletList`
- [x] `---`, `___`, and `***` on their own line each parse as `HorizontalRule`
- [x] A document mixing all block types returns them in the correct order with the correct subtype for each
- [x] Empty string and whitespace-only input return an empty list

### Quality gates

- [x] Zero compiler warnings in new code
- [x] All tests pass with `./gradlew test`

---

## Task 03-section-splitter

Create `SectionSplitter` — the greedy layout-aware accumulation engine that sub-splits a markdown section body into token-bounded sub-chunks. Depends on `LayoutBlockParser` (Task 02) and the injectable token-counter signature from Task 01.

Constructor: `SectionSplitter(chunkSize: Int, chunkOverlap: Int, tokenCounter: (String) -> Int)`.  
Primary method: `splitSection(bodyText: String, headingPrefix: String): List<String>`.

Algorithm:
1. `prefixCost = tokenCounter(headingPrefix + "\n")` (0 when prefix is empty). Body budget = `chunkSize − prefixCost`.
2. Parse `bodyText` into `LayoutBlock` list via `LayoutBlockParser`.
3. Accumulate blocks into a buffer:
   - `HorizontalRule` → force-flush buffer (rule text discarded); start fresh.
   - Other block → if `tokenCounter(buffer + block) ≤ budget`: add to buffer.
   - Over budget, buffer non-empty → flush buffer as a sub-chunk; start fresh with the current block.
   - Over budget, buffer empty (single oversized block):
     - `BulletList` → split at item boundaries; a single oversized item falls back to `TokenTextSplitter`.
     - All other atomic blocks → apply `TokenTextSplitter(chunkSize, chunkOverlap)`.
4. Flush remaining buffer as final sub-chunk.
5. Prepend `headingPrefix + "\n"` to every returned chunk (skip when prefix is empty).

**Overlap policy:** `chunkOverlap` is only used inside `TokenTextSplitter` fallback calls. Layout-block boundary splits carry zero overlap — no content is duplicated between chunks produced by the greedy accumulation step.

### Implementation steps

- [x] Implement `SectionSplitter` with the greedy accumulation loop
- [x] Subtract prefix token cost from budget before accumulation begins
- [x] Handle `HorizontalRule` as forced flush with rule text discarded
- [x] Handle atomic block overflow: emit as a single (potentially over-budget) chunk
- [x] Handle `BulletList` overflow: split at item boundaries; apply `TokenTextSplitter` to a single oversized item
- [x] Apply `TokenTextSplitter(chunkSize, chunkOverlap)` for all other oversized atomic blocks
- [x] Prepend heading prefix to every returned chunk; skip when prefix is empty
- [x] Write unit tests using an injected word-count lambda as `tokenCounter` so tests are fast and deterministic

### Acceptance criteria

- [x] A body that fits within `chunkSize − prefixCost` returns exactly one chunk
- [x] Two paragraphs whose combined token count exceeds the budget are split into two separate chunks
- [x] Two small paragraphs whose combined token count fits within the budget remain in one chunk
- [x] Chunks produced by layout-block boundary splits contain no duplicated content (zero overlap between adjacent chunks)
- [x] A `HorizontalRule` between two paragraphs that each fit within budget produces two chunks; neither chunk contains `---`, `___`, or `***`
- [x] An oversized table (single block > budget) is emitted as exactly one chunk even though it exceeds `chunkSize`
- [x] An oversized fenced code block is emitted as exactly one chunk even though it exceeds `chunkSize`
- [x] A `BulletList` whose total token count exceeds budget is split between items; no item is split mid-text
- [x] A single oversized list item produces multiple chunks via `TokenTextSplitter`; consecutive chunks share approximately `chunkOverlap` tokens of content
- [x] All returned chunk strings begin with `headingPrefix + "\n"` when `headingPrefix` is non-empty
- [x] When `headingPrefix` is empty, returned chunks do not start with a leading `"\n"`
- [x] A body whose tokens fit individually but exceed `chunkSize − prefixCost` (due to prefix cost) is correctly split — demonstrating that prefix cost is subtracted from the budget

### Quality gates

- [x] All `SectionSplitter` unit tests use an injected `tokenCounter` lambda (e.g. word count) — no real jtokkit calls in unit tests
- [x] Zero compiler warnings in new code
- [x] All tests pass with `./gradlew test`

---

## Task 04-markdown-reader-integration

Wire `SectionSplitter` into `MarkdownDocumentReader` so that every section body — including content before the first heading — is automatically sub-split when it exceeds `chunkSize`. All `Document` objects from the same section share identical heading metadata. Extend `MarkdownDocumentReaderTest` with integration-level scenarios and confirm the eval corpus continues to pass.

Note: YAML front matter is stripped by `stripYamlFrontMatter()` before any line reaches the chunking logic. The front-matter delimiter `---` is therefore never seen by `LayoutBlockParser`. However, a `---` horizontal rule inside a section body must be processed by `LayoutBlockParser` as a `HorizontalRule`, not re-entered into front-matter stripping. A regression test must confirm that the front-matter stripper does not affect `---` rules that appear mid-document.

### Implementation steps

- [x] Modify `MarkdownDocumentReader.flushBuffer()` to call `SectionSplitter.splitSection()` and emit one `Document` per returned string
- [x] Each `Document` produced from a section carries the same `heading_title`, `heading_level`, and `heading_path` metadata as the original single-chunk implementation
- [x] Add integration test: a section whose content exceeds `chunkSize` produces more than one `Document`, each with the correct heading metadata and heading prefix at the start of its text
- [x] Add integration test: a section whose content fits within `chunkSize` still produces exactly one `Document` (no regression)
- [x] Add integration test: a `---` horizontal rule within a section body produces two `Document` objects from that section
- [x] Add regression test: a document with YAML front matter followed later by a `---` horizontal rule produces a chunk for each side of the rule but no chunk containing front-matter content
- [x] Confirm all pre-existing `MarkdownDocumentReaderTest` cases pass without modification
- [x] Run eval corpus tests and confirm they pass

### Acceptance criteria

- [x] A markdown section exceeding `chunkSize` tokens produces multiple `Document` objects
- [x] Every `Document` from the same section has identical `heading_title`, `heading_level`, and `heading_path` values
- [x] Every `Document` from a heading section has text starting with the full heading hierarchy (e.g. `# H1\n## H2\n`)
- [x] A section fitting within `chunkSize` produces exactly one `Document`
- [x] A `---` horizontal rule inside a section body produces two `Document` objects from that section; neither contains `---`
- [x] A document with YAML front matter (`---` delimited) followed by a `---` horizontal rule mid-body: front-matter content does not appear in any chunk
- [x] All pre-existing `MarkdownDocumentReaderTest` tests pass without modification
- [x] Eval corpus tests pass

### Quality gates

- [x] Zero compiler warnings in new or modified code
- [x] `./gradlew test` passes with all tests green
