# Source Code Ingestion

## Problem Statement

When developers ingest a software repository into ez-rag, source code files are currently treated as plain text and split at fixed token boundaries. This produces chunks that cut across function and class boundaries, making retrieval unreliable: a search for "how does `validatePassword` work" may return a chunk containing only part of the method body, or a chunk that mixes unrelated methods. The resulting answers from LLMs are incomplete or misleading.

## Solution

ez-rag gains first-class support for ingesting Kotlin, Java, and TypeScript/JavaScript source files. Each file is split at meaningful structural boundaries — class declarations and method/function bodies — so that each retrieved chunk corresponds to a coherent, self-contained unit of code. Package declarations and imports are included in every chunk to preserve context. A plain-text fallback continues to handle all other file types unchanged.

## User Stories

1. As a developer, I want Kotlin source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
2. As a developer, I want Java source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
3. As a developer, I want TypeScript source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
4. As a developer, I want JavaScript source files to be chunked at class and function boundaries, so that retrieval returns complete, meaningful code units.
5. As a developer, I want `.kt` and `.kts` files to be automatically recognised as Kotlin and processed with source-code-aware chunking, so that I do not need to configure anything extra.
6. As a developer, I want `.java` files to be automatically recognised as Java, so that they are processed with source-code-aware chunking without extra configuration.
7. As a developer, I want `.ts`, `.tsx`, `.js`, and `.jsx` files to be automatically recognised and processed with source-code-aware chunking, so that TypeScript and JavaScript projects work out of the box.
8. As a developer, I want doc comments (KDoc, Javadoc, JSDoc) to be included in the chunk for the declaration they document, so that natural-language descriptions are retrievable alongside the code they describe.
9. As a developer, I want package and import declarations to be included in every chunk, so that each chunk contains enough context to understand the dependencies and aliases used.
10. As a developer, I want each method chunk to carry metadata identifying the language, declaration type, enclosing class name, and declaration name, so that search results indicate exactly where a chunk came from.
11. As a developer, I want each class header chunk to carry metadata identifying the language, declaration type, and class name, so that I can distinguish class-level chunks from method-level chunks.
12. As a developer, I want nested and inner classes to be chunked independently from their enclosing class, so that each class is retrievable on its own merits.
13. As a developer, I want the qualified name (e.g. `Outer.Inner`) to appear in the metadata for nested classes, so that the containment relationship is preserved.
14. As a developer, I want top-level functions (outside any class) to each produce their own chunk, so that module-level functions in Kotlin, TypeScript, and JavaScript are individually retrievable.
15. As a developer, I want the class header chunk to include the class signature, field declarations, and constructors (but not method bodies), so that searching for the structure of a class returns focused results.
16. As a developer, I want method chunks that exceed the configured chunk size to be token-split as a safety valve, so that very large generated or minified files do not produce unbounded chunk sizes.
17. As a developer, I want source files for languages not in the first-class set to continue falling through to plain-text ingestion, so that my existing ingestion behaviour is not disrupted.
18. As a developer, I want source code ingestion to respect the same `--chunk-size` and `--chunk-overlap` options as all other formats, so that I have a single consistent way to tune chunking across my repository.
19. As a developer, I want the MCP `ingest` tool to handle source code files using the same source-code-aware chunking as the CLI, so that AI assistants using the MCP interface also benefit.

## User Acceptance Tests

1. Given a Kotlin file containing a class with two methods, when the file is ingested, then each method body appears in its own separate chunk and no chunk contains parts of two different methods.
2. Given a Kotlin file containing a class, when the file is ingested, then one chunk contains the class signature and field declarations but no method bodies.
3. Given a Java file containing a class with a Javadoc comment on a method, when the file is ingested, then the method's chunk includes the Javadoc text.
4. Given a TypeScript file with import aliases (e.g. `import { Foo as Bar } from './foo'`), when the file is ingested, then the import line including the alias appears in every chunk produced from that file.
5. Given a Kotlin file with a top-level function (outside any class), when the file is ingested, then the function produces its own chunk.
6. Given a Kotlin file with a nested class, when the file is ingested, then the nested class and its methods produce independent chunks, and the chunk metadata reflects the qualified class name (e.g. `Outer.Inner`).
7. Given a TypeScript `.tsx` file, when the file is ingested, then it is processed with source-code-aware chunking (not plain-text fallback).
8. Given a JavaScript `.js` file, when the file is ingested, then it is processed with source-code-aware chunking.
9. Given a Go `.go` file, when the file is ingested, then it falls through to plain-text ingestion (no error, tokens split at fixed boundaries).
10. Given a very large generated Kotlin file where a single method body exceeds `--chunk-size`, when the file is ingested, then the method is split into multiple chunks rather than producing one oversized chunk.
11. Given a source file ingested with `--chunk-size 500`, when chunks are inspected, then no chunk exceeds 500 tokens.
12. Given a Kotlin file ingested via the MCP `ingest` tool, when the MCP `search` tool is queried with the name of a method in that file, then the returned chunk contains that method's body.

## Definition of Done

- All user acceptance tests pass.
- Kotlin, Java, and TypeScript/JavaScript files are automatically chunked at structural boundaries with no additional CLI options required.
- Each chunk carries `language`, `declaration_type`, `class_name`, and `declaration_name` metadata fields.
- Oversized method chunks are token-split as a safety valve; no chunk exceeds the configured chunk size.
- All other file types continue to ingest without regression.
- `--chunk-size` and `--chunk-overlap` options apply to source code files identically to all other formats.
- README updated to document supported source code file types.
- All automated tests pass with `./gradlew test`.

## Out of Scope

- AST-based parsing (tree-sitter or compiler APIs); all parsing uses regex heuristics.
- Languages beyond Kotlin, Java, TypeScript, and JavaScript (these fall through to plain-text ingestion).
- Semantic understanding of generics, annotations, or decorators beyond what is needed to locate declaration boundaries.
- Minified or obfuscated source files (these may not chunk well; no special handling is required).
- Cross-file symbol resolution or call-graph analysis.
- A dedicated `--source-code-aware` opt-in flag; chunking is always applied based on file extension.

## Further Notes

The regex-based approach was chosen over AST parsing (e.g. tree-sitter) to avoid native library dependencies. The heuristics are expected to handle idiomatic code well but may produce imperfect splits for unusual formatting, deeply nested lambdas, or minified files. This is acceptable for the initial implementation.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New classes

**`SourceDeclaration`** (data class, `ingestion/sourcecode` package)

```kotlin
enum class DeclarationType { CLASS, METHOD, FUNCTION }

data class SourceDeclaration(
    val declarationType: DeclarationType,
    val declarationName: String,
    val className: String?,       // null for top-level functions
    val packageName: String?,
    val imports: List<String>,
    val fullText: String,         // doc comment + signature + body (no method bodies for CLASS type)
)
```

**`SourceCodeParser`** (interface, `ingestion/sourcecode` package)

```kotlin
interface SourceCodeParser {
    val language: String
    fun parse(source: String): List<SourceDeclaration>
}
```

Three regex-based implementations:
- `KotlinSourceCodeParser : SourceCodeParser` — handles `.kt`, `.kts`
- `JavaSourceCodeParser : SourceCodeParser` — handles `.java`
- `TypeScriptSourceCodeParser : SourceCodeParser` — handles `.ts`, `.tsx`, `.js`, `.jsx`

Each implementation:
1. Extracts `package` declaration (first non-comment, non-blank line matching `package …` / `package …;`)
2. Extracts all `import` lines (including aliases: `import … as …` / `import { … as … } from …`)
3. Detects top-level and nested class/interface/object declarations by regex + brace-depth tracking
4. Within each class: emits one `CLASS` declaration (signature + fields + constructors, no method bodies)
5. Within each class: emits one `METHOD` declaration per method/function
6. At file scope: emits one `FUNCTION` declaration per top-level function
7. Nested classes are flattened; `className` carries the qualified name (e.g. `Outer.Inner`)

**`SourceCodeDocumentReader`** (`ingestion/sourcecode` package)

```kotlin
class SourceCodeDocumentReader(
    private val source: String,
    private val parser: SourceCodeParser,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    fun read(): List<Document>
}
```

Per declaration, `read()`:
1. Builds heading prefix from package + imports + synthetic Markdown headings:
   - Class chunk: `## ClassName`
   - Method chunk: `## ClassName\n### methodName` (or `## methodName` for top-level functions)
2. Calls `SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens).splitSection(declaration.fullText, headingPrefix)` to obtain sub-chunks (token-split safety valve is handled inside `SectionSplitter`)
3. Builds one `Document` per sub-chunk with metadata:
   - `heading_title` — `declarationName`
   - `heading_path` — e.g. `["ClassName", "methodName"]`
   - `language` — `parser.language` (e.g. `"kotlin"`)
   - `declaration_type` — `declarationType.name.lowercase()` (e.g. `"method"`)
   - `class_name` — `className` (null omitted)
   - `declaration_name` — `declarationName`

#### Modified classes

**`DocumentReaderRegistry`** — add entries to the `readers` map:

```kotlin
"kt"  to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
"kts" to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
"java" to { file -> SourceCodeDocumentReader(file.readText(), JavaSourceCodeParser(), chunkSize, chunkOverlap).read() },
"ts"  to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser(), chunkSize, chunkOverlap).read() },
"tsx" to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser(), chunkSize, chunkOverlap).read() },
"js"  to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser(), chunkSize, chunkOverlap).read() },
"jsx" to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser(), chunkSize, chunkOverlap).read() },
```

#### Data flow

```
File (.kt/.java/.ts/etc.)
  → SourceCodeDocumentReader.read()
      → SourceCodeParser.parse(source)          # regex extraction
          → List<SourceDeclaration>
      → per declaration:
          → SectionSplitter.splitSection(...)   # token-split safety valve
          → Document(text, metadata)
  → List<Document>
  → LuceneRepository (via IngestService, unchanged)
```

#### Existing classes NOT modified

- `IngestService`, `IngestCommand`, `McpIngestTool`, `McpReIngestTool` — no changes required; they go through `DocumentReaderRegistry`.
- `SectionSplitter`, `MarkdownDocumentReader`, `TokenCounter` — used as-is; `SourceCodeDocumentReader` calls `SectionSplitter` directly.

### Automated Testing Decisions

**What makes a good test:** Tests assert on externally observable behaviour — the `List<Document>` returned by `read()`, the `List<SourceDeclaration>` returned by `parse()`, or the extensions handled by the registry. Tests do not assert on internal regex strings, intermediate state, or the number of times a helper is called.

**Test classes and types:**

| Test class | Type | What it covers |
|---|---|---|
| `KotlinSourceCodeParserTest` | Unit | Parsing: class + fields, methods, top-level functions, nested classes, KDoc, imports/aliases, package extraction |
| `JavaSourceCodeParserTest` | Unit | Parsing: class + fields, methods, constructors, Javadoc, imports, package extraction |
| `TypeScriptSourceCodeParserTest` | Unit | Parsing: classes, methods, top-level functions, JSDoc, imports with aliases, `.tsx` patterns |
| `SourceCodeDocumentReaderTest` | Unit | Metadata fields on returned `Document` objects; correct heading structure; oversized method token-split |
| `DocumentReaderRegistryTest` | Unit | Each new extension routes to source-code-aware reader (extend existing test) |

**Prior art:** `HtmlDocumentReaderTest`, `MarkdownDocumentReaderTest`, `SectionSplitterTest` — all test reader/splitter behaviour by passing inline strings and asserting on `Document` metadata and text content. Follow the same pattern: construct the reader with an inline source string (not a file), call `read()` or `parse()`, assert on the returned list.
