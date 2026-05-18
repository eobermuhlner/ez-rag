The goal of this project is a simple command line tool for RAG (retrieval-augmented generation).


## Test Driven Development

You MUST use test driven development (TDD) to build this project.
This means you will write a failing test before you write any implementation code, and then write just enough code to make the test pass. You will repeat this cycle for each new feature or piece of functionality.

Write one test at a time, and make it pass before moving on to the next test.

## Documentation

Keep the documentation up to date.

- README.md for users and developers
- CLAUDE.md for agentic coding instructions

## Architecture

### Storage: LuceneRepository

All chunk data — embedding vectors and BM25 text — lives in a single `LuceneRepository` backed by an on-disk Lucene index at `<storeDir>/lucene/`.

- **Semantic search**: HNSW `KnnFloatVectorField` with COSINE similarity.
- **BM25 search**: `TextField` with Lucene's `QueryParser`.
- **Single writer**: Lucene enforces one `IndexWriter` at a time; always use `LuceneRepository.open(...).use { }` and never hold two instances open on the same directory simultaneously.
- **Dimension validation**: `open()` reads the stored embedding dimension from index metadata and throws `IllegalStateException` if the current model's dimension mismatches. Pass `dimension = 0` for read-only callers (e.g. `status`) that do not embed anything.

`VectorStoreRepository` and `BM25Repository` have been deleted. Do not recreate them.