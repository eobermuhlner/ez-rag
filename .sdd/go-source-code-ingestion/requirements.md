# Go Source Code Ingestion

## Problem Statement

Developers and teams using ez-rag to build knowledge bases over their codebases cannot currently ingest Go source files. Go is widely used in cloud infrastructure, backend services, and developer tooling — domains where a RAG tool is valuable for navigating large codebases. Without Go support, users must fall back to plain-text ingestion that loses all structural context: struct definitions, interface contracts, method ownership, and GoDoc comments.

## Solution

Extend ez-rag's source code ingestion pipeline to support Go (`.go`) files. Each Go file is parsed into its structural declarations — structs, interfaces, receiver methods, top-level functions, and a merged module-level chunk for package-level variable, constant, and type alias declarations. GoDoc comments are preserved as part of each chunk. All declarations carry the package name as context. Go test files (`_test.go`) are treated identically to production files. This allows users to search their Go codebase the same way they search Java or Kotlin code today.

## User Stories

1. As a developer, I want to ingest a Go source file, so that its structs, interfaces, and functions are searchable in the RAG knowledge base.
2. As a developer, I want Go struct declarations to be extracted as separate chunks, so that I can retrieve the definition of a struct without unrelated code.
3. As a developer, I want Go interface declarations to be extracted as separate chunks, so that I can find interface contracts across a large codebase.
4. As a developer, I want Go receiver methods to be extracted as separate chunks labelled with their receiver type, so that I can find all methods belonging to a given type.
5. As a developer, I want top-level Go functions to be extracted as separate chunks, so that standalone functions are individually discoverable.
6. As a developer, I want `init()` functions to be extracted as chunks, so that package initialisation logic is searchable.
7. As a developer, I want `NewXxx()` factory functions to be extracted as regular function chunks, so that constructor-style functions are discoverable without special-casing.
8. As a developer, I want package-level `var`, `const`, and non-struct/interface `type` declarations to be collected into a single module-level chunk per file, so that package constants and type aliases are searchable without generating many low-value individual chunks.
9. As a developer, I want GoDoc comments (`//` lines immediately above a declaration) to be included in the chunk for the declaration they document, so that documentation text is part of the searchable content.
10. As a developer, I want the package name to be included in chunk metadata, so that search results carry meaningful package context.
11. As a developer, I want Go import statements to be included in each declaration's chunk, so that retrieved code is understandable without needing to look up the rest of the file.
12. As a developer, I want Go test files (`_test.go`) to be ingested alongside production files, so that test code and usage examples are searchable.
13. As a developer, I want to ingest an entire directory containing Go files, so that I can index a whole Go package or project in one command.
14. As a developer, I want large Go declarations to be split into smaller overlapping chunks, so that token limits are respected without losing content.
15. As a developer, I want the ingested Go chunks to be searchable via both semantic and keyword search, so that I can find code by concept or by exact symbol name.
16. As a developer, I want chunk metadata to include the declaration type, declaration name, receiver type (for methods), and package name, so that search results can be filtered and displayed with useful context.
17. As an operator, I want Go files to be detected automatically by file extension without any configuration, so that ingestion works out of the box.

## User Acceptance Tests

1. Given a Go file containing a struct type, when the file is ingested, then the struct appears as a separate retrievable chunk labelled with the struct name.
2. Given a Go file containing an interface type, when the file is ingested, then the interface appears as a separate retrievable chunk with its method signatures included.
3. Given a Go file containing a receiver method (e.g., `func (r MyType) DoSomething()`), when the file is ingested, then the method appears as a separate chunk with `MyType` identified as the owning type in the heading.
4. Given a Go file containing a top-level function, when the file is ingested, then the function appears as a separate retrievable chunk.
5. Given a Go file containing an `init()` function, when the file is ingested, then `init` appears as a retrievable function chunk.
6. Given a Go file containing a `NewMyStruct()` factory function, when the file is ingested, then it appears as a regular function chunk (not labelled as a constructor).
7. Given a Go file containing a GoDoc comment above a function, when the file is ingested, then the comment text appears in the retrieved chunk.
8. Given a Go file containing `const`, `var`, and `type` alias declarations at package level, when the file is ingested, then these appear together in a single module-level chunk.
9. Given a Go file with `package json` at the top, when the file is ingested, then `json` appears as the package name in all chunk metadata from that file.
10. Given a Go file containing import statements, when the file is ingested, then the imports appear in each declaration's chunk.
11. Given a Go test file ending in `_test.go`, when its parent directory is ingested, then the test file's declarations appear as retrievable chunks.
12. Given a Go file containing only a package declaration and imports and no other declarations, when the file is ingested, then no error occurs and no spurious chunks are created.
13. Given a directory containing both Go and Python source files, when the directory is ingested, then chunks from both languages appear in search results.
14. Given a large Go struct or function that exceeds the configured token limit, when the file is ingested, then the declaration is split into multiple overlapping chunks without losing content.

## Definition of Done

- All user acceptance tests pass using the ez-rag CLI.
- Go `.go` files (including `_test.go`) are automatically detected and ingested without any additional configuration.
- All Go declaration types (struct, interface, receiver method, function, module-level declarations) produce correctly labelled chunks.
- Chunk metadata includes declaration type, declaration name, receiver type (for methods), and package name.
- GoDoc comments are included in chunk text.
- Package-level `var`, `const`, and non-struct/interface `type` declarations are merged into a single module-level chunk per file.
- Existing language support (Java, Kotlin, TypeScript, JavaScript, Python) is unaffected.
- All automated tests for the new parser pass.
- No regression in existing automated tests.

## Out of Scope

- Go modules (`go.mod`, `go.sum`) — not parsed as source code declarations.
- Generated Go files (e.g., protobuf output) — no special handling; ingested like any other `.go` file.
- Cgo files (`.c` files in a Go package) — not supported in this feature.
- C# source code ingestion — planned as a separate follow-on feature.
- Extraction of embedded struct fields as separate declarations.
- Per-declaration `var`/`const` chunks (all package-level declarations are merged into one module chunk).

## Further Notes

- This is the second of three planned language additions: Python → Go → C#.
- Go's lack of classes makes it structurally closer to C than to Java. The receiver-method pattern (methods defined outside the type body) is the key difference from all previously supported languages and drives the main AST traversal complexity.
- The `MODULE` declaration type introduced for Python is reused here for the merged package-level declarations chunk — no new enum value is needed.
- Go's package model means all files in a directory share the same package name; the parser reads `package foo` from each file independently.

---

## Technical Annex
> Written against codebase as of: 2026-06-17

This section contains the architectural and automated testing decisions derived from the planning session. It is intended for architect and developer review.

### Architectural Decisions

**1. `DeclarationType` — no change required**

`MODULE` was already added to `DeclarationType` for the Python feature. No further enum changes are needed for Go.

**2. New `GoSourceCodeParser`**

Create:
```
src/main/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/GoSourceCodeParser.kt
```

Implements `SourceCodeParser`:
```kotlin
class GoSourceCodeParser : SourceCodeParser {
    override val language: String = "go"
    override fun parse(source: String): List<SourceDeclaration>
}
```

Uses `io.github.bonede:tree-sitter-go` (version to be confirmed against Maven Central at implementation time).

**Extraction rules:**

| Go AST node | Declaration type | `declarationName` | `className` |
|---|---|---|---|
| `type_declaration` → `type_spec` with `struct_type` body | `CLASS` | struct name | `null` |
| `type_declaration` → `type_spec` with `interface_type` body | `CLASS` | interface name | `null` |
| `function_declaration` (no receiver) | `FUNCTION` | function name | `null` |
| `method_declaration` (has receiver) | `METHOD` | method name | receiver type name |
| `var_declaration`, `const_declaration`, `type_declaration` (non-struct, non-interface) at top level | `MODULE` | package name | `null` |

**Receiver type extraction:**
- The receiver in `func (r *MyType) Name()` → `className = "MyType"` (pointer `*` stripped).

**GoDoc comment extraction:**
- Scan the preceding siblings of a declaration node for consecutive `comment` nodes (lines starting with `//`). Include them in `fullText` above the declaration.

**`fullText` content for each declaration type:**
- `CLASS` (struct): GoDoc + `type Name struct { ... }` full body
- `CLASS` (interface): GoDoc + `type Name interface { ... }` full body
- `METHOD` / `FUNCTION`: GoDoc + full function signature + body
- `MODULE`: all `var`, `const`, and non-struct/non-interface `type` declarations concatenated, each with their own GoDoc comments; one chunk per file

**Package name extraction:**
- Read the `package_clause` node (first node in every Go file) to get the package name.
- Set `packageName` on every `SourceDeclaration` produced from the file.

**Imports:**
- Collect all `import_declaration` nodes at file scope.
- Attach the full import list to every `SourceDeclaration` from the file as the `imports` field.

**3. Registration in `DocumentReaderRegistry`**

In `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/DocumentReaderRegistry.kt`, add:
```kotlin
"go" to { file -> SourceCodeDocumentReader(file.readText(), GoSourceCodeParser(), chunkSize, chunkOverlap).read() },
```

No special handling is needed for `_test.go` — the `.go` extension match covers them automatically.

**4. Gradle dependency**

In `build.gradle.kts`, add to the `dependencies` block:
```kotlin
implementation("io.github.bonede:tree-sitter-go:<version>")
```
Verify the latest available version on Maven Central before implementing.

### Automated Testing Decisions

**What makes a good test:**
Tests should assert on the `List<SourceDeclaration>` returned by `GoSourceCodeParser.parse()` — specifically `declarationType`, `declarationName`, `className`, `packageName`, `imports`, and whether `fullText` contains expected substrings (GoDoc comment, `func` line, struct/interface body). Tests should not assert on exact whitespace or internal AST node structure.

**Modules with automated tests:**
- `GoSourceCodeParser` — unit tests only. The `SourceCodeDocumentReader` and `DocumentReaderRegistry` are already tested indirectly through existing tests and do not need new test classes for this feature.

**Prior art:**
- `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/JavaSourceCodeParserTest.kt` — follow this exact structure and naming convention.
- `src/test/resources/fixtures/sourcecode/` — add a Go fixture file at `src/test/resources/fixtures/sourcecode/Example.go`.

**Test cases to cover:**
1. Struct declaration → `CLASS` declaration type, correct `declarationName`, `className` is null
2. Interface declaration → `CLASS` declaration type, interface method signatures in `fullText`
3. Receiver method → `METHOD` declaration type, `className` = receiver type name (pointer `*` stripped)
4. Top-level function → `FUNCTION` declaration type
5. `init()` function → `FUNCTION` declaration type with `declarationName = "init"`
6. `NewXxx()` factory function → `FUNCTION` declaration type (not `CONSTRUCTOR`)
7. GoDoc comment → comment text present in `fullText` of the documented declaration
8. Package-level `var`, `const`, non-struct `type` → single `MODULE` declaration, all present in `fullText`
9. Package name → `packageName` populated on all declarations
10. Imports → `imports` list populated on all declarations
11. File with only package declaration and imports → empty declaration list returned, no error
