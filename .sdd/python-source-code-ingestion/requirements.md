# Python Source Code Ingestion

## Problem Statement

Developers and teams using ez-rag to build knowledge bases over their codebases cannot currently ingest Python source files. Python is one of the most widely used programming languages, particularly in AI, data science, and scripting contexts — the exact domains where a RAG tool is most likely to be deployed. Without Python support, users must maintain separate documentation alongside their Python code, or rely on plain-text fallback ingestion that loses all structural context (class boundaries, method signatures, docstrings).

## Solution

Extend ez-rag's source code ingestion pipeline to support Python (`.py`) files. Each Python file is parsed into its structural declarations — modules, classes, methods, functions, and constructors — and each declaration is stored as a focused, semantically coherent chunk. Docstrings and decorators are preserved as part of each chunk. Top-level executable code is captured as a module-level chunk rather than discarded. This allows users to search their Python codebase the same way they search Java or Kotlin code today.

## User Stories

1. As a developer, I want to ingest a Python source file, so that its classes and functions are searchable in the RAG knowledge base.
2. As a developer, I want Python class declarations to be extracted as separate chunks, so that I can retrieve the definition of a class without unrelated code.
3. As a developer, I want Python method declarations to be extracted as separate chunks, so that I can find specific methods across a large codebase.
4. As a developer, I want Python top-level functions to be extracted as separate chunks, so that standalone utility functions are discoverable.
5. As a developer, I want `__init__` methods to be identified as constructors, so that constructor chunks have consistent headings across all supported languages.
6. As a developer, I want decorators to be included in the chunk for the declaration they annotate, so that context like `@dataclass` or `@staticmethod` is not lost.
7. As a developer, I want Python docstrings to be included in the chunk for their declaration, so that documentation text is part of the searchable content.
8. As a developer, I want `async def` functions and methods to be ingested the same as regular ones, so that async code is not excluded from search results.
9. As a developer, I want nested classes to be extracted as separate chunks, so that inner classes are individually discoverable.
10. As a developer, I want nested functions to be treated as part of the enclosing function's chunk rather than extracted separately, so that implementation details do not create misleading standalone chunks.
11. As a developer, I want top-level executable code (script logic, `if __name__ == "__main__":` blocks) to be captured as a module-level chunk, so that it is not silently discarded.
12. As a developer, I want the module name to be derived from the file path, so that chunks carry meaningful package context analogous to Java package names.
13. As a developer, I want to ingest an entire directory containing Python files, so that I can index a whole project in one command.
14. As a developer, I want Python chunks to include the relevant import statements, so that retrieved code is understandable without needing to look up the rest of the file.
15. As a developer, I want large Python declarations to be split into smaller overlapping chunks, so that token limits are respected without losing content.
16. As a developer, I want the ingested Python chunks to be searchable via both semantic and keyword search, so that I can find code by concept or by exact symbol name.
17. As a developer, I want chunk metadata to include the declaration type, class name, and module name, so that search results can be filtered and displayed with useful context.
18. As an operator, I want Python files to be detected automatically by file extension without any configuration, so that ingestion works out of the box.

## User Acceptance Tests

1. Given a Python file containing a class with methods, when the file is ingested, then each method appears as a separate retrievable chunk with the class name in its heading.
2. Given a Python file containing a class with an `__init__` method, when the file is ingested, then the `__init__` chunk is labelled as a constructor (not as a plain method).
3. Given a Python file containing a function decorated with `@staticmethod`, when the file is ingested, then the decorator appears in the retrieved chunk text.
4. Given a Python file containing a class with a docstring, when the file is ingested, then the docstring text appears in the class chunk and is searchable.
5. Given a Python file containing an `async def` function, when the file is ingested, then the function appears as a retrievable chunk with `async def` preserved in the code.
6. Given a Python file containing a nested class, when the file is ingested, then the nested class appears as a separate retrievable chunk.
7. Given a Python file containing a nested function (a function defined inside another function), when the file is ingested, then no separate chunk is created for the nested function.
8. Given a Python file containing top-level script code outside any class or function, when the file is ingested, then that code appears as a retrievable module-level chunk.
9. Given a Python file located at `src/utils/helpers.py`, when the file is ingested, then the module name `utils.helpers` appears in the chunk metadata.
10. Given a Python file containing import statements, when the file is ingested, then the import statements appear in each declaration's chunk.
11. Given a directory containing both Python and Java source files, when the directory is ingested, then chunks from both languages appear in search results.
12. Given a Python file containing only import statements and no declarations, when the file is ingested, then no error occurs and no spurious chunks are created.
13. Given a Python chunk whose source text exceeds the configured token limit, when the file is ingested, then the declaration is split into multiple overlapping chunks without losing content.

## Definition of Done

- All user acceptance tests pass using the ez-rag CLI.
- Python `.py` files are automatically detected and ingested without any additional configuration.
- All Python declaration types (class, method, function, constructor, module) produce correctly labelled chunks.
- Chunk metadata includes declaration type, declaration name, class name, and module name where applicable.
- Decorators and docstrings are included in chunk text.
- Top-level code is captured as a module-level chunk.
- Existing language support (Java, Kotlin, TypeScript, JavaScript) is unaffected.
- All automated tests for the new parser pass.
- No regression in existing automated tests.

## Out of Scope

- Python stub files (`.pyi`) — not included in this feature; may be addressed in a future iteration.
- Python 2 syntax — only Python 3 is targeted.
- Go and C# source code ingestion — planned as separate follow-on features.
- Dynamic/runtime analysis of Python code (imports resolved at runtime, decorators evaluated at runtime).
- Extraction of module-level variable assignments or constants as separate chunks.
- Support for compiled Python files (`.pyc`).

## Further Notes

- This is the first of three planned language additions: Python → Go → C#.
- The `MODULE` declaration type added here is Python-specific today but may prove useful for Go (which also lacks a class hierarchy) in the next iteration.
- The Tree-sitter Python grammar is available via the same `io.github.bonede` library already used for Java, Kotlin, TypeScript, and JavaScript — no new library vendor is introduced.

---

## Technical Annex
> Written against codebase as of: 2026-06-17

This section contains the architectural and automated testing decisions derived from the planning session. It is intended for architect and developer review.

### Architectural Decisions

**1. New `MODULE` declaration type**

Add `MODULE` to the `DeclarationType` enum in:
```
src/main/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/DeclarationType.kt
```
Current enum: `CLASS, METHOD, FUNCTION, CONSTRUCTOR`
New enum: `CLASS, METHOD, FUNCTION, CONSTRUCTOR, MODULE`

`MODULE` is used for top-level Python code that is not inside any class or function (script logic, `if __name__ == "__main__":` blocks, top-level statements). The `declarationName` for a `MODULE` declaration is the derived module name (e.g., `utils.helpers`).

**2. New `PythonSourceCodeParser`**

Create:
```
src/main/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/PythonSourceCodeParser.kt
```

Implements `SourceCodeParser`:
```kotlin
class PythonSourceCodeParser : SourceCodeParser {
    override val language: String = "python"
    override fun parse(source: String): List<SourceDeclaration>
}
```

Uses `io.github.bonede:tree-sitter-python` (version to be confirmed against Maven Central at implementation time). Parser walks the Tree-sitter AST and emits `SourceDeclaration` objects.

**Extraction rules:**
- `class_definition` node → `DeclarationType.CLASS`
- `function_definition` or `decorated_definition` wrapping a function at class scope → `DeclarationType.METHOD`
- `function_definition` or `decorated_definition` wrapping a function named `__init__` at class scope → `DeclarationType.CONSTRUCTOR`
- `function_definition` or `decorated_definition` wrapping a function at module scope → `DeclarationType.FUNCTION`
- Nested `class_definition` inside a class → `DeclarationType.CLASS` with `className` set to the qualified outer class name
- Nested `function_definition` inside a function → **ignored** (not extracted as a separate declaration)
- Top-level code that is not a class or function definition → `DeclarationType.MODULE`

**Module name derivation:**
- Derived from the file path relative to the source root using the existing path context available in the registry (e.g., `src/utils/helpers.py` → `utils.helpers`).
- Passed as `packageName` on every `SourceDeclaration` from that file.

**Decorator handling:**
- When a `decorated_definition` node wraps a `function_definition` or `class_definition`, the decorator lines are prepended to the `fullText` of the declaration.

**Docstring handling:**
- A `string` node as the first statement in a `block` is recognised as a docstring and included in the `fullText` of its enclosing declaration.

**`fullText` content for each declaration type:**
- `CLASS`: decorator(s) + `class` line + docstring + method/nested-class signatures (nested class bodies collapsed to `...`)
- `METHOD` / `FUNCTION` / `CONSTRUCTOR`: decorator(s) + `def` line + docstring + full body
- `MODULE`: all top-level statements not covered by any class or function declaration, concatenated

**Imports:**
- All `import` and `from ... import` statements at module scope are collected and attached to every `SourceDeclaration` in the file as the `imports` list.

**3. Registration in `DocumentReaderRegistry`**

In `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/DocumentReaderRegistry.kt`, add:
```kotlin
"py" to { file -> SourceCodeDocumentReader(file.readText(), PythonSourceCodeParser(), chunkSize, chunkOverlap).read() },
```

**4. Gradle dependency**

In `build.gradle.kts`, add to the `dependencies` block:
```kotlin
implementation("io.github.bonede:tree-sitter-python:<version>")
```
Verify the latest available version on Maven Central before implementing.

### Automated Testing Decisions

**What makes a good test:**
Tests should assert on the `List<SourceDeclaration>` returned by `PythonSourceCodeParser.parse()` — specifically `declarationType`, `declarationName`, `className`, `packageName`, `imports`, and whether `fullText` contains expected substrings (decorator, docstring, `def` line). Tests should not assert on exact whitespace or internal AST node structure.

**Modules with automated tests:**
- `PythonSourceCodeParser` — unit tests only. The `SourceCodeDocumentReader` and `DocumentReaderRegistry` are already tested indirectly through existing tests and do not need new test classes for this feature.

**Prior art:**
- `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/sourcecode/JavaSourceCodeParserTest.kt` — follow this exact structure and naming convention.
- `src/test/resources/fixtures/sourcecode/` — fixture `.java`, `.kt`, `.ts`, `.js` files exist here; add a Python fixture file at `src/test/resources/fixtures/sourcecode/Example.py`.

**Test cases to cover:**
1. Class with methods → correct `CLASS` and `METHOD` declarations extracted
2. `__init__` method → `CONSTRUCTOR` declaration type
3. Decorator on a function → decorator text present in `fullText`
4. Docstring on a class → docstring text present in `fullText`
5. `async def` function → extracted as `FUNCTION`, `async` present in `fullText`
6. Nested class → separate `CLASS` declaration with qualified `className`
7. Nested function → not extracted as a separate declaration
8. Top-level executable code → `MODULE` declaration extracted
9. Module name → `packageName` derived from file path
10. Imports → `imports` list populated for all declarations in the file
11. File with only imports and no declarations → empty list returned, no error
