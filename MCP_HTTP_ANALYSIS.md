# ez-rag MCP Quality Analysis

## Tool Descriptions

### Strengths

- The `query` vs `search` distinction is clearly communicated: `query` produces an LLM-generated answer; `search` returns raw chunks. Avoids a common confusion.
- `status` explicitly defers to `list` for per-document detail: *"For a per-document inventory with staleness flags, use the `list` tool."* Good cross-referencing.
- `chunk` explains that `chunkIndex` values come from prior search results — preventing misuse.
- `reingest` describes its default behaviour (stale-only) and the opt-in `forceAll` flag clearly.
- Score normalization is documented per-tool: RRF-fused (0–1) for `search`, top-result-relative for `search_bm25`. This is important for `minScore` to be usable.

### Weaknesses

- `search_embedding` does not explain how its scores are normalized, unlike `search` and `search_bm25`.
- `ingest`: the `path` parameter description mixes "file or directory" with "HTTP/HTTPS URL" in a slightly awkward way for a parameter named `path`.
- `reingest` has no required parameters. An agent could call it with no arguments and silently trigger a bulk re-ingestion of all stale documents. A brief warning in the description ("calling with no arguments re-ingests all stale documents") would help.

---

## Result Format & Content

### `status`

```json
{"storeDirPath":"/home/ero/.ez-rag/lucene","chunkCount":87}
```

Clean and minimal. `storeDirPath` is useful for debugging.

### `list`

```json
[{"path":"...","chunkCount":81,"stale":true}, ...]
```

Good. The `stale: true` flag is immediately actionable. No ambiguity.

### `search` / `search_bm25` / `search_embedding`

All three return `path`, `chunkIndex`, `score`, `content`, `headingPath`. Rich and consistent.  
The `headingPath` array (e.g. `["ez-rag","Quick start"]`) is excellent for context — it locates a chunk within the document hierarchy without requiring the agent to read the full file.

One concern: the hybrid `search` returned two completely different chunks with identical scores (`0.9178...`). Ties at the top are unexpected and suggest the RRF fusion or normalization may be collapsing scores in edge cases, making ranking unreliable when the top results are conceptually very different.

### `query`

```json
{"answer":"...","sources":[{"path":"...","chunkIndex":4,"score":0.83,"excerpt":"..."}]}
```

Good structure. `excerpt` in sources aids verification. However the `answer` field is a plain string with no indication of which model was used or whether it was a passthrough (no LLM) response — could be useful metadata for traceability.

### `show` and `chunk` — critical bug

Both tools failed consistently:

```
"error": "Lock held by this virtual machine: /home/ero/.ez-rag/lucene/write.lock"
```

The lock file dated **Jun 4** was set when the MCP server process started. `show` and `chunk` are purely read-only operations (retrieve stored data by index), but they appear to try to acquire the Lucene `IndexWriter` lock. This is architecturally wrong: read-only tools should open a read-only `IndexReader`, not contend for the `IndexWriter`.

The failure was reproducible both in parallel and in subsequent sequential calls — this is not a race condition but a persistent incorrect lock acquisition.

Additionally, the error is returned inside the response payload (`{"chunks":[],"error":"..."}`) rather than as a protocol-level error. An agent that checks only the `chunks` array will silently miss the failure.

---

## Summary Table

| Tool               | Description Quality       | Result Format              | Works Correctly                    |
|--------------------|---------------------------|----------------------------|------------------------------------|
| `status`           | Good                      | Clean                      | Yes                                |
| `list`             | Good                      | Clean                      | Yes                                |
| `ingest`           | Good (path param awkward) | n/a (not tested)           | —                                  |
| `reingest`         | Good (no-args risk)       | n/a                        | —                                  |
| `delete`           | Adequate                  | n/a                        | —                                  |
| `query`            | Good                      | Good, missing model info   | Yes                                |
| `search`           | Good                      | Excellent (`headingPath`)  | Yes (tie-score quirk)              |
| `search_bm25`      | Good                      | Excellent                  | Yes                                |
| `search_embedding` | Missing score norm docs   | Excellent                  | Yes                                |
| `show`             | Good                      | n/a                        | **No — write lock bug**            |
| `chunk`            | Good                      | n/a                        | **No — write lock bug**            |

---

## Write-Lock Architecture Analysis

### Root Cause of the `show` / `chunk` Bug

`McpServerCommand` opens a shared `LuceneRepository` at server startup and injects it into `status`, `list`, and the three search tools. However, `show`, `chunk`, `ingest`, `delete`, and `reingest` each call `LuceneRepository.open(...)` internally, creating a **second `IndexWriter`** on the same directory. Lucene enforces a single-writer-at-a-time contract, so any tool that tries to open its own repository while the server-level one is alive will fail with the `write.lock` error.

### Why `show` and `chunk` Are Broken, But Write Tools Are Not

`ingest`, `delete`, and `reingest` happen to work because they also hold the write lock — they open, write, and close their own repository *after* the server-level repository is already holding the lock. This should also fail, but the observed behaviour suggests the server-level repository may not be holding the lock continuously, or it only fails under concurrent access. This is fragile and will break under concurrent MCP tool calls.

### Recommended Fix

Refactor `show` and `chunk` to accept the shared `LuceneRepository` instance injected at construction time (matching the pattern used by `status`, `list`, and the search tools). For read-only operations this is clearly correct and sufficient.

For `ingest`, `delete`, and `reingest`, the shared repository should also be used for writes. `LuceneRepository` already manages a single `IndexWriter` internally; routing all writes through it is safe and avoids reopening. The alternative — closing and reopening the shared repository around each write — is not thread-safe.

### Risk of Leaving Write Tools Unfixed

If two agents call `ingest` and `search` concurrently (a realistic MCP scenario), the `ingest` call will attempt to open a new writer while the server-level repository holds the lock, producing the same `write.lock` error as `show` and `chunk`.

---

## Priority Issues

1. **`show` and `chunk` incorrectly acquire the Lucene write lock.** They should open a read-only `IndexReader`. This makes two tools completely unusable while the MCP server is running.
2. **Errors returned in payload instead of as protocol errors.** `{"chunks":[],"error":"..."}` — agents will silently ignore empty results without inspecting the error field.
3. **`search` score tie at the top.** Two semantically different chunks received identical RRF scores. *(Note: this is mathematically expected behaviour, not a defect. When result A ranks first in BM25 and is absent from the embedding results, and result B ranks first in embedding and is absent from BM25, both receive the same RRF contribution: `1/(k+1)` from the ranking where they appear and `1/(k+N+1)` from the ranking where they are absent — the formula is symmetric by construction and no code change is required.)*
