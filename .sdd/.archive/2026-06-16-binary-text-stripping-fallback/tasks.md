## Task [01-binary-text-stripper]

Implement a new pure `BinaryTextStripper` object that extracts printable ASCII runs of at least 4 characters from a byte array, joining qualifying runs with newlines. This is the core algorithm used by both the file and URL ingestion paths.

### Implementation steps

- [x] Write failing unit tests in `BinaryTextStripperTest` (package `ch.obermuhlner.ezrag.ingestion`) covering: empty input, all-non-printable, run of exactly 3 chars (below min), run of exactly 4 chars, two separated runs, embedded newline within a run, mixed printable and non-printable bytes
- [x] Create `BinaryTextStripper` object in `ch.obermuhlner.ezrag.ingestion`
- [x] Implement `strip(bytes: ByteArray): String`: scan bytes for contiguous runs of printable ASCII (0x20–0x7E) plus `\t` (0x09), `\n` (0x0A), `\r` (0x0D); discard runs shorter than 4; join qualifying runs with `"\n"`; preserve embedded newlines within a run as-is
- [x] Verify all unit tests pass with `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.BinaryTextStripperTest"`

### Acceptance criteria

- [x] `strip(ByteArray(0))` returns `""`
- [x] `strip` on a byte array with only non-printable bytes (e.g. all null bytes) returns `""`
- [x] A single run of exactly 3 printable chars returns `""` (below minimum run length)
- [x] A single run of exactly 4 printable chars (e.g. `"test"`) returns `"test"`
- [x] Two runs of 4+ printable chars separated by non-printable bytes are joined with a single `"\n"` in the output
- [x] A `\n` byte within a run of 4+ printable chars is preserved in the output (not treated as a run separator)
- [x] Any byte outside 0x20–0x7E and not `\t`/`\n`/`\r` breaks the current run

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.BinaryTextStripperTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions in any other test

---

## Task [02-file-binary-stripping]

Update `IngestService` so that when the file ingestion path catches an `IllegalArgumentException` from `DocumentReaderRegistry` (binary file detected), it calls `BinaryTextStripper` and ingests the extracted text instead of skipping — while still skipping files that yield no extractable text.

The `fileBytes` variable is already read before `registry.read()` is called (and before the exception is thrown), so the stripping call reuses that in-scope value rather than reading the file a second time.

### Implementation steps

- [x] Write failing tests in `IngestServiceTest`: (a) a binary file with embedded printable strings → `filesIngested >= 1` and a warning; (b) a binary file with no printable text (all null bytes) → `skipped = 1` and a warning
- [x] Verify the new tests fail before any implementation change (`./gradlew test`)
- [x] In `IngestService`, replace the `catch (e: IllegalArgumentException)` skip block: call `BinaryTextStripper.strip(fileBytes)` (reusing the already-read bytes), emit a warning, and for non-blank result write to a temp file and ingest via `PlainTextDocumentReader`
- [x] If the stripped text is blank, emit a second warning ("No extractable text in binary file") and increment `skipped`
- [x] Verify all tests pass

### Acceptance criteria

- [x] Ingesting a directory containing a binary file with at least 4 embedded printable chars produces `filesIngested >= 1` and a warning message containing "stripped to plain text"
- [x] Ingesting a binary file that is all null bytes produces `skipped = 1`, emits a warning that stripping was attempted, and emits a second warning that no extractable text was found
- [x] A plain-text file with an unknown extension is still ingested without any binary warning (no regression in plain-text fallback)
- [x] A file with a known extension handled by a dedicated parser (e.g. `.docx`) continues to use that parser (no regression)
- [x] `DocumentReaderRegistry` still throws `IllegalArgumentException` for unknown-extension binary files (its contract is unchanged)

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.IngestServiceTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions in any other test

---

## Task [03-url-binary-stripping]

Update `IngestService` to strip text from binary URL responses rather than skipping them, using the same `BinaryTextStripper` and temp-file pattern as the file path. Update README.md to document the binary text-stripping behavior.

### Implementation steps

- [x] Write failing tests in `IngestServiceTest`: (a) a binary URL response with embedded printable strings → `filesIngested >= 1` and a warning; (b) a binary URL response that is all null bytes → `skipped = 1` and a warning
- [x] Verify the new tests fail before any implementation change (`./gradlew test`)
- [x] In `IngestService`, replace the binary URL skip block: call `BinaryTextStripper.strip(fetchResult.bytes)`, emit a warning ("Binary content stripped to plain text at URL"), and for non-blank result write to a temp file and ingest via `PlainTextDocumentReader` (mirroring the existing plain-text URL path)
- [x] If stripped text is blank, emit a second warning ("No extractable text in binary content at URL") and increment `skipped`
- [x] Update README.md to document that binary files and binary URL responses are partially indexed via text stripping (minimum 4-char printable ASCII runs)
- [x] Verify all tests pass

### Acceptance criteria

- [x] Ingesting a binary URL response with at least 4 embedded printable chars produces `filesIngested >= 1` and a warning containing "stripped to plain text"
- [x] Ingesting a binary URL response that is all null bytes produces `skipped = 1` and a warning that no extractable text was found
- [x] Plain-text URL responses continue to be ingested without a binary warning (no regression)
- [x] HTML and PDF URL responses continue to be routed to their dedicated readers (no regression)
- [x] README.md documents that binary files and URLs are partially indexed via text stripping

### Quality gates

- [x] `./gradlew test --tests "ch.obermuhlner.ezrag.ingestion.IngestServiceTest"` passes with no failures
- [x] `./gradlew test` passes with no regressions in any other test
