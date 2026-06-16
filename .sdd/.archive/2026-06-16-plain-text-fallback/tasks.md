# Tasks: Plain-Text Fallback Ingestion

## Task [01-binary-detector]

Create the `BinaryDetector` utility — a single-responsibility Kotlin `object` that scans up to the first 8 192 bytes of a byte array and returns `true` if any byte equals `0x00`. This null-byte heuristic is used by `git`, `file(1)`, and many editors to classify binary vs. text content. All downstream binary-detection logic in tasks 02 and 04 depends on this utility.

### Implementation steps

- [x] Write `BinaryDetectorTest` with failing tests first: empty array → not binary; all printable ASCII → not binary; null byte present → binary; null byte at index ≥ 8192 with default `length` → not binary; `length` parameter smaller than `bytes.size` positions null byte outside scan window → not binary; UTF-8 multi-byte sequences → not binary
- [x] Create `BinaryDetector` as a Kotlin `object` in `src/main/kotlin/ch/obermuhlner/ezrag/ingestion/`
- [x] Implement `isBinary(bytes: ByteArray, length: Int = bytes.size): Boolean` — scan `minOf(length, 8192)` bytes for `0x00`; return `true` if any are found
- [x] Run `./gradlew test` and verify all tests pass

### Acceptance criteria

- [x] `isBinary(ByteArray(0))` returns `false`
- [x] `isBinary("hello world".toByteArray())` returns `false`
- [x] `isBinary(byteArrayOf(0x41, 0x00, 0x42))` returns `true`
- [x] A 9000-byte array with `0x00` at index 8192 (the first index outside the scan window): `isBinary` returns `false`
- [x] `isBinary(byteArrayOf(0x41, 0x00), length = 1)` returns `false` (explicit `length` moves null byte outside scan window)
- [x] `isBinary("café".toByteArray(Charsets.UTF_8))` returns `false`

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.BinaryDetectorTest"` passes with all tests green
- [x] `./gradlew build` compiles without errors

---

## Task [02-filesystem-text-fallback]

*Depends on: 01-binary-detector*

End-to-end vertical slice: a file with an unrecognised extension (`.yaml`, `.sh`, `.json`, etc.) or no extension (`Makefile`, `Dockerfile`) is walked by `DirectoryWalker`, dispatched through `DocumentReaderRegistry`, and ingested as plain text by `IngestService`. Files whose content contains a null byte are detected as binary and skipped with a warning. Dedicated parsers for known formats (PDF, Word, Excel, etc.) continue to work without change.

### Implementation steps

- [x] **`DirectoryWalkerTest` first:** write failing tests asserting that files with unknown extensions and files with no extension are returned; update the existing test `walk returns only supported extensions sorted alphabetically` to expect all files; update or remove the test `walk emits a warning for each unsupported file` (the walker no longer emits warnings for extension mismatches)
- [x] **`DirectoryWalker`:** remove the `SUPPORTED_EXTENSIONS` constant and the `if (ext in SUPPORTED_EXTENSIONS)` filter; walk all regular files unconditionally
- [x] **`DocumentReaderRegistryTest` first:** write failing tests: unknown-extension text file → non-empty `List<Document>`; unknown-extension file with a null byte → throws `IllegalArgumentException`; no-extension text file → non-empty `List<Document>`; no-extension file with a null byte → throws `IllegalArgumentException`; use small temp-file fixtures (write `"hello"` for text, `byteArrayOf(0x68, 0x00)` for binary)
- [x] **`DocumentReaderRegistry.read(file)`:** after the map lookup fails, read up to 8 192 bytes via `file.inputStream().use { it.readNBytes(8192) }`, call `BinaryDetector.isBinary(bytes)`, throw `IllegalArgumentException("Binary file detected, skipping: ${file.name}")` if binary; otherwise return `PlainTextDocumentReader(file, chunkSize, chunkOverlap).read()`
- [x] **`IngestServiceTest` first:** write failing tests: `.yaml` fixture → `ingested > 0, skipped == 0`; no-extension `Makefile` fixture → `ingested > 0`; binary fixture (temp file with `0x00` byte, unknown extension) → `skipped == 1, ingested == 0`; directory with one text `.yaml` and one binary unknown-extension file → `ingested == 1, skipped == 1`; update the existing test for `.odt`-like "unsupported extension" behaviour (these files will now be ingested as plain text unless they contain null bytes — update fixture or expectations accordingly)
- [x] **`IngestService` filesystem path — directory source:** remove the extension check against `DirectoryWalker.SUPPORTED_EXTENSIONS`; in the per-file processing loop add a `catch (e: IllegalArgumentException)` that prints the existing "Skipping…" message and increments `skipped`
- [x] **`IngestService` filesystem path — single-file source:** remove the identical `SUPPORTED_EXTENSIONS` guard on the single-file code path (same pattern as the directory path)
- [x] Run `./gradlew test` and verify all tests pass

### Acceptance criteria

- [x] `DirectoryWalker` has no `SUPPORTED_EXTENSIONS` constant and returns all regular files regardless of extension
- [x] Ingesting a `.yaml` text file produces `ingested > 0, skipped == 0`
- [x] Ingesting a no-extension file whose content is plain text produces `ingested > 0`
- [x] Ingesting a file whose content contains a null byte (any extension) produces `skipped == 1, ingested == 0` and prints a warning containing "binary" or "skipping"
- [x] Ingesting a directory with one `.txt` and one `.yaml` file produces chunks from both
- [ ] ~~Ingesting a directory with a `.pdf` and a `.json` uses the dedicated PDF parser for the PDF and plain-text ingestion for the JSON — both appear in search results~~ *(skipped: this is covered by existing test infrastructure and the `IngestIntegrationTest` which tests pdf + xyz in same directory; JSON-specific combined-search result verification requires a running search stack not available here — behaviour is implemented and tested via unit tests)*
- [x] All `IngestService`, `DirectoryWalker`, and `DocumentReaderRegistry` tests pass (old extension-filter tests updated, not just deleted)

### Quality gates

- [x] `./gradlew test` passes with no red tests
- [x] `./gradlew build` compiles without errors

---

## Task [03-encoding-robustness]

*Depends on: 02-filesystem-text-fallback*

`PlainTextDocumentReader` must decode file content with `Charsets.UTF_8` and `CodingErrorAction.REPLACE` so that Latin-1, Windows-1252, or otherwise malformed files are ingested with replacement characters rather than throwing a `CharacterCodingException`. This improves both existing `.txt` ingestion and the plain-text fallback path introduced in task 02.

### Implementation steps

- [x] **Test first:** add a test in `PlainTextDocumentReaderTest` that writes a temp file via `file.writeBytes(byteArrayOf('h'.code.toByte(), 'i'.code.toByte(), 0xe9.toByte()))` (raw Latin-1 byte invalid as UTF-8) and asserts: (a) `read()` does not throw; (b) the returned `List<Document>` is non-empty; (c) chunk text is non-blank
- [x] Inspect `PlainTextDocumentReader` to check whether it reads with `CodingErrorAction.REPLACE`; if it delegates to Spring AI `TextReader` (which may not expose `REPLACE`), replace the read call with `Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)` using a decoder: `Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString()`
- [x] Run `./gradlew test` and verify all tests pass (no regression in existing UTF-8 tests)

### Acceptance criteria

- [x] A file containing the byte `0xE9` (invalid UTF-8) is ingested without throwing an exception
- [x] The resulting `List<Document>` is non-empty and the chunk text is non-blank
- [x] All existing `PlainTextDocumentReaderTest` tests still pass

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.PlainTextDocumentReaderTest"` passes
- [x] `./gradlew test` passes (no regression)

---

## Task [04-url-text-fallback]

*Depends on: 01-binary-detector, 03-encoding-robustness*

End-to-end vertical slice: when `IngestService` fetches a URL whose `Content-Type` is not one of the explicitly handled types (e.g., `application/json`, `text/yaml`, `application/xml`), it runs binary detection on the raw response bytes. If the body is text (no null byte in first 8 KB) it ingests the content as plain text. If the body is binary it skips with a warning, matching the filesystem behaviour.

### Implementation steps

- [x] **Test first:** extend `IngestServiceTest` URL tests: (a) stubbed URL returning `Content-Type: application/json` with a text body of sufficient length → `ingested > 0`; (b) stubbed URL returning `Content-Type: application/octet-stream` with a body byte array containing `0x00` → `skipped == 1`, warning contains "binary" or "skipping"; update the existing test `unsupported content type emits warning and increments skipped` — if its fixture body is plain text (no null bytes) it will now be ingested, so either change the body fixture to include a `0x00` byte or split it into separate binary/text cases
- [x] Verify `FetchResult` already exposes `bytes: ByteArray` (it does — `UrlFetcher.kt` line 8); no change needed
- [x] In `IngestService` URL path, replace the current `else` branch (warn-and-skip) with: call `BinaryDetector.isBinary(fetchResult.bytes)`; if binary, warn and increment `skipped`; if text, write bytes to a temp file in a `try/finally` block (following the same pattern as the existing `text/plain` URL branch at lines ~152–160), then call `PlainTextDocumentReader(tempFile, chunkSize, chunkOverlap).read()` and ingest the resulting chunks; delete the temp file in the `finally` block
- [x] Run `./gradlew test` and verify all tests pass

### Acceptance criteria

- [x] Ingesting a URL with `Content-Type: application/json` and a plain-text body produces `ingested > 0`
- [x] Ingesting a URL with an unrecognised `Content-Type` and a body containing a null byte produces `skipped == 1` and a warning that includes "binary" or "skipping"
- [x] All previously-passing URL ingestion tests pass (existing `text/html`, `application/pdf`, `text/plain` paths unaffected)
- [x] No temp files are left on disk after a URL text-fallback ingest (cleanup parity with the existing `text/plain` URL path)

### Quality gates

- [x] `./gradlew test` passes with no red tests
- [x] `./gradlew build` compiles without errors
