# Plain-Text Fallback Ingestion

## Problem Statement

When a user tries to ingest a file whose extension is not explicitly recognised by the tool (for example `.yaml`, `.json`, `.xml`, `.sh`, `.py`, `.log`, `.ini`, `Makefile`, `Dockerfile`, `LICENSE`), the tool currently skips it with a warning. This means large amounts of useful plain-text content — configuration files, source code, logs, scripts — are silently excluded from the knowledge base with no way to include them short of renaming files. The same problem applies to URLs whose HTTP `Content-Type` header is not one of the small set of explicitly supported types.

## Solution

The tool will attempt to ingest any file or URL that is not already handled by a specific parser, by reading the first 8 KB of its content and checking for null bytes. If no null byte is found, the content is treated as plain text and ingested using the existing plain-text chunking pipeline. If a null byte is found, the file is considered binary and skipped with a warning, preserving the current behaviour for true binary files. The explicit allowlist of supported file extensions is removed; binary detection becomes the single gate for all content.

## User Stories

1. As a developer, I want to ingest YAML configuration files, so that I can search my project's configuration in the knowledge base.
2. As a developer, I want to ingest JSON files, so that I can retrieve structured data alongside my prose documents.
3. As a developer, I want to ingest XML files, so that I can search configuration, data, and markup in the same index.
4. As a developer, I want to ingest shell scripts, so that I can ask questions about my automation tooling.
5. As a developer, I want to ingest Python, JavaScript, or other source code files, so that I can search and retrieve code snippets via the RAG pipeline.
6. As a developer, I want to ingest log files, so that I can run semantic search over application output.
7. As a developer, I want to ingest INI, TOML, or properties files, so that I can include all configuration formats in the knowledge base.
8. As a developer, I want to ingest files with no extension (e.g. `Makefile`, `Dockerfile`, `LICENSE`), so that common project files are not silently excluded.
9. As a developer, I want binary files (executables, images, compressed archives) to be automatically skipped, so that binary garbage does not pollute the index.
10. As a developer, I want a clear warning when a file is skipped because it is binary, so that I understand why it was excluded.
11. As a developer, I want to ingest a directory tree containing mixed file types, so that all text-readable files are indexed in a single command.
12. As a developer, I want to ingest a URL whose `Content-Type` header is not explicitly recognised, so that text-based APIs and endpoints are not silently skipped.
13. As a developer, I want files with encoding issues (e.g. Latin-1 or Windows-1252) to be ingested with replacement characters rather than failing, so that imperfectly-encoded files do not cause errors.
14. As a developer, I want the existing specifically-supported formats (PDF, Word, Excel, etc.) to continue to be parsed with their dedicated parsers, so that rich format extraction is not degraded.
15. As a developer, I want the chunking behaviour for fallback text files to be the same as for `.txt` files, so that search quality is consistent across file types.

## User Acceptance Tests

1. Given a `.yaml` file containing plain text, when the file is ingested, then at least one chunk from that file appears in search results.
2. Given a `Dockerfile` (no extension) containing plain text, when it is ingested, then its content is retrievable via search.
3. Given a `.png` image file, when the directory containing it is ingested, then the image file is skipped with a warning and no error is raised.
4. Given a `.exe` executable file, when the directory containing it is ingested, then the file is skipped with a warning and no error is raised.
5. Given a directory containing a `.txt` file and a `.yaml` file, when the directory is ingested, then both files produce chunks in the index.
6. Given a directory containing a `.pdf` file and a `.json` file, when the directory is ingested, then the PDF is parsed with its dedicated parser and the JSON is parsed as plain text — both appear in search results.
7. Given a URL whose `Content-Type` is `application/json` (not in the previous supported set), when the URL is ingested, then its text content is retrievable via search.
8. Given a URL whose response body begins with binary data (null byte in first 8 KB), when the URL is ingested, then the URL is skipped with a warning and no error is raised.
9. Given a Latin-1 encoded text file, when the file is ingested, then it is ingested successfully (possibly with some replacement characters) rather than failing with an encoding error.
10. Given a file with no extension that contains null bytes (e.g. a compiled object file), when it is ingested, then it is skipped with a warning.
11. Given a previously ingested `.txt` file and a newly ingested `.yaml` file of similar content, when a search is run, then both files appear in results with comparable relevance.

## Definition of Done

- All user acceptance tests pass.
- Binary files (containing null bytes) are skipped with a warning for both filesystem and URL sources.
- Text files of any extension (including no extension) are ingested as plain text when no dedicated parser exists.
- Dedicated parsers for PDF, Word, Excel, PowerPoint, HTML, RTF, CSV, and Markdown continue to function without regression.
- The explicit extension allowlist is fully removed from the directory-walking logic.
- URL ingestion falls back to plain-text when `Content-Type` is unrecognised and content passes binary detection.
- Files with encoding issues are ingested with replacement characters, not errors.
- All automated tests (unit and integration) pass.
- No regression in existing ingestion features.
- README updated to reflect that all text file formats are now supported.

## Out of Scope

- MIME type library integration (e.g. Apache Tika) — detection is limited to null-byte sniffing.
- Configurable binary-detection thresholds or printable-ratio checks.
- Explicit block-lists of known binary extensions.
- Charset auto-detection (files are always read as UTF-8 with replacement).
- Extracting text from archives (`.zip`, `.tar`, `.gz`) by decompressing them.
- Language-aware chunking for source code (e.g. splitting on function boundaries).

## Further Notes

- The null-byte heuristic is well-established (used by `git`, `file(1)`, and many editors) and correctly classifies the vast majority of real-world files with no configuration required.
- Files that pass the null-byte check but are not semantically meaningful (e.g. a `.bin` file that happens to contain no null bytes) will be ingested as plain text. This is acceptable — the content will simply not match useful queries.
- The change in `DirectoryWalker` (removing `SUPPORTED_EXTENSIONS`) is a breaking change to the public constant. Any external callers referencing `DirectoryWalker.SUPPORTED_EXTENSIONS` will need to be updated.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New module: `BinaryDetector`

A single-responsibility utility object with one public function:

```kotlin
object BinaryDetector {
    fun isBinary(bytes: ByteArray, length: Int = bytes.size): Boolean
}
```

Reads up to 8 KB (or `length` bytes, whichever is smaller) and returns `true` if any byte equals `0x00`. No external dependencies. Placed in `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/`.

#### Modified: `DirectoryWalker`

- Remove the `SUPPORTED_EXTENSIONS` constant (`Set<String>`) entirely.
- Remove the `filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }` call in the directory walk.
- Walk all files unconditionally; leave format gating to `DocumentReaderRegistry`.

#### Modified: `DocumentReaderRegistry`

Current dispatch: a `Map<String, (File) -> List<Document>>` keyed by extension, throwing `IllegalArgumentException` for unknown extensions.

New behaviour: add a fallback branch after the map lookup:

```kotlin
fun read(file: File): List<Document> {
    val extension = file.extension.lowercase()
    val specificReader = readers[extension]
    if (specificReader != null) return specificReader(file)

    // Fallback: binary detection
    val bytes = file.inputStream().use { it.readNBytes(8192) }
    if (BinaryDetector.isBinary(bytes)) {
        throw IllegalArgumentException("Binary file detected, skipping: ${file.name}")
    }
    return PlainTextDocumentReader(file, chunkSize, chunkOverlap).read()
}
```

`PlainTextDocumentReader` must read with `Charsets.UTF_8` and `CodingErrorAction.REPLACE`. Verify current implementation; update if needed.

#### Modified: `IngestService` — filesystem path

- Remove the guard block that checks `file.extension.lowercase() in DirectoryWalker.SUPPORTED_EXTENSIONS` and prints a warning / increments `skipped`.
- Let `DocumentReaderRegistry.read()` throw `IllegalArgumentException` for binary files.
- Catch `IllegalArgumentException` in the per-file processing block: print the existing "Skipping…" warning and increment `skipped`.

#### Modified: `IngestService` — URL path

Current behaviour: skip with warning if `contentType` is not in `{text/html, application/xhtml+xml, application/pdf, text/plain}`.

New behaviour: add an `else` branch that runs `BinaryDetector.isBinary(fetchResult.bodyBytes)`. If text, pass body bytes to `PlainTextDocumentReader` (or a byte-array variant). If binary, skip with warning as before.

`FetchResult` (or equivalent) must expose the raw response bytes alongside the `contentType` string. Verify; extend if not already present.

### Automated Testing Decisions

**What makes a good test**: Tests should verify external behaviour (what is ingested, what is skipped, what errors are raised) via the public API of each module. They must not assert on internal state, private methods, or implementation details such as which class handled a file.

#### `BinaryDetector` — unit tests

- File: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/BinaryDetectorTest.kt`
- Test cases:
  - Empty byte array → not binary.
  - All printable ASCII bytes → not binary.
  - Byte array with `0x00` anywhere → binary.
  - Byte array with `0x00` beyond the 8 KB window → not binary (only first 8 KB is checked).
  - UTF-8 multi-byte sequences → not binary.
- Prior art: `PlainTextDocumentReaderTest.kt` for fixture file patterns.

#### `DocumentReaderRegistry` — unit tests (fallback branch)

- File: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/DocumentReaderRegistryTest.kt`
- Test cases:
  - File with known extension (e.g. `.txt`) → handled by specific reader (existing behaviour).
  - File with unknown extension containing plain text → returns non-empty `List<Document>`.
  - File with unknown extension containing a null byte → throws `IllegalArgumentException`.
  - File with no extension containing plain text → returns non-empty `List<Document>`.
  - File with no extension containing a null byte → throws `IllegalArgumentException`.
- Use small in-memory or temp-file fixtures; no external services needed.

#### `DirectoryWalker` — unit tests

- File: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/DirectoryWalkerTest.kt`
- Test cases:
  - Directory containing `.yaml` and `.txt` files → both paths returned.
  - Directory containing a `.png` file → path returned (walker no longer filters; filtering is downstream).
  - Recursive walk returns files from subdirectories.
- Prior art: check existing `DirectoryWalkerTest.kt` if present; otherwise model on `IngestServiceTest.kt`.

#### `IngestService` — integration-style tests (fallback paths)

- File: `src/test/kotlin/ch/obermuhlner/ezrag/ingestion/IngestServiceTest.kt` (extend existing file).
- Test cases:
  - Ingest a `.yaml` fixture file → result has `ingested > 0`, `skipped == 0`.
  - Ingest a no-extension fixture file (`Makefile`) → result has `ingested > 0`.
  - Ingest a binary fixture file (contains null byte, unknown extension) → result has `skipped == 1`, `ingested == 0`.
  - Ingest a directory with mixed text and binary files → correct counts for each.
  - Ingest a URL with unrecognised `Content-Type` and text body → result has `ingested > 0` (requires HTTP stub/mock matching existing URL-test patterns).
- Use the same temp-directory and `LuceneRepository` pattern as existing `IngestServiceTest.kt`.
