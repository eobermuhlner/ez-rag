# Tasks: Delete and Show Commands

## Task [01-normalize-ingest-paths]

`IngestService` currently stores source paths exactly as received (e.g. `./docs/file.md`). `DocumentLoader` stamps this same raw path into each `Document`'s `source` metadata. The `delete` and `show` commands need to match on file paths regardless of how they were originally passed to `ingest`. This task normalises source paths to absolute at ingest time and adds a `chunk_index` field so chunks can be reliably ordered when shown.

### Implementation steps

- [x] Write a failing test: ingest a file via a relative path; assert that the stored `source` metadata (read via `VectorStoreRepository.getMetadata()`) contains the absolute path, not the relative form
- [x] In `IngestService`, resolve each path to `path.toAbsolutePath().normalize()` before passing it to `DocumentLoader.load()` and before computing `sourceKey`
- [x] Extend `IngestService.withMtime()` (or the equivalent post-split step) to stamp a `chunk_index` integer on each chunk, counting from 0 within a single document's chunk list
- [x] Write a failing test: ingest a multi-chunk document and verify each chunk carries a distinct `chunk_index` in ascending order starting from 0
- [x] Verify that all existing `IngestCommandTest` and `VectorStoreRepositoryTest` tests still pass

### Acceptance criteria

- [x] After ingesting via `./docs/file.md`, `VectorStoreRepository.getMetadata()` lists the file with its absolute path
- [x] Each stored `Document` has a `source` metadata value that is an absolute, normalised path (not relative)
- [x] Each stored `Document` has a `chunk_index` integer metadata value; for a file that produces N chunks the values are `0, 1, …, N-1`
- [x] `isAlreadyIngested` returns `true` when called with the absolute path of a file previously ingested via a relative path form
- [x] Re-ingesting the same file via a different relative form that resolves to the same absolute path is treated as "already ingested" (skipped)

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new Kotlin compiler warnings introduced
- [x] After the operation, reloading the store file with `VectorStoreRepository.load()` succeeds without exceptions

---

## Task [02-delete-command]

End-to-end delete support: a repository method removes all chunks for a given file, a CLI subcommand exposes it to the user, and an MCP tool exposes it to agents. A developer can run `ez-rag delete file1.md file2.md` to purge one or more documents from the store without touching any other content.

### Implementation steps

- [x] Write a failing `VectorStoreRepositoryTest`: `delete()` removes all chunks for the target file, leaves other files' chunks untouched, and returns the number of chunks removed
- [x] Write a failing `VectorStoreRepositoryTest`: `delete()` on an unknown path returns 0
- [x] Add `delete(absoluteFilePath: String): Int` to `VectorStoreRepository`: collect matching document IDs via the same reflection approach used in `getMetadata()`, call `VectorStore.delete(ids)` (public API on the `VectorStore` interface), evict all `(source, mtime)` pairs for the file from the `ingestedFiles` cache, and return the chunk count
- [x] Write a failing `DeleteCommandTest`: default output line, `--quiet` flag, unknown-file warning, multi-file invocation, exit codes
- [x] Add `DeleteCommand` picocli subcommand: accept one or more positional file paths, normalise each to absolute via `Path.toAbsolutePath().normalize()`, delegate to `VectorStoreRepository.delete()`, print result lines, save the store
- [x] Register `DeleteCommand` in `EzRagCommand` as a subcommand
- [x] Write a failing `McpDeleteToolTest`: calling the MCP tool with a valid file path removes the document and returns a confirmation message
- [x] Add `McpDeleteTool` Spring bean (single `filePath` string parameter); wire it into the MCP server alongside the existing MCP tools
- [x] Update README with a `### delete` section including usage examples and flag descriptions

### Acceptance criteria

- [x] `repository.delete(absolutePath)` returns the number of chunks removed (e.g. `3` for a file with 3 chunks)
- [x] After `repository.delete(absolutePath)`, `getMetadata()` no longer lists the deleted file
- [x] `repository.delete(absolutePath)` for file A does not affect chunks belonging to file B
- [x] `repository.delete(unknownPath)` returns `0` without error
- [x] After `repository.delete(absolutePath)`, `isAlreadyIngested(absolutePath, mtime)` returns `false`
- [x] `ez-rag delete <file>` prints `Deleted: <absolute-path> (<N> chunks)` to stdout and exits 0
- [x] `ez-rag delete --quiet <file>` produces no output on success and exits 0
- [x] `ez-rag delete <unknown-file>` prints a warning line (`Warning: not found in store: …`) and exits 0
- [x] `ez-rag delete file1.md file2.md` deletes both documents and prints a result line for each
- [x] After deletion, re-ingesting the same file with `ingest` adds it back to the store successfully
- [x] README contains a `delete` section with at least one usage example

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new Kotlin compiler warnings introduced
- [x] `ez-rag delete --help` renders usage without error
- [x] After `delete`, reloading the store file with `VectorStoreRepository.load()` succeeds without exceptions

---

## Task [03-show-command]

End-to-end document inspection: a repository method returns structured chunk data for a file, a CLI subcommand formats it for the terminal, and an MCP tool makes it available to agents. A developer can run `ez-rag show <file>` to see per-chunk metadata and optionally raw chunk text.

### Implementation steps

- [x] Write a failing `VectorStoreRepositoryTest`: `getChunksForFile()` returns chunks sorted by `chunkIndex` with correct `charCount` and `mtime` values
- [x] Write a failing `VectorStoreRepositoryTest`: `getChunksForFile()` on an unknown path returns an empty list
- [x] Add `DocumentChunkInfo(chunkIndex: Int, charCount: Int, mtime: Long, text: String)` data class alongside the existing store data classes
- [x] Add `getChunksForFile(absoluteFilePath: String): List<DocumentChunkInfo>` to `VectorStoreRepository`: iterate the store map via reflection, filter by `source`, map to `DocumentChunkInfo`, sort by `chunkIndex`, handle missing `chunk_index` gracefully (default to 0)
- [x] Write a failing `ShowCommandTest`: default text output shows chunk count and per-chunk metadata without raw text; `--chunks` adds raw text; `--output json` produces valid JSON; unknown file exits non-zero
- [x] Add `ShowCommand` picocli subcommand: accept exactly one positional file path, normalise to absolute via `Path.toAbsolutePath().normalize()`, call `getChunksForFile()`, exit non-zero with an error message if the result is empty, format and print output
- [x] Register `ShowCommand` in `EzRagCommand` as a subcommand
- [x] Write a failing `McpShowToolTest`: calling the tool with a valid file path returns a structured result with chunk metadata
- [x] Add `McpShowTool` Spring bean (`filePath: String`, `includeChunks: Boolean`); wire it into the MCP server
- [x] Update README with a `### show` section including usage examples for `--chunks` and `--output json`

### Acceptance criteria

- [x] `repository.getChunksForFile(absolutePath)` returns all chunks sorted by ascending `chunkIndex`
- [x] Each returned `DocumentChunkInfo` has the correct `charCount` (length of the chunk text in characters) and a non-zero `mtime` matching the file's last-modified time at ingest
- [x] `repository.getChunksForFile(unknownPath)` returns an empty list
- [x] `ez-rag show <file>` prints a header line (`File: <absolute-path>`, `Chunks: <N>`) and one metadata line per chunk (`Chunk <N> — <M> chars, mtime: <T>`); does NOT print raw text
- [x] `ez-rag show --chunks <file>` also prints the raw text of each chunk beneath its metadata line
- [x] `ez-rag show --output json <file>` produces valid JSON with a `file` string and a `chunks` array; each chunk object contains `chunkIndex`, `charCount`, and `mtime` but omits the `text` field
- [x] `ez-rag show --output json --chunks <file>` includes a `text` field in each chunk object
- [x] `ez-rag show <unknown-file>` exits with a non-zero code and prints an error message to stderr
- [x] README contains a `show` section with examples for the default form, `--chunks`, and `--output json`

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new Kotlin compiler warnings introduced
- [x] `ez-rag show --help` renders usage showing `--chunks` and `--output` flags
