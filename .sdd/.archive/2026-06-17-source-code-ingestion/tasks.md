# Tasks: source-code-ingestion

## Task 01-kotlin-source-code-chunking

Kotlin source files (`.kt`, `.kts`) are ingested with structural chunking end-to-end. This task introduces all shared infrastructure — `DeclarationType`, `SourceDeclaration`, `SourceCodeParser`, and `SourceCodeDocumentReader` — plus the first concrete parser (`KotlinSourceCodeParser`). After this task, ingesting a `.kt` or `.kts` file routes through the AST-based chunker, produces one chunk per class/method/function/constructor, and stores correct metadata in Lucene.

### Implementation steps

- [x] Add Gradle dependencies: `io.github.bonede:tree-sitter` (≈ 0.26.3.1) and `io.github.bonede:tree-sitter-kotlin` (≈ 0.3.8.1); resolve exact versions from Maven Central
- [x] Create package `ch.obermuhlner.ezrag.ingestion.sourcecode`; define `DeclarationType` enum (CLASS, METHOD, FUNCTION, CONSTRUCTOR), `SourceDeclaration` data class (`declarationType`, `declarationName`, `className: String?`, `packageName: String?`, `imports: List<String>`, `fullText: String`), and `SourceCodeParser` interface (`val language: String`, `fun parse(source: String): List<SourceDeclaration>`)
- [x] Implement `KotlinSourceCodeParser`: extract package and imports; for each class emit one CLASS declaration whose `fullText` is the full class body with nested class bodies replaced by their signatures only; for each method emit one METHOD declaration (including KDoc, signature, and body); for each secondary constructor emit one CONSTRUCTOR declaration; for each top-level function emit one FUNCTION declaration; set `className = null` for top-level functions and `className = "Outer.Inner"` for nested classes
- [x] Implement `SourceCodeDocumentReader(source: String, parser: SourceCodeParser, chunkSize: Int, chunkOverlap: Int)`: call `parser.parse(source)`; per declaration build heading prefix (`## ClassName` for class, `## ClassName\n### methodName` for method/constructor, `## functionName` for top-level function) and a fenced code block (language tag + package line + import lines + blank line + `fullText`); call `SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens).splitSection(body, headingPrefix)` as safety valve; emit one `Document` per sub-chunk with metadata: `heading_title`, `heading_path`, `language`, `declaration_type` (lowercase enum name), `class_name` (omitted if null), `declaration_name`
- [x] Register `.kt` and `.kts` in `DocumentReaderRegistry` using `SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read()`
- [x] Write `KotlinSourceCodeParserTest`: class with methods, top-level function, nested class with qualified name, secondary constructor, KDoc inclusion, package and import extraction
- [x] Write `SourceCodeDocumentReaderTest`: correct metadata fields per declaration type, heading prefix format, fenced code block structure, oversized method triggers safety-valve split producing all-compliant chunk sizes
- [x] Extend `DocumentReaderRegistryTest`: `.kt` and `.kts` extensions route to source-code-aware reader (not plain-text fallback)

### Acceptance criteria

- [x] A Kotlin file with a class `MyClass` containing methods `foo()` and `bar()` produces at least three chunks — one CLASS chunk and one METHOD chunk per method — and no chunk contains text from two different methods
- [x] The CLASS chunk for `MyClass` carries metadata `declaration_type = "class"` and `declaration_name = "MyClass"`; the METHOD chunks carry `declaration_type = "method"` and the respective method names as `declaration_name`
- [x] A Kotlin file with a nested class `Outer` containing `Inner` produces an Inner chunk with `class_name = "Outer.Inner"` in metadata
- [x] A Kotlin file with a top-level function `myFun()` produces a chunk with `declaration_type = "function"` and no `class_name` key in metadata
- [x] A Kotlin file whose method is annotated with a KDoc comment includes the KDoc text together with the method signature and body in the method's chunk
- [x] A Kotlin file's package declaration and all import lines appear verbatim in every chunk produced from that file
- [x] A Kotlin method body that is larger than `chunkSize` tokens is split into multiple sub-chunks via `SectionSplitter`; every sub-chunk has a token count ≤ `chunkSize`
- [x] Every chunk produced from a `.kt` file starts with a markdown heading prefix followed by a fenced code block opened with ` ```kotlin `

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 02-java-source-code-chunking

Java source files (`.java`) are ingested with structural chunking, reusing the `SourceCodeDocumentReader` and shared types introduced in Task 01. This task adds `JavaSourceCodeParser` and wires `.java` into the registry, so that after this task a developer can ingest a Java project and retrieve class, method, constructor, and interface-signature chunks individually.

### Implementation steps

- [x] Add Gradle dependency: `io.github.bonede:tree-sitter-java` (≈ 0.23.5); resolve exact version from Maven Central
- [x] Implement `JavaSourceCodeParser` (implements `SourceCodeParser`, `language = "java"`): extract package and imports; for each class emit one CLASS declaration (body minus nested class bodies); for each constructor emit one CONSTRUCTOR declaration; for each method (including interface/abstract method signatures) emit one METHOD declaration with Javadoc if present; qualified names for nested classes (`Outer.Inner`)
- [x] Register `.java` in `DocumentReaderRegistry`
- [x] Write `JavaSourceCodeParserTest`: class with methods, constructor, interface with method signatures, Javadoc inclusion, package and import extraction, nested class qualified name
- [x] Extend `DocumentReaderRegistryTest`: `.java` routes to source-code-aware reader

### Acceptance criteria

- [x] A Java file with a Javadoc-annotated method produces a METHOD chunk whose text includes the Javadoc text and the method signature and body
- [x] A Java class with a constructor produces a chunk with `declaration_type = "constructor"` and `language = "java"`
- [x] A Java interface with two abstract method signatures produces two METHOD chunks with `declaration_type = "method"`
- [x] A Java file's package and import lines appear verbatim in every chunk produced from that file

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors

---

## Task 03-typescript-javascript-source-code-chunking

TypeScript (`.ts`, `.tsx`) and JavaScript (`.js`, `.jsx`) source files are ingested with structural chunking, reusing the `SourceCodeDocumentReader` from Task 01. This task adds `TypeScriptSourceCodeParser`, wires all four extensions into the registry, verifies the plain-text fallback for unsupported languages (e.g. `.go`), and updates the README.

### Implementation steps

- [x] Add Gradle dependencies: `io.github.bonede:tree-sitter-typescript` (≈ 0.23.2) and `io.github.bonede:tree-sitter-javascript` (≈ 0.23.1); note: no separate `tree-sitter-tsx` artifact on Maven Central — TypeScript grammar handles both `.ts` and `.tsx`; JavaScript grammar handles `.js` and `.jsx`
- [x] Implement `TypeScriptSourceCodeParser(grammarKey: String)` (implements `SourceCodeParser`): select tree-sitter grammar based on `grammarKey` (`"typescript"` → TypeScript grammar, `"tsx"` → TypeScript grammar, `"javascript"` → JavaScript grammar, `"jsx"` → JavaScript grammar); set `language` to `"typescript"` for `"typescript"` and `"tsx"` keys and `"javascript"` for `"javascript"` and `"jsx"` keys; extract imports verbatim including alias forms; identify class declarations, methods, top-level functions, and interface method signatures; emit `SourceDeclaration` objects following the same contract as the Kotlin and Java parsers
- [x] Register `.ts`, `.tsx`, `.js`, `.jsx` in `DocumentReaderRegistry` passing the appropriate grammar key to `TypeScriptSourceCodeParser`
- [x] Write `TypeScriptSourceCodeParserTest`: class with methods, top-level function, interface with method signatures, JSDoc inclusion, import with alias, `.tsx` JSX element patterns
- [x] Extend `DocumentReaderRegistryTest`: all four extensions route to source-code-aware reader; `.go` still falls through to plain-text with no error (regression guard)
- [x] Update README to list all supported source code extensions: `.kt`, `.kts`, `.java`, `.ts`, `.tsx`, `.js`, `.jsx`

### Acceptance criteria

- [x] A TypeScript file with `import { Foo as Bar } from './foo'` produces chunks that all contain the full alias import line verbatim
- [x] A TypeScript interface with two method signatures produces two METHOD chunks with `declaration_type = "method"`
- [x] A `.tsx` file is processed via `SourceCodeDocumentReader` (chunks carry `language = "typescript"`; not plain-text fallback)
- [x] A `.js` file produces chunks with `language = "javascript"` metadata
- [x] A `.go` file ingested via `DocumentReaderRegistry` falls through to plain-text ingestion without throwing an error
- [x] Every chunk from a `.ts` file carries `language = "typescript"` in metadata

### Quality gates

- [x] `./gradlew test` passes with no failures
- [x] `./gradlew build` compiles without errors
- [x] README contains a section listing all supported source code file extensions
