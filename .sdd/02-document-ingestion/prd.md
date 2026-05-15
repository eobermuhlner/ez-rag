## Problem Statement

Users need to populate a local vector store with content from their project's documents before they can query it. Without an ingestion pipeline, there is nothing to search against. The pipeline must handle multiple file formats, split large documents into manageable chunks, embed them, and persist the result to disk so it survives between tool invocations.

## Solution

Implement the `ingest` subcommand. It accepts one or more file or directory paths, recursively discovers supported files (`.txt`, `.pdf`, `.md`), loads and parses each file into a Spring AI `Document`, splits documents into overlapping token-sized chunks, embeds them using the configured embedding provider, stores them in a `SimpleVectorStore`, and saves the store to `.ez-rag/vector-store.json` (or the configured path). Progress and a summary are printed to stdout.

## User Stories

1. As a user, I want to run `ez-rag ingest report.pdf`, so that the PDF's content is searchable.
2. As a user, I want to run `ez-rag ingest docs/`, so that all supported files under `docs/` are ingested recursively.
3. As a user, I want to run `ez-rag ingest file1.md file2.txt`, so that I can ingest multiple files in one command.
4. As a user, I want `.txt`, `.pdf`, and `.md` files to be supported out of the box, so that I can ingest the most common document formats without extra configuration.
5. As a user, I want unsupported file types to be skipped with a warning, so that I can point `ingest` at a mixed directory without errors.
6. As a user, I want large documents automatically split into overlapping chunks (default: 1000 tokens, 200 overlap), so that individual chunks fit within the embedding model's context window and retrieval is precise.
7. As a user, I want chunk size and overlap configurable via `--chunk-size` and `--chunk-overlap` flags, so that I can tune chunking for my documents.
8. As a user, I want the vector store saved to `.ez-rag/vector-store.json` by default, so that ingested data persists between `ez-rag` invocations.
9. As a user, I want to override the store path with `--store <path>`, so that I can maintain multiple stores for different purposes.
10. As a user, I want the store to be updated incrementally (existing chunks preserved, new chunks appended), so that re-ingesting one file does not discard previously ingested documents.
11. As a user, I want already-ingested files (same path + modification time) to be skipped, so that re-running `ingest` on a directory is idempotent and fast.
12. As a user, I want a summary printed at the end (`X files ingested, Y chunks created, Z skipped`), so that I know what happened.
13. As a user, I want `--verbose` to print each file and chunk as it is processed, so that I can debug ingestion issues.
14. As a developer, I want document loading, chunking, embedding, and store persistence to be separate modules with clean interfaces, so that each can be tested and evolved independently.
15. As a user, I want the `.ez-rag/` directory created automatically if it does not exist, so that I don't have to create it manually before first use.
16. As a user, I want ingestion to fail fast with a clear error message if the embedding provider is misconfigured (e.g., missing API key), so that I don't waste time waiting only to get a cryptic error.

## Implementation Decisions

- **DocumentLoader module**: Accepts a `Path`, detects the file type by extension, and returns a list of Spring AI `Document` objects. Delegates to:
  - `TextReader` for `.txt`
  - `PagePdfDocumentReader` (PDFBox-based) for `.pdf`
  - A markdown-aware reader (strip front-matter, pass through as text) for `.md`
  Spring AI's built-in readers are used where available.
- **DirectoryWalker module**: Accepts one or more `Path` arguments. Recursively enumerates files with supported extensions. Returns a list of `Path` objects sorted deterministically (alphabetical).
- **DocumentChunker module**: Wraps Spring AI's `TokenTextSplitter`. Accepts a list of `Document` objects and chunk/overlap parameters. Returns a flat list of chunked `Document` objects, each carrying source metadata (original file path, chunk index).
- **VectorStoreRepository module**: Manages the lifecycle of the `SimpleVectorStore` — load from disk if the JSON file exists, add new documents, save back to disk. Exposes `load()`, `add(documents)`, `save()`, `getMetadata()`.
- **Ingestion deduplication**: Each ingested file's path and last-modified timestamp are stored as metadata on its chunks. On subsequent runs, `VectorStoreRepository` checks existing metadata and skips files whose path+mtime matches.
- **Embedding provider**: Resolved by `ConfigService`. The `EmbeddingModel` bean is conditionally created based on `embeddingProvider` config (covered in PRD 04). `DocumentChunker` and `VectorStoreRepository` depend only on Spring AI's `EmbeddingModel` interface.
- **Output**: Prints one line per file in human-readable mode. In JSON mode, outputs a single JSON object with `filesIngested`, `chunksCreated`, `filesSkipped`.

## Testing Decisions

- **What makes a good test**: Test what each module produces given known inputs — file content → documents, documents → chunks, chunks → store state. Do not test Spring AI internals.
- **DocumentLoader**: Unit-test with small sample `.txt`, `.pdf`, and `.md` files in `src/test/resources`. Assert that the returned `Document` list is non-empty and metadata contains the source path.
- **DocumentChunker**: Unit-test that a document larger than the chunk size produces multiple chunks, that overlap produces repeated tokens at chunk boundaries, and that source metadata is propagated.
- **VectorStoreRepository**: Unit-test with an in-memory `SimpleVectorStore`. Test `add` followed by `getMetadata` returns the expected document count. Test save/load round-trip using a temp file.
- **DirectoryWalker**: Unit-test with a temp directory containing mixed file types. Assert that only supported extensions are returned.
- **Integration test**: End-to-end `ingest` of a small `.txt` file to a temp store path, then assert the store file exists and is non-empty.

## Out of Scope

- Word (`.docx`), HTML, and other formats — can be added later.
- Updating or removing previously ingested documents (beyond skip-on-same-mtime).
- Parallel ingestion of multiple files.
- Progress bars or streaming output.
- Embedding provider implementation — covered in PRD 04.

## Further Notes

The `VectorStoreRepository` is the deepest module in this PRD — it encapsulates all knowledge of the on-disk JSON format and Spring AI's `SimpleVectorStore` API. All other code should talk to `VectorStoreRepository` rather than to `SimpleVectorStore` directly. This will make it easy to swap in a different vector store implementation later.
