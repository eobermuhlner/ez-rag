## Problem Statement

Ingested URLs are always reported as STALE in the `list` output and by the MCP `list` tool. Unlike local files — which can be checked against the filesystem's modification time — URLs have no reliable on-disk anchor to determine whether the stored content is current. As a result, the `reingest` command always re-fetches every URL, even when it was ingested moments ago, wasting network resources and making the staleness indicator meaningless for URL-based sources.

## Solution

Introduce a **FRESH** status for URL sources. Immediately after a URL is successfully ingested or re-ingested, it is marked FRESH. It remains FRESH for a configurable freshness window (default 24 hours). Once that window expires, the URL is reported as STALE and becomes a candidate for re-ingestion. File sources are unaffected and continue to use their existing filesystem modification-time based staleness detection.

## User Stories

1. As a developer using the CLI, I want URLs to show as FRESH immediately after ingestion, so that I can see at a glance that they were recently fetched.
2. As a developer using the CLI, I want URLs to automatically become STALE after 24 hours, so that the list output tells me which URLs need refreshing.
3. As a developer using the CLI, I want to configure the freshness window with a `--url-freshness-hours` flag, so that I can tune how aggressively URLs are re-fetched for my use case.
4. As a developer using the CLI, I want a re-ingest run to skip FRESH URLs, so that URLs are not unnecessarily re-fetched within the freshness window.
5. As a developer using the CLI, I want `reingest --all` to override the freshness check and re-fetch all URLs regardless of FRESH status, so that I can force a full refresh when needed.
6. As a developer using the CLI, I want URLs that were ingested before this feature existed to be treated as STALE, so that the upgrade does not silently suppress needed re-fetches.
7. As a developer using the MCP server, I want the MCP `list` tool to report FRESH or STALE for each URL, so that an AI agent can determine which sources need refreshing.
8. As a developer using the MCP server, I want the MCP `reingest` tool to skip FRESH URLs (just as the CLI does), so that agent-triggered re-ingestion is network-efficient.
9. As a developer using the MCP server, I want to set the freshness window via the `--url-freshness-hours` flag on `mcp-server`, so that the server's threshold is consistent across all MCP tool calls.
10. As a developer using the CLI, I want a successful re-ingest of an unchanged URL to also reset the freshness timer, so that confirming a URL is still current counts as a "check."
11. As a developer using the CLI, I want the JSON output of `list` to include a `status` field with the value `"FRESH"` or `"STALE"`, so that scripts consuming the JSON output can distinguish the two states.
12. As a developer using the CLI, I want the text output of `list` to show `[STALE]` only for documents that are STALE (URLs beyond the window, or changed files), so that the output remains concise.
13. As a developer using the CLI, I want the freshness threshold to apply consistently to both the `list` command and the `reingest` command, so that what I see in `list` matches what `reingest` would skip.

## User Acceptance Tests

1. Given a URL that was just ingested, when I run `ez-rag list`, then the URL appears with no `[STALE]` marker.
2. Given a URL that was ingested more than 24 hours ago (simulated), when I run `ez-rag list`, then the URL appears with a `[STALE]` marker.
3. Given a URL ingested 12 hours ago and `--url-freshness-hours 6`, when I run `ez-rag list --url-freshness-hours 6`, then the URL appears as STALE.
4. Given a URL ingested 3 hours ago and `--url-freshness-hours 6`, when I run `ez-rag list --url-freshness-hours 6`, then the URL appears without a `[STALE]` marker.
5. Given a URL that is currently FRESH, when I run `ez-rag reingest`, then the URL is not re-fetched and the re-ingest summary reports it as skipped.
6. Given a URL that is currently STALE, when I run `ez-rag reingest`, then the URL is re-fetched and the freshness timer is reset; a subsequent `list` shows it as FRESH.
7. Given a URL that is currently FRESH, when I run `ez-rag reingest --all`, then the URL is re-fetched regardless of its FRESH status.
8. Given a URL ingested before this feature was introduced (no freshness timestamp in the index), when I run `ez-rag list`, then the URL appears as STALE.
9. Given a URL re-ingested successfully with unchanged content, when I run `ez-rag list`, then the URL appears as FRESH with the freshness timer reset.
10. Given two documents — one local file and one URL — both STALE, when I run `ez-rag reingest`, then both are re-ingested and the file becomes FRESH (via mtime match) and the URL becomes FRESH (via timer reset).
11. Given a URL and `list --output-format json`, when the URL is FRESH, then the JSON contains `"status": "FRESH"`; when STALE, it contains `"status": "STALE"`.
12. Given the MCP `list` tool is called, when a URL is within the freshness window, then the tool response includes `"status": "FRESH"` for that URL.

## Definition of Done

- All user acceptance tests pass.
- `list`, `reingest`, and `mcp-server` accept `--url-freshness-hours` with a default of 24.
- URLs are marked FRESH immediately after initial ingest and after any successful re-ingest.
- URLs beyond the freshness window are reported as STALE and picked up by `reingest`.
- FRESH URLs are skipped by `reingest` (without `--all`).
- `reingest --all` bypasses freshness and re-fetches all sources.
- URLs with no freshness timestamp in the index (pre-feature) are treated as STALE.
- File-based staleness detection is unchanged.
- The `stale` field in all outputs has been replaced by a `status` field with values `"FRESH"` and `"STALE"`.
- All automated tests listed in the Technical Annex are green.
- No regressions in existing tests.
- README updated to document the `--url-freshness-hours` flag and the FRESH/STALE states.

## Out of Scope

- Per-URL freshness thresholds (one global threshold per command invocation only).
- Freshness state stored anywhere other than the existing Lucene index (no separate config or metadata file).
- HTTP cache-control / `Expires` / `Cache-Control` header interpretation.
- Applying time-based freshness to file sources.
- Automatic background re-ingestion of STALE URLs.
- A `FRESH` override that prevents `reingest --all` from re-fetching.

## Further Notes

- The FRESH/STALE distinction for URLs is purely time-based (wall-clock time of last successful ingest vs. threshold), not content-based. Content-hash comparison still drives whether chunks are actually replaced during re-ingest; the freshness timer only controls whether a URL is a candidate for re-fetching at all.
- The `reingest` command currently reports "Stale documents: N" when run without `--all`. After this change, FRESH URLs are excluded from that count, so the count may decrease compared to pre-feature behaviour.

---

## Technical Annex
> Written against codebase as of: 2026-06-13

### Architectural Decisions

#### New Lucene field: `FIELD_INGEST_TIME`

Add a `StoredField("ingest_time", epochMs: Long)` alongside the existing `FIELD_MTIME`. This stores the wall-clock time of the last successful ingest or re-ingest for any source. It is written by `LuceneRepository.add()` when the document metadata contains an `"ingest_time"` key. For file sources, `ingest_time` is not set (or set to 0), so file staleness is unaffected.

`LuceneRepository` needs a companion `sourceIngestTimeCache: MutableMap<String, Long>` (analogous to `sourceMtimeCache`) built in `buildCache()` and updated in `add()` and `delete()`.

#### `StoreDocumentInfo` data model change

Replace `stale: Boolean = false` with `status: String = "STALE"` in `StoreDocumentInfo`. All callers must be migrated from `doc.stale` to `doc.status == "STALE"`.

```kotlin
// Before
data class StoreDocumentInfo(val path: String, val chunkCount: Int, val mtime: Long = 0L, val stale: Boolean = false, val contentHash: String? = null)

// After
data class StoreDocumentInfo(val path: String, val chunkCount: Int, val mtime: Long = 0L, val status: String = "STALE", val contentHash: String? = null)
```

#### `LuceneRepository.getMetadata()` signature change

Add `urlFreshnessThresholdMs: Long` parameter (default `24 * 60 * 60 * 1000L`). Status computation:

- **File sources** (path does not start with `http://` or `https://`): `status = if (filesystemProbe returns storedMtime) "FRESH" else "STALE"` — unchanged logic, just mapped to the new string.
- **URL sources**: `val ingestTime = sourceIngestTimeCache[src] ?: 0L; status = if (ingestTime > 0 && now - ingestTime < urlFreshnessThresholdMs) "FRESH" else "STALE"`.

`StoreMetadata.staleDocumentCount` continues to count documents where `status == "STALE"`.

#### `IngestService` — write `ingest_time` for URL sources

In the URL ingestion branch (`IngestService.ingestUrl()`), after computing the `FetchResult`, add `"ingest_time" to System.currentTimeMillis()` to each `Document`'s metadata before calling `repository.add()`. File ingestion paths do not set `ingest_time`.

#### `ReIngestService` — skip FRESH URLs, reset timer on re-ingest

- `reIngest(forceAll: Boolean, urlFreshnessThresholdMs: Long)` — new parameter, forwarded to `repository.getMetadata(filesystemProbe, urlFreshnessThresholdMs)`.
- Candidate filtering (non-`forceAll` path): filter to `doc.status == "STALE"` (was `doc.stale`).
- Because re-ingest delegates to `IngestService`, the `ingest_time` is automatically written on successful re-ingest for URLs — no additional change needed here.

#### CLI flags

Add `--url-freshness-hours: Int = 24` option to:
- `ListCommand` — passed as `urlFreshnessThresholdMs = freshnessHours * 3_600_000L` to `repository.getMetadata()`
- `ReIngestCommand` — passed to `ReIngestService.reIngest()`
- `McpServerCommand` — stored as a field, injected into `McpListTool` and `McpReIngestTool` constructors

#### `McpListTool.DocumentInfo` change

Replace `stale: Boolean` with `status: String`. Update tool `@Tool` description to reflect that URLs now show `FRESH` within the freshness window.

#### `McpReIngestTool`

Accept `urlFreshnessThresholdMs: Long` in constructor; forward to `ReIngestService.reIngest()`.

### Automated Testing Decisions

**What makes a good test**: test observable behaviour through the public API of each module — what values are returned and what side-effects occur — not how the values are computed internally. Do not assert on private fields or internal cache state.

**`LuceneRepositoryTest`** (unit, real on-disk index via `@TempDir`):
- After `add()` with `ingest_time` in metadata, `getMetadata()` returns `status = "FRESH"` within the threshold and `status = "STALE"` beyond it.
- A document with no `ingest_time` stored (backward compatibility) returns `status = "STALE"`.
- File sources continue to return `"FRESH"` when `filesystemProbe` returns the stored mtime, and `"STALE"` when it differs or returns null.
- `getMetadata()` with a custom `urlFreshnessThresholdMs` overrides the default.
- After `delete()` and `add()` for a URL, the freshness timer resets to now.

Prior art: `LuceneRepositoryTest` — uses `@TempDir`, fake `EmbeddingModel`, `makeDoc()` helper.

**`IngestServiceTest`** (unit, real on-disk index via `@TempDir`, fake `UrlFetcher`):
- After ingesting a URL source, the resulting `StoreDocumentInfo` has `status = "FRESH"` (within a long threshold).
- After ingesting a file source, the resulting `StoreDocumentInfo` has `status` determined by mtime check, not timer.

Prior art: `IngestServiceTest`.

**`ReIngestServiceTest`** (unit, real on-disk index via `@TempDir`, fake `UrlFetcher`):
- A FRESH URL is skipped by `reIngest(forceAll = false, ...)` within the freshness window.
- A STALE URL (beyond the window) is re-fetched.
- After successful re-ingest of a STALE URL, a subsequent `getMetadata()` returns `status = "FRESH"`.
- `reIngest(forceAll = true, ...)` re-fetches a FRESH URL.
- `staleFound` count in `ReIngestResult` reflects only STALE documents (FRESH URLs excluded).

Prior art: `ReIngestServiceTest`.

**`McpListToolTest`** (unit, real on-disk index via `@TempDir`):
- `list()` returns `status = "FRESH"` for a recently-ingested URL and `status = "STALE"` for a URL beyond the threshold.
- `list()` continues to return `status = "FRESH"` / `"STALE"` for files using the existing filesystem-probe injection.

Prior art: `McpListToolTest`.

**`McpReIngestToolTest`** (unit, real on-disk index via `@TempDir`, fake `UrlFetcher`):
- A FRESH URL is not re-fetched; result reflects it as skipped.

Prior art: `McpReIngestToolTest`.

**`ListCommandTest`** (unit, real on-disk index via `@TempDir`):
- Text output shows no `[STALE]` marker for a FRESH URL.
- Text output shows `[STALE]` for a URL beyond the configured threshold.
- JSON output includes `"status": "FRESH"` / `"status": "STALE"`.
- `--url-freshness-hours` flag is accepted and alters the output.

Prior art: `ListCommandTest`.

**`ReIngestCommandTest`** (unit, real on-disk index via `@TempDir`, fake `UrlFetcher`):
- `--url-freshness-hours` flag is accepted and controls which URLs are skipped.

Prior art: `ReIngestCommandTest`.
