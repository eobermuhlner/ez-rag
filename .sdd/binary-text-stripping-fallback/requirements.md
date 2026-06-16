## Problem Statement

When a user ingests a directory or URL that contains binary files (compiled executables, firmware images, proprietary format files, archives, etc.), the tool currently skips those files entirely and emits a warning. This means that readable text embedded in those binary files — such as error messages, function names, configuration strings, and embedded documentation — is never indexed and cannot be retrieved by search.

## Solution

When a file or URL is detected as binary and no dedicated parser is available, the tool extracts all readable text from the binary content using a strings-like algorithm and ingests the result as plain text chunks. The user is warned that binary text stripping was applied so they understand that the content quality may be lower than a purpose-built parser would produce.

## User Stories

1. As a developer, I want binary files in a directory to be partially ingested, so that strings embedded in compiled code (error messages, symbol names, configuration values) are searchable.
2. As a developer, I want to ingest firmware or binary blob files without needing to pre-process them manually, so that I can search for embedded strings using the same workflow as for documents.
3. As a sysadmin, I want executables and object files in a project directory to be indexed, so that I can search for embedded paths, version strings, or error messages.
4. As a user, I want to receive a clear warning when binary text stripping is applied, so that I understand the ingested content is approximate and may contain noise.
5. As a user, I want binary URLs to be ingested with text stripping rather than skipped, so that downloadable binary resources are treated consistently with local binary files.
6. As a user, I want the minimum extracted string length to be long enough to filter out single-character noise, so that the resulting chunks are meaningful rather than cluttered with garbage bytes.
7. As a user, I want newlines that appear in the original binary content to be preserved in the extracted text, so that multi-line embedded strings (help text, embedded documents) are readable.
8. As a user, I want binary text stripping to work on files with any extension — including unknown extensions and no extension — so that no file type is silently excluded.
9. As a user, I want the text-stripped content to be chunked with the same chunk size and overlap settings as all other content, so that search results are consistent.
10. As a user, I want the binary text-stripping fallback to produce zero chunks rather than an error when the binary file contains no printable text at all, so that the tool handles pathological cases gracefully.

## User Acceptance Tests

1. Given a directory containing a compiled binary executable alongside plain-text files, when the directory is ingested, then the executable is indexed (with a warning) and a search for a known error string embedded in the binary returns a result.
2. Given a binary file with a known extension but no registered parser, when it is ingested, then a warning is printed stating that binary text stripping was applied, and the file does not appear as skipped in the ingest summary.
3. Given a binary file that contains no printable text at all (e.g., all zero bytes), when it is ingested, then no error occurs, a warning is printed, and the file produces zero chunks.
4. Given a URL whose response is binary content, when it is ingested, then the content is text-stripped and indexed rather than skipped, and a warning is emitted.
5. Given a binary file containing a run of printable characters shorter than four characters, when it is ingested, then that short run does not appear in the extracted text.
6. Given a binary file containing a run of printable characters of exactly four characters, when it is ingested, then that four-character run appears in the extracted text.
7. Given a binary file containing an embedded newline character within a run of printable characters, when it is ingested, then the newline is preserved in the extracted text.
8. Given a binary file whose extracted text is non-empty, when it is ingested with custom chunk size and overlap settings, then the resulting chunks respect those settings.

## Definition of Done

- All user acceptance tests pass.
- Binary files in a directory are ingested with a warning instead of being skipped.
- Binary URL responses are ingested with a warning instead of being skipped.
- A warning is always printed when binary text stripping is applied.
- Files and URLs that produce zero extractable text are handled gracefully (no error, zero chunks, warning emitted).
- No regression in existing plain-text fallback behaviour (extensionless text files, unknown-extension text files still ingested as plain text without a binary warning).
- No regression in any existing format-specific parser (txt, md, pdf, html, rtf, csv, docx, doc, pptx, ppt, xlsx, xls).
- Automated tests cover the binary text-stripping algorithm and its integration in both the file and URL ingestion paths.
- README updated to document that binary files are partially indexed via text stripping.

## Out of Scope

- Format-specific binary parsers (e.g., ZIP content listing, ELF symbol table extraction, image EXIF data). Only generic printable-ASCII string extraction is in scope.
- Configurable minimum string length (fixed at 4 characters, matching the Unix `strings` default).
- Configurable character sets beyond printable ASCII (0x20–0x7E, tab, carriage return, newline).
- A CLI flag to disable binary text stripping.
- Quality filtering or deduplication of extracted strings.

## Further Notes

The binary text-stripping fallback is intentionally lossy. It is a best-effort extraction that trades completeness for broad coverage. Users who need high-fidelity extraction from a specific binary format should pre-process those files into a supported format before ingestion.

The stripping algorithm is equivalent in spirit to the Unix `strings` utility: it finds contiguous runs of printable ASCII characters (including whitespace characters `\t`, `\r`, `\n`) of at least 4 characters and emits them separated by newlines. Newlines within a run are preserved as-is; the newline separator is added only between adjacent extracted runs.

---

## Technical Annex
> Written against codebase as of: 2026-06-16

### Architectural Decisions

#### New module: `BinaryTextStripper`

A new object in `ch.obermuhlner.ezrag.ingestion` with a single pure function:

```kotlin
object BinaryTextStripper {
    private const val MIN_RUN_LENGTH = 4

    fun strip(bytes: ByteArray): String
}
```

- Scans `bytes` for contiguous runs of printable ASCII: bytes in `0x20..0x7E` plus `\t` (0x09), `\n` (0x0A), `\r` (0x0D).
- Keeps only runs of `MIN_RUN_LENGTH` (4) or more characters.
- Joins extracted runs with `"\n"` as separator.
- Returns an empty string when no qualifying run exists.
- Has no I/O dependencies — entirely testable with byte arrays.

#### Modified: `DocumentReaderRegistry.read()`

Current binary branch:
```kotlin
if (BinaryDetector.isBinary(bytes)) {
    throw IllegalArgumentException("Binary file detected, skipping: ${file.name}")
}
```

New binary branch (reads the full file for stripping, uses already-read 8KB for detection):
```kotlin
if (BinaryDetector.isBinary(bytes)) {
    // strip text from full file content
    val fullBytes = file.readBytes()
    val strippedText = BinaryTextStripper.strip(fullBytes)
    // caller (IngestService) is responsible for warning; registry returns documents
    return strippedTextToDocuments(strippedText, chunkSize, chunkOverlap)
}
```

The registry should no longer throw `IllegalArgumentException` for binary files. `IngestService` must be updated to emit a warning when the registry returns documents for a previously-binary path. One approach: have `DocumentReaderRegistry.read()` return a result type that carries a flag indicating binary stripping was applied, so `IngestService` can emit the warning. An alternative is to have the registry accept a warning callback; the simplest approach is to have `IngestService` detect the binary condition itself before calling the registry, then call `BinaryTextStripper` directly.

**Preferred approach**: Move the binary-detection-and-strip logic out of `DocumentReaderRegistry` and into `IngestService` for file ingestion, keeping `DocumentReaderRegistry` focused only on extension-dispatched readers. `DocumentReaderRegistry.read()` continues to throw `IllegalArgumentException` for binary files (existing contract preserved), and `IngestService` catches it, strips, warns, and ingests.

This keeps `DocumentReaderRegistry` simple and avoids coupling it to warning output.

#### Modified: `IngestService` — file ingestion path

Current catch block (lines ~79–84):
```kotlin
} catch (e: IllegalArgumentException) {
    warningWriter.println("Warning: Skipping binary file: $absolutePath — ${e.message}")
    skipped++
    continue
}
```

New catch block:
```kotlin
} catch (e: IllegalArgumentException) {
    val strippedText = BinaryTextStripper.strip(absolutePath.toFile().readBytes())
    warningWriter.println("Warning: Binary file stripped to plain text: $absolutePath")
    if (strippedText.isBlank()) {
        warningWriter.println("Warning: No extractable text in binary file: $absolutePath")
        skipped++
        continue
    }
    // build Document list from strippedText via TokenTextSplitter or inline
    strippedText /* → documents */
}
```

#### Modified: `IngestService` — URL ingestion path

Current binary branch (lines ~160–166):
```kotlin
if (BinaryDetector.isBinary(fetchResult.bytes)) {
    warningWriter.println("Warning: Skipping binary content at URL: $url ...")
    skipped++
    continue
}
```

New binary branch:
```kotlin
if (BinaryDetector.isBinary(fetchResult.bytes)) {
    val strippedText = BinaryTextStripper.strip(fetchResult.bytes)
    warningWriter.println("Warning: Binary content stripped to plain text at URL: $url")
    if (strippedText.isBlank()) {
        warningWriter.println("Warning: No extractable text in binary content at URL: $url")
        skipped++
        continue
    }
    // ingest strippedText as plain text (temp file approach, same as existing text/plain URL path)
}
```

#### Ingesting stripped text as documents

Both paths need to convert a `String` to `List<Document>` using the existing `TokenTextSplitter`. The cleanest integration is to write the stripped text to a temp file and pass it to `PlainTextDocumentReader`, which already handles chunking. This avoids adding a new string-input constructor to `PlainTextDocumentReader` and stays consistent with the existing URL plain-text path.

### Automated Testing Decisions

**What makes a good test**: tests should verify observable output (extracted strings, chunk content, warning messages) given controlled input (byte arrays, temp files). Do not test internal loop state or run-length counters directly.

**`BinaryTextStripperTest`** (new, unit):
- Empty byte array → empty string
- All non-printable bytes → empty string
- Single run of exactly 3 printable chars → empty string (below minimum)
- Single run of exactly 4 printable chars → that string returned
- Two separated runs of 4+ chars → both returned joined by newline
- Embedded `\n` within a run → newline preserved in output
- Mixed printable and non-printable → only qualifying runs returned
- Prior art: `BinaryDetectorTest` (same package, same style)

**`DocumentReaderRegistryTest`** (modify, unit):
- Existing test `read throws IllegalArgumentException for unknown extension file containing null byte` — keep as-is (registry contract unchanged under preferred approach)
- No new registry tests needed if stripping responsibility moves to `IngestService`

**`IngestServiceTest`** (modify, unit with fake embedding model):
- Binary file in directory → ingested with warning, not skipped
- Binary file with no extractable text → skipped with warning
- Binary URL content → ingested with warning, not skipped
- Binary URL with no extractable text → skipped with warning
- Prior art: existing `IngestServiceTest` with `fakeEmbeddingModel` and temp dirs
