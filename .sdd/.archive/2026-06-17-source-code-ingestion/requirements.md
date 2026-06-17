# Source Code Ingestion

## Problem Statement

When developers ingest a software repository into ez-rag, source code files are treated as plain text and split at fixed token boundaries. This produces chunks that cut across function and class boundaries, making retrieval unreliable: a search for "how does `validatePassword` work" may return a chunk containing only part of the method body, or a chunk that mixes unrelated methods. The resulting answers from LLMs are incomplete or misleading.

## Solution

ez-rag gains first-class support for ingesting Kotlin, Java, and TypeScript/JavaScript source files. Each file is parsed using a proven AST parser (tree-sitter) and split at meaningful structural boundaries — class declarations, method and function bodies, constructors, and interface method signatures — so that each retrieved chunk corresponds to a coherent, self-contained unit of code. Package declarations and imports are included in every chunk to preserve context. A plain-text fallback continues to handle all other file types unchanged.

## User Stories

1. As a developer, I want Kotlin source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
2. As a developer, I want Java source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
3. As a developer, I want TypeScript source files to be chunked at class and method boundaries, so that retrieval returns complete, meaningful code units.
4. As a developer, I want JavaScript source files to be chunked at class and function boundaries, so that retrieval returns complete, meaningful code units.
5. As a developer, I want `.kt` and `.kts` files to be automatically recognised as Kotlin and processed with source-code-aware chunking, so that I do not need to configure anything extra.
6. As a developer, I want `.java` files to be automatically recognised as Java, so that they are processed with source-code-aware chunking without extra configuration.
7. As a developer, I want `.ts` files to be automatically recognised and processed with source-code-aware chunking, so that TypeScript projects work out of the box.
8. As a developer, I want `.tsx` and `.jsx` files to be automatically recognised and processed with source-code-aware chunking, so that JSX/TSX projects work out of the box.
9. As a developer, I want `.js` files to be automatically recognised as JavaScript and processed with source-code-aware chunking.
10. As a developer, I want doc comments (KDoc, Javadoc, JSDoc) attached to a declaration to be included in that declaration's chunk, so that natural-language descriptions are retrievable alongside the code they describe.
11. As a developer, I want package and import declarations to be included in every chunk, so that each chunk contains enough context to understand the dependencies and aliases used.
12. As a developer, I want each chunk to carry metadata identifying the language, declaration type, enclosing class name, and declaration name, so that search results indicate exactly where a chunk came from.
13. As a developer, I want nested and inner classes to be chunked independently from their enclosing class, so that each class is retrievable on its own merits.
14. As a developer, I want the qualified name (e.g. `Outer.Inner`) to appear in the metadata for nested classes, so that the containment relationship is preserved.
15. As a developer, I want top-level functions (outside any class) to each produce their own chunk, so that module-level functions in Kotlin, TypeScript, and JavaScript are individually retrievable.
16. As a developer, I want constructors to each produce their own chunk, so that searching for how to instantiate a class returns a focused result.
17. As a developer, I want interface and abstract method signatures to each produce their own chunk, so that searching for what a contract declares returns a focused result.
18. As a developer, I want the class chunk to include the full class body minus the bodies of any nested classes, so that searching for the overall structure of a class returns a focused result without pulling in unrelated nested declarations.
19. As a developer, I want method chunks that exceed the configured chunk size to be split as a safety valve, so that very large generated or minified files do not produce unbounded chunk sizes.
20. As a developer, I want source files for languages not in the first-class set to continue falling through to plain-text ingestion, so that my existing ingestion behaviour is not disrupted.
21. As a developer, I want source code ingestion to respect the same `--chunk-size` and `--chunk-overlap` options as all other formats, so that I have a single consistent way to tune chunking across my repository.
22. As a developer, I want the MCP `ingest` tool to handle source code files using the same source-code-aware chunking as the CLI, so that AI assistants using the MCP interface also benefit.

## User Acceptance Tests

1. Given a Kotlin file containing a class with two methods, when the file is ingested, then each method body appears in its own separate chunk and no chunk contains parts of two different methods.
2. Given a Kotlin file containing a class, when the file is ingested, then one chunk contains the class body (excluding nested class bodies) and separate chunks exist for each method.
3. Given a Java file containing a class with a Javadoc comment on a method, when the file is ingested, then the method's chunk includes the Javadoc text.
4. Given a TypeScript file with import aliases (e.g. `import { Foo as Bar } from './foo'`), when the file is ingested, then the import line including the alias appears in every chunk produced from that file.
5. Given a Kotlin file with a top-level function (outside any class), when the file is ingested, then the function produces its own chunk.
6. Given a Kotlin file with a nested class, when the file is ingested, then the nested class produces its own chunk with metadata reflecting the qualified class name (e.g. `Outer.Inner`).
7. Given a TypeScript `.tsx` file, when the file is ingested, then it is processed with source-code-aware chunking (not plain-text fallback).
8. Given a JavaScript `.js` file, when the file is ingested, then it is processed with source-code-aware chunking.
9. Given a Go `.go` file, when the file is ingested, then it falls through to plain-text ingestion with no error.
10. Given a Java file with a constructor, when the file is ingested, then the constructor produces its own chunk.
11. Given a TypeScript interface, when the file is ingested, then each method signature in the interface produces its own chunk.
12. Given a very large generated Kotlin file where a single method body exceeds `--chunk-size`, when the file is ingested, then the method is split into multiple chunks rather than producing one oversized chunk.
13. Given a source file ingested with `--chunk-size 500`, when chunks are inspected, then no chunk exceeds 500 tokens.
14. Given a Kotlin file ingested via the MCP `ingest` tool, when the MCP `search` tool is queried with the name of a method in that file, then the returned chunk contains that method's body.
15. Given a chunk retrieved from a Kotlin source file, then the chunk text begins with the markdown heading path (e.g. `## ClassName\n### methodName`) followed by a fenced Kotlin code block containing the package declaration, imports, and declaration source.

## Definition of Done

- All user acceptance tests pass.
- Kotlin, Java, TypeScript, and JavaScript files are automatically chunked at structural boundaries with no additional CLI options required.
- Each chunk carries `language`, `declaration_type`, `class_name`, and `declaration_name` metadata fields, as well as `heading_title` and `heading_path` consistent with other document readers.
- Oversized declarations are split via the existing safety valve; no chunk exceeds the configured chunk size.
- All other file types continue to ingest without regression.
- `--chunk-size` and `--chunk-overlap` options apply to source code files identically to all other formats.
- README updated to document supported source code file types.
- All automated tests pass with `./gradlew test`.

## Out of Scope

- Languages beyond Kotlin, Java, TypeScript, and JavaScript (these fall through to plain-text ingestion).
- Filtering or summarising imports (all imports are included verbatim in every chunk).
- Cross-file symbol resolution or call-graph analysis.
- Semantic understanding of generics, annotations, or decorators beyond what is needed to locate declaration boundaries.
- Minified or obfuscated source files (these may not chunk well; no special handling required).
- A dedicated `--source-code-aware` opt-in flag; chunking is always applied based on file extension.
- Supporting AST query customisation by the user.

## Further Notes

Tree-sitter was chosen over regex-based heuristics because its grammars are the same battle-tested parsers used by VS Code, Neovim, and GitHub, giving correctness guarantees that regex + brace-depth tracking cannot provide. The `io.github.bonede` (tree-sitter-ng) library was chosen because it is the only JVM binding that publishes prebuilt Maven artifacts for all four target languages (Kotlin, Java, TypeScript, JavaScript/TSX). The project already ships JNI native libraries (ONNX Runtime, DJL Tokenizers), so adding further JNI dependencies is consistent with the existing dependency profile.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New Gradle dependencies (`build.gradle.kts`)

```kotlin
implementation("io.github.bonede:tree-sitter:<latest>")
implementation("io.github.bonede:tree-sitter-kotlin:<latest>")
implementation("io.github.bonede:tree-sitter-java:<latest>")
implementation("io.github.bonede:tree-sitter-javascript:<latest>")
implementation("io.github.bonede:tree-sitter-typescript:<latest>")
implementation("io.github.bonede:tree-sitter-tsx:<latest>")
```

Resolve exact versions from Maven Central at implementation time. Known versions as of planning: core `0.26.3.1`, kotlin `0.3.8.1`, java `0.23.5`, typescript `0.23.2`.

#### New classes (package: `ch.obermuhlner.ezrag.ingestion.sourcecode`)

**`DeclarationType`** (enum)

```kotlin
enum class DeclarationType { CLASS, METHOD, FUNCTION, CONSTRUCTOR }
```

`METHOD` covers both concrete methods and interface/abstract method signatures. `CONSTRUCTOR` covers Java constructors and Kotlin secondary constructors. Kotlin primary constructors are captured as part of the `CLASS` declaration.

**`SourceDeclaration`** (data class)

```kotlin
data class SourceDeclaration(
    val declarationType: DeclarationType,
    val declarationName: String,
    val className: String?,       // null for top-level functions; "Outer.Inner" for nested classes
    val packageName: String?,
    val imports: List<String>,
    val fullText: String,         // doc comment + signature + body (for CLASS: full body minus nested class bodies)
)
```

**`SourceCodeParser`** (interface)

```kotlin
interface SourceCodeParser {
    val language: String           // e.g. "kotlin", "java", "typescript", "javascript"
    fun parse(source: String): List<SourceDeclaration>
}
```

Three implementations:
- `KotlinSourceCodeParser` — grammars `.kt`, `.kts`; uses `tree-sitter-kotlin`
- `JavaSourceCodeParser` — grammar `.java`; uses `tree-sitter-java`
- `TypeScriptSourceCodeParser` — grammars `.ts`, `.tsx`, `.js`, `.jsx`; uses `tree-sitter-typescript` or `tree-sitter-tsx` depending on extension (caller selects grammar at construction time); language string is `"typescript"` for `.ts`/`.tsx` and `"javascript"` for `.js`/`.jsx`

Each implementation:
1. Parses the source with tree-sitter
2. Extracts package declaration and all import lines
3. Walks the AST to find class, method, constructor, and top-level function nodes
4. For each class: emits one `CLASS` declaration (full class body, nested class bodies replaced with their signatures only)
5. For each method/constructor/interface-method: emits one `METHOD` or `CONSTRUCTOR` declaration
6. For each top-level function: emits one `FUNCTION` declaration
7. Nested classes are flattened; `className` carries the qualified name (e.g. `Outer.Inner`)

**`SourceCodeDocumentReader`**

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
1. Builds heading prefix:
   - Class chunk: `## ClassName`
   - Method/constructor chunk: `## ClassName\n### declarationName` (or `## declarationName` for top-level functions)
2. Builds fenced code block body:
   ```
   ```<language>
   <packageName line if present>
   <import lines>

   <declaration.fullText>
   ```
   ```
3. Calls `SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens).splitSection(body, headingPrefix)` for the safety valve
4. Builds one `Document` per sub-chunk with metadata:
   - `heading_title` → `declarationName`
   - `heading_path` → e.g. `["ClassName", "methodName"]` (single-element for class/top-level function)
   - `language` → `parser.language`
   - `declaration_type` → `declarationType.name.lowercase()` (e.g. `"method"`, `"class"`, `"function"`, `"constructor"`)
   - `class_name` → `className` (omitted if null)
   - `declaration_name` → `declarationName`

#### Modified classes

**`DocumentReaderRegistry`** — add entries to the `readers` map:

```kotlin
"kt"  to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
"kts" to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
"java" to { file -> SourceCodeDocumentReader(file.readText(), JavaSourceCodeParser(), chunkSize, chunkOverlap).read() },
"ts"  to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("typescript"), chunkSize, chunkOverlap).read() },
"tsx" to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("tsx"), chunkSize, chunkOverlap).read() },
"js"  to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("javascript"), chunkSize, chunkOverlap).read() },
"jsx" to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("jsx"), chunkSize, chunkOverlap).read() },
```

#### Data flow

```
File (.kt/.java/.ts/etc.)
  → SourceCodeDocumentReader.read()
      → SourceCodeParser.parse(source)          # tree-sitter AST extraction
          → List<SourceDeclaration>
      → per declaration:
          → build fenced code block (package + imports + fullText)
          → SectionSplitter.splitSection(body, headingPrefix)   # safety valve
          → Document(text, metadata)
  → List<Document>
  → LuceneRepository (via IngestService, unchanged)
```

#### Existing classes NOT modified

- `IngestService`, `IngestCommand`, `McpIngestTool`, `McpReIngestTool` — no changes required; they go through `DocumentReaderRegistry`.
- `SectionSplitter`, `MarkdownDocumentReader`, `TokenCounter` — used as-is.

### Automated Testing Decisions

**What makes a good test:** Tests assert on externally observable behaviour — the `List<Document>` returned by `read()`, the `List<SourceDeclaration>` returned by `parse()`, or the extensions handled by the registry. Tests do not assert on internal tree-sitter node types, intermediate state, or query strings.

**Test classes and types:**

| Test class | Type | What it covers |
|---|---|---|
| `KotlinSourceCodeParserTest` | Unit | Parsing: class + methods, top-level functions, nested classes, secondary constructors, KDoc, imports, package |
| `JavaSourceCodeParserTest` | Unit | Parsing: class + methods, constructors, interface method signatures, Javadoc, imports, package |
| `TypeScriptSourceCodeParserTest` | Unit | Parsing: classes, methods, top-level functions, interface signatures, JSDoc, imports with aliases, `.tsx` patterns |
| `SourceCodeDocumentReaderTest` | Unit | Metadata fields on returned `Document` objects; correct heading structure; fenced code block format; oversized method safety-valve split |
| `DocumentReaderRegistryTest` | Unit | Each new extension routes to source-code-aware reader (extend existing test) |

**Prior art:** `HtmlDocumentReaderTest`, `MarkdownDocumentReaderTest`, `SectionSplitterTest` — all test reader/splitter behaviour by passing inline strings and asserting on `Document` metadata and text content. Follow the same pattern: construct the reader with an inline source string (not a file), call `read()` or `parse()`, assert on the returned list.
