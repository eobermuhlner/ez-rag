# C# Source Code Ingestion

## Problem Statement

Developers and teams using ez-rag to build knowledge bases over their codebases cannot currently ingest C# source files. C# is widely used in enterprise software, game development (Unity), and Microsoft-ecosystem services. Without C# support, users must rely on plain-text fallback ingestion that loses all structural context: class hierarchies, interface contracts, method signatures, XML documentation comments, and attributes that carry important behavioural metadata.

## Solution

Extend ez-rag's source code ingestion pipeline to support C# (`.cs`) files. Each C# file is parsed into its structural declarations — classes, interfaces, structs, records, enums, delegates, methods, constructors, destructors, and operator overloads. XML doc comments and attributes are preserved as part of each chunk. Properties are included in their containing class chunk rather than extracted separately. Partial class fragments are each captured as a separate chunk per file. Namespace context is carried on every chunk. This allows users to search their C# codebase the same way they search Java, Kotlin, or Python code today.

## User Stories

1. As a developer, I want to ingest a C# source file, so that its classes, interfaces, and methods are searchable in the RAG knowledge base.
2. As a developer, I want C# class declarations to be extracted as separate chunks, so that I can retrieve the definition of a class without unrelated code.
3. As a developer, I want C# interface declarations to be extracted as separate chunks, so that I can find interface contracts across a large codebase.
4. As a developer, I want C# struct declarations to be extracted as separate chunks, so that value types are individually discoverable.
5. As a developer, I want C# record declarations to be extracted as separate chunks, so that data-carrier types are searchable.
6. As a developer, I want C# enum declarations to be extracted as separate chunks including their member values, so that enumeration information is not lost.
7. As a developer, I want C# delegate declarations to be extracted as separate chunks, so that callback and function-type signatures are discoverable.
8. As a developer, I want C# method declarations to be extracted as separate chunks, so that I can find specific methods across a large codebase.
9. As a developer, I want C# constructors to be identified as constructors, so that constructor chunks have consistent headings across all supported languages.
10. As a developer, I want C# static constructors to be identified as constructors, so that class-level initialisation logic is discoverable with appropriate labelling.
11. As a developer, I want C# destructors and finalizers to be extracted as method chunks, so that resource-cleanup logic is searchable.
12. As a developer, I want C# operator overloads to be extracted as method chunks, so that custom operator behaviour is discoverable.
13. As a developer, I want C# extension methods to be extracted as method chunks labelled with their containing static class, so that type-extension behaviour is findable.
14. As a developer, I want nested classes to be extracted as separate chunks with their outer class identified, so that inner types are individually discoverable.
15. As a developer, I want C# properties to be included in the containing class chunk rather than extracted as individual chunks, so that property declarations do not create many low-value fragments.
16. As a developer, I want C# attributes (e.g., `[Obsolete]`, `[HttpGet]`, `[JsonProperty]`) to be included in the chunk for the declaration they annotate, so that metadata-driven behaviour is preserved in searchable content.
17. As a developer, I want XML doc comments (`///`) to be included in the chunk for the declaration they document, so that documentation text is part of the searchable content.
18. As a developer, I want partial class fragments to each be captured as a separate chunk per file, so that partial class code is searchable without requiring cross-file merging.
19. As a developer, I want the namespace to be included in chunk metadata, so that search results carry meaningful namespace context analogous to Java package names.
20. As a developer, I want C# `using` directives to be included in each declaration's chunk, so that retrieved code is understandable without needing to look up the rest of the file.
21. As a developer, I want to ingest an entire directory containing C# files, so that I can index a whole project or solution in one command.
22. As a developer, I want large C# declarations to be split into smaller overlapping chunks, so that token limits are respected without losing content.
23. As a developer, I want the ingested C# chunks to be searchable via both semantic and keyword search, so that I can find code by concept or by exact symbol name.
24. As a developer, I want chunk metadata to include the declaration type, declaration name, class name (for nested types and methods), and namespace, so that search results can be filtered and displayed with useful context.
25. As an operator, I want C# files to be detected automatically by file extension without any configuration, so that ingestion works out of the box.

## User Acceptance Tests

1. Given a C# file containing a class with methods, when the file is ingested, then each method appears as a separate retrievable chunk with the class name in its heading.
2. Given a C# file containing an interface, when the file is ingested, then the interface appears as a separate retrievable chunk including its method signatures.
3. Given a C# file containing a struct, when the file is ingested, then the struct appears as a separate retrievable chunk.
4. Given a C# file containing a record type, when the file is ingested, then the record appears as a separate retrievable chunk.
5. Given a C# file containing an enum, when the file is ingested, then the enum appears as a separate retrievable chunk including all its member values.
6. Given a C# file containing a delegate declaration, when the file is ingested, then the delegate appears as a separate retrievable chunk.
7. Given a C# file containing a constructor, when the file is ingested, then the constructor chunk is labelled as a constructor (not as a plain method).
8. Given a C# file containing a static constructor, when the file is ingested, then it is labelled as a constructor with the static keyword visible in the chunk text.
9. Given a C# file containing a destructor, when the file is ingested, then the destructor appears as a retrievable method chunk.
10. Given a C# file containing an operator overload, when the file is ingested, then the operator overload appears as a retrievable method chunk with the `operator` keyword visible.
11. Given a C# file containing an extension method in a static class, when the file is ingested, then the method chunk identifies the containing static class as its owner.
12. Given a C# file containing a class with properties, when the file is ingested, then properties appear as part of the class chunk and not as separate individual chunks.
13. Given a C# file containing an attribute such as `[HttpGet("/api/users")]` on a method, when the file is ingested, then the attribute text appears in the method's chunk.
14. Given a C# file containing an XML doc comment above a class or method, when the file is ingested, then the comment text appears in the corresponding chunk.
15. Given a C# file marked `partial` containing part of a class definition, when the file is ingested, then that fragment is captured as a separate chunk without requiring the other partial files.
16. Given a C# file containing a nested class, when the file is ingested, then the nested class appears as a separate chunk identifying the outer class.
17. Given a C# file with `namespace MyCompany.MyApp`, when the file is ingested, then `MyCompany.MyApp` appears as the namespace in all chunk metadata from that file.
18. Given a C# file containing `using` directives, when the file is ingested, then the using directives appear in each declaration's chunk.
19. Given a C# file containing only a namespace declaration and using directives and no type declarations, when the file is ingested, then no error occurs and no spurious chunks are created.
20. Given a directory containing both C# and Go source files, when the directory is ingested, then chunks from both languages appear in search results.

## Definition of Done

- All user acceptance tests pass using the ez-rag CLI.
- C# `.cs` files are automatically detected and ingested without any additional configuration.
- All C# declaration types (class, interface, struct, record, enum, delegate, method, constructor, destructor, operator overload, extension method, nested type) produce correctly labelled chunks.
- Chunk metadata includes declaration type, declaration name, class name (where applicable), and namespace.
- XML doc comments and attributes are included in chunk text.
- Properties are part of the class chunk, not extracted as individual chunks.
- Partial class fragments are captured per file without cross-file merging.
- Existing language support (Java, Kotlin, TypeScript, JavaScript, Python, Go) is unaffected.
- All automated tests for the new parser pass.
- No regression in existing automated tests.

## Out of Scope

- C# script files (`.csx`) — not supported in this feature; may be addressed in a future iteration.
- Cross-file merging of partial class fragments.
- Extraction of properties as individual declaration chunks.
- Runtime or Roslyn-based analysis (semantic model, type resolution, inheritance graph).
- Python, Go source code ingestion — already handled in separate features.

## Further Notes

- This is the third and final language in the Python → Go → C# sequence planned during the source code ingestion initiative.
- C# is the most Java-like of the three new languages, meaning many AST patterns from `JavaSourceCodeParser` can be adapted directly.
- The `DeclarationType` enum requires no changes — all C# constructs map to the existing `CLASS`, `METHOD`, and `CONSTRUCTOR` values.
- The `MODULE` declaration type (introduced for Python, reused for Go) is not needed for C# — all code in C# lives inside type declarations or namespace blocks, with no meaningful top-level executable code pattern equivalent to Python's module-level statements.
- Attributes in C# are syntactically similar to Python decorators and should be treated the same way in `fullText` construction.

---

## Technical Annex
> Written against codebase as of: 2026-06-17

This section contains the architectural and automated testing decisions derived from the planning session. It is intended for architect and developer review.

### Architectural Decisions

**1. `DeclarationType` — no change required**

`CLASS`, `METHOD`, `FUNCTION`, `CONSTRUCTOR`, and `MODULE` already cover all C# constructs. No new enum values are needed for this feature.

**2. New `CSharpSourceCodeParser`**

Create:
```
src/main/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/CSharpSourceCodeParser.kt
```

Implements `SourceCodeParser`:
```kotlin
class CSharpSourceCodeParser : SourceCodeParser {
    override val language: String = "c_sharp"
    override fun parse(source: String): List<SourceDeclaration>
}
```

Uses `io.github.bonede:tree-sitter-c-sharp` (version to be confirmed against Maven Central at implementation time).

**Extraction rules:**

| C# AST node / construct | Declaration type | `declarationName` | `className` |
|---|---|---|---|
| `class_declaration` | `CLASS` | class name | `null` (or outer class name if nested) |
| `interface_declaration` | `CLASS` | interface name | `null` |
| `struct_declaration` | `CLASS` | struct name | `null` |
| `record_declaration` | `CLASS` | record name | `null` |
| `enum_declaration` | `CLASS` | enum name | `null` |
| `delegate_declaration` | `CLASS` | delegate name | `null` |
| Nested type inside a class | `CLASS` | type name | outer class name (e.g., `"Outer"` or `"Outer.Middle"`) |
| `constructor_declaration` | `CONSTRUCTOR` | class name | containing class name |
| `static` constructor (`static MyClass()`) | `CONSTRUCTOR` | class name | containing class name |
| `destructor_declaration` (`~MyClass()`) | `METHOD` | `~ClassName` | containing class name |
| `method_declaration` | `METHOD` | method name | containing class name |
| Extension method (first param has `this` modifier) | `METHOD` | method name | containing static class name |
| Operator overload (`operator_declaration`) | `METHOD` | `operator <symbol>` | containing class name |
| `property_declaration` | — | not extracted as standalone declaration |

**Namespace extraction:**
- Read the `namespace_declaration` node enclosing the type to get the namespace name.
- If file-scoped namespace syntax is used (`namespace MyApp;`), read the `file_scoped_namespace_declaration` node.
- Set `packageName` on every `SourceDeclaration` from the file.

**Attribute handling:**
- `attribute_list` nodes immediately preceding a declaration are included at the top of `fullText`, before the declaration keyword. This mirrors Python decorator handling.

**XML doc comment handling:**
- Scan preceding siblings of a declaration node for consecutive `comment` nodes whose text starts with `///`. Concatenate them and prepend to `fullText`.

**`fullText` content for each declaration type:**
- `CLASS` (any kind): attributes + XML doc + type keyword + name + base list + body (with nested type bodies collapsed to `{ ... }`)
- `METHOD` / `CONSTRUCTOR` / destructor / operator overload: attributes + XML doc + full method signature + body
- Properties: included as-is within the enclosing class `fullText`; not extracted separately

**`using` directives (imports):**
- Collect all `using_directive` nodes at file scope.
- Attach the full list to every `SourceDeclaration` from the file as the `imports` field.

**Partial classes:**
- Each file fragment of a partial class is extracted independently as a `CLASS` declaration. No cross-file analysis is performed. The `partial` keyword is preserved in `fullText`.

**3. Registration in `DocumentReaderRegistry`**

In `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/DocumentReaderRegistry.kt`, add:
```kotlin
"cs" to { file -> SourceCodeDocumentReader(file.readText(), CSharpSourceCodeParser(), chunkSize, chunkOverlap).read() },
```

**4. Gradle dependency**

In `build.gradle.kts`, add to the `dependencies` block:
```kotlin
implementation("io.github.bonede:tree-sitter-c-sharp:<version>")
```
Verify the latest available version on Maven Central before implementing.

### Automated Testing Decisions

**What makes a good test:**
Tests should assert on the `List<SourceDeclaration>` returned by `CSharpSourceCodeParser.parse()` — specifically `declarationType`, `declarationName`, `className`, `packageName`, `imports`, and whether `fullText` contains expected substrings (attribute, XML doc comment, method signature, enum members). Tests should not assert on exact whitespace or internal AST node structure.

**Modules with automated tests:**
- `CSharpSourceCodeParser` — unit tests only. The `SourceCodeDocumentReader` and `DocumentReaderRegistry` are already tested indirectly through existing tests and do not need new test classes for this feature.

**Prior art:**
- `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/JavaSourceCodeParserTest.kt` — follow this exact structure and naming convention. C# is structurally closest to Java among all supported languages.
- `src/test/resources/fixtures/sourcecode/` — add a C# fixture file at `src/test/resources/fixtures/sourcecode/Example.cs`.

**Test cases to cover:**
1. Class with methods → correct `CLASS` and `METHOD` declarations extracted
2. Interface declaration → `CLASS` type, method signatures in `fullText`
3. Struct declaration → `CLASS` type
4. Record declaration → `CLASS` type
5. Enum declaration → `CLASS` type, member values in `fullText`
6. Delegate declaration → `CLASS` type, signature in `fullText`
7. Constructor → `CONSTRUCTOR` declaration type, `declarationName` = class name
8. Static constructor → `CONSTRUCTOR` declaration type, `static` in `fullText`
9. Destructor → `METHOD` declaration type, `~` prefix in `declarationName`
10. Operator overload → `METHOD` declaration type, `operator` in `fullText`
11. Extension method → `METHOD` declaration type, `className` = containing static class
12. Attribute on a method → attribute text present in method `fullText`
13. XML doc comment → comment text present in `fullText` of documented declaration
14. Nested class → separate `CLASS` declaration with qualified `className`
15. Namespace → `packageName` populated on all declarations
16. `using` directives → `imports` list populated on all declarations
17. File with only namespace and using directives, no type declarations → empty list returned, no error
