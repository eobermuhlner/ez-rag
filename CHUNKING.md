# Chunking Strategies

ez-rag automatically selects a chunking strategy based on file extension. Each strategy is designed to preserve semantic boundaries — headings, declarations, rows, or XML elements — so that retrieved chunks carry enough context to answer a query without spilling into unrelated content.

All chunking respects a configurable **token budget** (`--chunk-size`, default 1000 tokens) and an **overlap** (`--chunk-overlap`, default 200 tokens) where relevant.

---

## Table of Contents

- [Plain Text](#plain-text-txt-and-unknown-text-files)
- [Markdown](#markdown-md)
- [PDF](#pdf-pdf)
- [HTML / XHTML](#html--xhtml-html-htm-xhtml)
- [RTF](#rtf-rtf)
- [Word](#word-docx-doc)
- [PowerPoint](#powerpoint-pptx-ppt)
- [Excel](#excel-xlsx-xls)
- [CSV](#csv-csv)
- [JSON](#json-json)
- [JSONL](#jsonl-jsonl)
- [JSONC](#jsonc-jsonc)
- [XML / SVG / RSS / Atom](#xml--svg--rss--atom-xml-svg-rss-atom)
- [Source Code](#source-code-kt-kts-java-ts-tsx-js-jsx)

---

## Plain Text (`.txt` and unknown text files)

**Strategy: token-based sliding-window split.**

The entire file is read as UTF-8 text (with replacement for malformed bytes) and split by Spring AI's `TokenTextSplitter`. Splits happen at natural sentence/word boundaries to avoid cutting mid-word.

Files with no recognised extension are also handled here if binary detection (first 8 KB) finds no null bytes.

**Input:**
```
The quick brown fox jumps over the lazy dog.
Sphinx of black quartz, judge my vow.
Pack my box with five dozen liquor jugs.
```

**Output (single chunk if within budget):**
```
The quick brown fox jumps over the lazy dog.
Sphinx of black quartz, judge my vow.
Pack my box with five dozen liquor jugs.
```

---

## Markdown (`.md`)

**Strategy: heading-aware section split.**

The document is split at `#`–`######` headings. Each heading and its body text become one or more chunks. The full ancestor heading path is prepended to every chunk so that context is preserved regardless of which chunk is retrieved.

Within a section, the body is parsed into layout blocks (paragraphs, fenced code blocks, tables, bullet lists, block quotes) and packed into chunks up to the token budget. A horizontal rule `---` forces a flush. Oversized individual blocks are split further: bullet lists item by item, paragraphs by token splitter.

YAML front matter (`---` … `---`) is stripped before chunking.

**Input:**
```markdown
# User Guide

Welcome to the guide.

## Installation

Run the installer.

### macOS

Use Homebrew: `brew install tool`.

### Windows

Download the MSI.
```

**Output chunks:**
```
# User Guide

Welcome to the guide.
```
```
# User Guide
## Installation

Run the installer.
```
```
# User Guide
## Installation
### macOS

Use Homebrew: `brew install tool`.
```
```
# User Guide
## Installation
### Windows

Download the MSI.
```

Each chunk carries metadata: `heading_title`, `heading_level`, and `heading_path` (the list of ancestor titles).

---

## PDF (`.pdf`)

**Strategy: positional text extraction → Markdown → heading-aware section split.**

PDFs are converted to Markdown using a custom positional text stripper. The RAG conversion profile is used:
- Inline bold/italic markers (`**`, `*`) are stripped (embedding noise).
- Table of Contents entries are excluded.
- Epigraph and advisory callouts are rendered as plain text, not block quotes.
- Table column spans are normalised to reduce data loss.

The resulting Markdown is then chunked by the same heading-aware strategy as `.md` files.

---

## HTML / XHTML (`.html`, `.htm`, `.xhtml`)

**Strategy: HTML → Markdown → heading-aware section split.**

HTML is parsed by Jsoup. The converter maps:
- `<h1>`–`<h6>` → `#`–`######`
- `<p>`, `<div>` → paragraphs
- `<ul>` / `<ol>` → bullet lists
- `<table>` → Markdown pipe tables
- `<pre>` / `<code>` → fenced code blocks
- `<a>` → `[text](url)`
- `<strong>` / `<b>` → `**text**`
- `<em>` / `<i>` → `*text*`

The resulting Markdown is chunked as a `.md` file. Every chunk also receives a `page_title` metadata field from the HTML `<title>` tag.

---

## RTF (`.rtf`)

**Strategy: plain text extraction → token-based split.**

Rich text formatting is stripped using `javax.swing.text.rtf.RTFEditorKit`. The resulting plain text is split the same way as `.txt` files. Heading structure from RTF is not preserved.

---

## Word (`.docx`, `.doc`)

**Strategy: paragraph/table extraction → Markdown → heading-aware section split.**

Apache POI reads the document body in order. Paragraphs with styles `Heading 1`–`Heading 6` are mapped to `#`–`######`. Tables are converted to Markdown pipe tables. Footnotes and endnotes are appended at the end. The resulting Markdown is chunked the same way as `.md` files.

Encrypted `.docx` files are supported: supply passwords via `--password`.

**Input (DOCX structure):**
```
[Heading 1] Introduction
[Paragraph] This is the introduction.
[Heading 2] Background
[Paragraph] Some background.
[Table] | Col A | Col B |
        | foo   | bar   |
```

**Output chunks:**
```
# Introduction

This is the introduction.
```
```
# Introduction
## Background

Some background.

| Col A | Col B |
| --- | --- |
| foo | bar |
```

---

## PowerPoint (`.pptx`, `.ppt`)

**Strategy: slide-by-slide Markdown → heading-aware section split.**

Each slide becomes a `## Slide N: Title` heading (or `## Slide N` if no title shape exists). The remaining text shapes on the slide become the body. Speaker notes are appended after the body.

Encrypted `.pptx` files are supported: supply passwords via `--password`.

**Input (3-slide deck):**
```
Slide 1: "Welcome"   — body: "Today's agenda"
Slide 2: "Overview"  — body: "Point 1\nPoint 2", notes: "Expand on point 2"
Slide 3: (no title)  — body: "Thank you"
```

**Output Markdown (before heading split):**
```markdown
## Slide 1: Welcome
Today's agenda
## Slide 2: Overview
Point 1
Point 2
Expand on point 2
## Slide 3
Thank you
```

Each slide becomes its own chunk (or multiple chunks if the slide body exceeds the token budget).

---

## Excel (`.xlsx`, `.xls`)

**Strategy: per-sheet row batching with repeating header.**

Each sheet is extracted as a header row plus data rows. The `TableChunker` packs as many data rows as fit within the token budget into one chunk. Every chunk begins with a `## SheetName` heading and the full header row, so each chunk is self-contained.

No row ever spans two chunks. A single row that exceeds the budget is emitted as its own chunk.

Encrypted files are supported via `--password`.

**Input (sheet "Employees"):**
```
Name    | Department | Salary
Alice   | Engineering| 95000
Bob     | Marketing  | 72000
Carol   | Engineering| 88000
```

**Output chunks (if all rows fit in budget — one chunk):**
```markdown
## Employees
| Name | Department | Salary |
| --- | --- | --- |
| Alice | Engineering | 95000 |
| Bob | Marketing | 72000 |
| Carol | Engineering | 88000 |
```

If rows overflow the budget they are split across multiple chunks, each repeating the `## SheetName` and header.

---

## CSV (`.csv`)

**Strategy: row batching with repeating header.**

The first row is the header. Data rows are packed into chunks with `TableChunker`, identical in behaviour to Excel sheets. Each chunk is a self-contained Markdown table beginning with the full header row and separator.

`chunkOverlap` is accepted for API consistency but is not applied — row overlap is not meaningful for tabular data.

**Input:**
```csv
Name,Age,City
Alice,30,London
Bob,25,Paris
Carol,35,Berlin
```

**Output (single chunk if within budget):**
```markdown
| Name | Age | City |
| --- | --- | --- |
| Alice | 30 | London |
| Bob | 25 | Paris |
| Carol | 35 | Berlin |
```

---

## JSON (`.json`)

**Strategy: recursive structure-aware chunking.**

The file is parsed with Jackson. The root node dispatches:

- **Array root**: elements are batched by token budget. Each chunk gets a heading `## Items N-M` (or `## Item N` for a single element). Elements that individually exceed the budget are emitted alone.
- **Object root**: fields are accumulated into chunks. Each chunk gets a heading `## key1 -> key2` reflecting the path. Oversized nested objects or arrays recurse deeper. Oversized string values are split with the token splitter.
- **Field rendering**: primitives render as `**key**: value`; small nested structures render as a fenced JSON code block; large nested structures recurse.

Headings carry the full key path, e.g. `## users -> address -> city`.

**Input:**
```json
[
  { "id": 1, "title": "Introduction to RAG", "author": "Alice" },
  { "id": 2, "title": "Practical Vector Databases", "author": "Bob" }
]
```

**Output (both items fit in budget — one chunk):**
```markdown
## Items 1-2

{
  "id" : 1,
  "title" : "Introduction to RAG",
  "author" : "Alice"
}

{
  "id" : 2,
  "title" : "Practical Vector Databases",
  "author" : "Bob"
}
```

If the items overflow the budget they are split, each chunk labelled `## Item N`.

---

## JSONL (`.jsonl`)

**Strategy: line-per-object → array chunking.**

Each non-blank line is parsed as an independent JSON object. The set of parsed objects is treated as a root JSON array and chunked identically to `.json` array files. Malformed lines are skipped with a warning to stderr.

**Input:**
```jsonl
{"timestamp": "2024-06-01T08:00:00Z", "level": "INFO", "message": "Job started"}
{"timestamp": "2024-06-01T08:00:03Z", "level": "INFO", "message": "Processed sample.pdf"}
{"timestamp": "2024-06-01T08:00:05Z", "level": "WARN", "message": "File skipped"}
```

**Output (all items fit in budget — one chunk):**
```markdown
## Items 1-3

{
  "timestamp" : "2024-06-01T08:00:00Z",
  "level" : "INFO",
  "message" : "Job started"
}

{
  "timestamp" : "2024-06-01T08:00:03Z",
  "level" : "INFO",
  "message" : "Processed sample.pdf"
}

{
  "timestamp" : "2024-06-01T08:00:05Z",
  "level" : "WARN",
  "message" : "File skipped"
}
```

---

## JSONC (`.jsonc`)

**Strategy: Tree-sitter CST parse → comment-preserving chunking.**

JSONC files (JSON with Comments, as used by VS Code settings, `tsconfig.json`, and similar developer-facing config files) are parsed with Tree-sitter into a concrete syntax tree. Comments are treated as first-class nodes and rendered as readable prose inside the chunk text. No comment text is discarded.

### Comment positions and rendering

| Position | Rendering |
|---|---|
| **Preceding line comment** (`// text` before a key) | Prose paragraph on the line before the `**key**: value` line |
| **Preceding block comment** (`/* text */` before a key) | Same as line comment; internal whitespace collapsed to single space; `/*` and `*/` markers stripped |
| **Trailing inline comment** (`value  // text` on same line as a value) | Appended to the value line: `**key**: value - text` |
| **File-level comments** (before the root object or array) | Prepended to every chunk as a preamble; their token cost is deducted from the per-chunk budget |

Comment text inside string literals is treated as data and is not modified.

### Whitespace normalisation

Block comments (`/* */`) may contain internal indentation. All internal whitespace runs (spaces, tabs, newlines) are collapsed to a single space, and the result is trimmed.

**Input:**
```jsonc
// ez-rag configuration
// Adjust for your environment
{
  // path where the Lucene index is stored
  "directory": ".ez-rag",
  /* Retrieval settings
     for hybrid search */
  "retrieval": {
    "mode": "hybrid",   // combines BM25 and embeddings
    "topK": 10          // result count
  }
}
```

**Output chunks:**

Chunk 1 (root object fields, with file-level preamble and preceding/trailing comments):
```markdown
ez-rag configuration Adjust for your environment

##

path where the Lucene index is stored
**directory**: .ez-rag
Retrieval settings for hybrid search
```

Chunk 2 (nested `retrieval` object):
```markdown
ez-rag configuration Adjust for your environment

## retrieval

**mode**: hybrid - combines BM25 and embeddings
**topK**: 10 - result count
```

The file-level preamble (`ez-rag configuration Adjust for your environment`) is prepended to every chunk. The per-chunk token budget is reduced by the preamble's token count so that each chunk remains within the overall `--chunk-size` limit.

---

## XML / SVG / RSS / Atom (`.xml`, `.svg`, `.rss`, `.atom`)

**Strategy: structure-aware repeated-element detection → Markdown → heading-aware section split.**

The XML tree is walked to find the natural "record" level — the deepest level where sibling elements repeat with the same tag name. Each repeated element becomes a Markdown section. Small adjacent elements (body text < `chunkSize × 3` chars) are merged into batches to avoid many tiny chunks.

Headings reflect the full ancestor path: `## root > parent > child`.

**Boundary tag mode** (`--xml-boundary-tags`): instead of auto-detection, specific tag names are supplied. All matching elements throughout the tree become section boundaries, grouped under their ancestor path heading.

Attributes are included inline as `tagName[attr=value]: text`. Namespace prefixes are stripped from tag names. XML comments are ignored.

**Input (RSS feed excerpt):**
```xml
<rss>
  <channel>
    <title>Tech News</title>
    <item>
      <title>AI breakthrough</title>
      <description>Researchers achieve new benchmark.</description>
    </item>
    <item>
      <title>New framework released</title>
      <description>Version 2.0 ships today.</description>
    </item>
  </channel>
</rss>
```

**Output Markdown (before section split):**
```markdown
## rss > channel > item
item:
  title: AI breakthrough
  description: Researchers achieve new benchmark.
item:
  title: New framework released
  description: Version 2.0 ships today.
```

Each `item` section (or merged batch) becomes a chunk.

---

## Source Code (`.kt`, `.kts`, `.java`, `.ts`, `.tsx`, `.js`, `.jsx`)

**Strategy: AST-based declaration-per-chunk.**

Source files are parsed with Tree-sitter into an AST. Each top-level declaration — class, interface, function, method, or constructor — becomes one chunk. Nested classes are extracted separately; their bodies are replaced with `{ /* ... */ }` in the parent class chunk to keep the class chunk concise.

Each chunk contains:
1. A Markdown heading reflecting the declaration path (`## ClassName` / `## ClassName\n### methodName`).
2. A fenced code block tagged with the language (`kotlin`, `java`, `typescript`, or `javascript`).
3. Inside the code block: the package declaration and imports, then the full declaration text.

KDoc / JSDoc comments immediately preceding a declaration are included in the chunk.

If a single declaration exceeds the token budget it is split at blank lines (paragraph boundaries), with each part repeating the heading and code-fence envelope.

Chunk metadata includes: `language`, `declaration_type` (class/function/method/constructor), `declaration_name`, and `class_name` for methods.

**Supported languages and extensions:**

| Extension | Language | Parser |
| --- | --- | --- |
| `.kt`, `.kts` | kotlin | Tree-sitter Kotlin |
| `.java` | java | Tree-sitter Java |
| `.ts`, `.tsx` | typescript | Tree-sitter TypeScript |
| `.js`, `.jsx` | javascript | Tree-sitter JavaScript |

**Input (Kotlin):**
```kotlin
package com.example

/**
 * Computes a greeting.
 */
class Greeter(val name: String) {
    fun greet(): String {
        return "Hello, $name!"
    }
}

fun helper(x: Int): Int = x * 2
```

**Output chunks:**

Chunk 1 — the class:
````markdown
## Greeter

```kotlin
package com.example

/**
 * Computes a greeting.
 */
class Greeter(val name: String) {
    fun greet(): String { ... }
}
```
````

Chunk 2 — the method inside the class:
````markdown
## Greeter
### greet

```kotlin
package com.example

fun greet(): String {
    return "Hello, $name!"
}
```
````

Chunk 3 — the top-level function:
````markdown
## helper

```kotlin
package com.example

fun helper(x: Int): Int = x * 2
```
````
