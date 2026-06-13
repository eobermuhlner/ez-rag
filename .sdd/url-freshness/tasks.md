## Task 01-url-freshness-list

Deliver the full "URL FRESH/STALE status visible in list output" slice end-to-end. After this task, any URL ingested through `IngestService` is stamped with a wall-clock timestamp; `LuceneRepository.getMetadata()` uses that timestamp to compute `status = "FRESH"` or `"STALE"`; `list` text output suppresses `[STALE]` for FRESH URLs; `list --output-format json` emits `"status"` instead of `"stale"`; the MCP `list` tool returns `status` in its `DocumentInfo`; and both `ListCommand` and `McpServerCommand` accept `--url-freshness-hours` (default 24). File-based sources are unaffected.

Clock injection: `LuceneRepository.getMetadata()` gains a `currentTimeMs: Long = System.currentTimeMillis()` parameter so tests can pass an explicit time without touching real-time.

### Implementation steps

- [x] Write failing `LuceneRepositoryTest`: URL doc with `ingest_time` in metadata returns `status = "FRESH"` within threshold (pass explicit `currentTimeMs`)
- [x] Add `FIELD_INGEST_TIME` stored field to `LuceneRepository`; add `sourceIngestTimeCache: MutableMap<String, Long>`; update `buildCache()`, `add()`, `delete()`
- [x] Update `getMetadata()` signature: add `urlFreshnessThresholdMs: Long = 24 * 3_600_000L` and `currentTimeMs: Long = System.currentTimeMillis()`; compute `status` for URL sources
- [x] Replace `stale: Boolean = false` with `status: String = "STALE"` in `StoreDocumentInfo`; migrate all call sites (`doc.stale` → `doc.status == "STALE"`, `StoreMetadata.staleDocumentCount` → count by `status`)
- [x] Write failing `LuceneRepositoryTest`: backward-compat doc (no `ingest_time`) returns `status = "STALE"`; file source returns `"FRESH"` when probe matches, `"STALE"` when it differs
- [x] Write failing `IngestServiceTest`: after ingesting a URL, `StoreDocumentInfo.status == "FRESH"`; after ingesting a file, status determined by mtime, not timer
- [x] In `IngestService` URL ingestion path, add `"ingest_time" to System.currentTimeMillis()` to each chunk's metadata before `repository.add()`
- [x] Write failing `ListCommandTest`: text output suppresses `[STALE]` for FRESH URL; shows `[STALE]` for STALE URL; JSON output has `"status"` field; `--url-freshness-hours` flag accepted and changes result
- [x] Add `--url-freshness-hours: Int = 24` to `ListCommand`; pass `urlFreshnessThresholdMs` and a `currentTimeMs` override (for tests) to `repository.getMetadata()`
- [x] Write failing `McpListToolTest`: `list()` returns `status = "FRESH"` for recent URL; `status = "STALE"` for URL beyond threshold; file source status unaffected
- [x] Replace `stale: Boolean` with `status: String` in `McpListTool.DocumentInfo`; update `list()` to pass `urlFreshnessThresholdMs` to `getMetadata()`; update `@Tool` description to mention FRESH/STALE
- [x] Add `--url-freshness-hours` to `McpServerCommand`; forward threshold to `McpListTool` constructor

### Acceptance criteria

- [x] After ingesting a URL, `list` text output shows the URL with no `[STALE]` marker
- [x] After simulating that the freshness window has passed (via injected `currentTimeMs`), `list` shows `[STALE]` for the URL
- [x] `list --output-format json` includes `"status": "FRESH"` when URL is within the window and `"status": "STALE"` when beyond it (no `"stale"` boolean field)
- [x] `list --url-freshness-hours 6` treats a URL with an `ingest_time` 12 h ago as STALE and a URL 3 h ago as FRESH
- [x] A URL with no `ingest_time` stored (pre-feature document) returns `status = "STALE"` from `getMetadata()`
- [x] File sources: `getMetadata()` returns `"FRESH"` when filesystem probe matches stored mtime, `"STALE"` when the probe returns a different value or null — unaffected by `urlFreshnessThresholdMs`
- [x] `StoreMetadata.staleDocumentCount` equals the count of documents with `status == "STALE"`
- [x] MCP `list()` returns `DocumentInfo.status = "FRESH"` for a recently-ingested URL and `"STALE"` for one beyond the threshold; `McpListTool` `@Tool` description no longer says URLs always appear as not stale

### Quality gates

- [x] `./gradlew test` passes with no regressions (ReIngestService failures are expected and will be fixed in Task 02)
- [x] No references to `StoreDocumentInfo.stale` or `doc.stale` remain in the codebase (`grep -r "\.stale" src/` returns no results)

---

## Task 02-reingest-freshness

Deliver "re-ingest skips FRESH URLs" end-to-end: `ReIngestService` gains a `urlFreshnessThresholdMs` parameter and filters candidates to `status == "STALE"` (skipping FRESH URLs) on the non-`forceAll` path; `ReIngestCommand` gains `--url-freshness-hours`; `McpReIngestTool` accepts the threshold in its constructor; `McpServerCommand --url-freshness-hours` forwards to both `McpListTool` (Task 01) and `McpReIngestTool`. `reingest --all` bypasses freshness and re-fetches everything. Because re-ingest delegates to `IngestService` (Task 01), a successfully re-ingested URL automatically resets the freshness timer.

### Implementation steps

- [x] Write failing `ReIngestServiceTest`: FRESH URL is skipped by `reIngest(forceAll = false, urlFreshnessThresholdMs = ...)`
- [x] Add `urlFreshnessThresholdMs: Long = 24 * 3_600_000L` parameter to `ReIngestService.reIngest()`; forward to `repository.getMetadata()`; change candidate filter from `doc.stale` to `doc.status == "STALE"`
- [x] Write failing `ReIngestServiceTest`: STALE URL is re-fetched and subsequent `getMetadata()` returns `status = "FRESH"`; `forceAll = true` re-fetches a FRESH URL; `staleFound` count excludes FRESH URLs; `staleFound` is `null` when `forceAll = true`
- [x] Write failing `ReIngestCommandTest`: `--url-freshness-hours` flag accepted; FRESH URL is skipped; STALE URL is re-fetched
- [x] Add `--url-freshness-hours: Int = 24` to `ReIngestCommand`; pass `urlFreshnessThresholdMs` to `ReIngestService.reIngest()`
- [x] Write failing `McpReIngestToolTest`: FRESH URL is not re-fetched when threshold is short
- [x] Add `urlFreshnessThresholdMs: Long` to `McpReIngestTool` constructor; forward to `ReIngestService.reIngest()`
- [x] Update `McpServerCommand` to forward `--url-freshness-hours` to both `McpListTool` and `McpReIngestTool`
- [x] Update README: document `--url-freshness-hours` on `list`, `reingest`, and `mcp-server`; describe FRESH/STALE states

### Acceptance criteria

- [x] A FRESH URL is skipped by `reIngest(forceAll = false, ...)` and is not re-fetched
- [x] A STALE URL is re-fetched; a subsequent `getMetadata()` returns `status = "FRESH"` for it
- [x] `reIngest(forceAll = true, ...)` re-fetches a FRESH URL regardless of freshness status
- [x] `ReIngestResult.staleFound` counts only STALE documents; FRESH URLs are excluded from that count
- [x] `ReIngestResult.staleFound` is `null` when `forceAll = true` (existing behaviour preserved)
- [x] CLI `reingest --url-freshness-hours N` controls which URLs are treated as candidates; skipped URLs appear in `filesSkipped`
- [x] MCP `reingest` skips FRESH URLs using the threshold injected from `McpServerCommand --url-freshness-hours`

### Quality gates

- [x] `./gradlew test` passes with no regressions
- [x] README contains `--url-freshness-hours` in the `list`, `reingest`, and `mcp-server` sections
