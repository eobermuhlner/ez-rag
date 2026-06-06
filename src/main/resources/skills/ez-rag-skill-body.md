# ez-rag — Local Document Knowledge Base

`ez-rag` builds and queries a local vector store.

## Operations

### List what's indexed

```sh
ez-rag list
```

Shows every ingested document with its chunk count. Documents whose source file has
changed since ingestion are flagged `[STALE]`. Use this to see what's in the store and
whether anything needs re-ingesting.

### Ingest documents

```sh
ez-rag ingest <file-or-directory> [<more-paths>...]
ez-rag ingest <https://...> [<more-urls>...]
```

Recursively indexes `.txt`, `.md`, and `.pdf` files into the vector store.
Also accepts HTTP/HTTPS URLs — the page is fetched, its text extracted, and ingested
like any other document. Mix files, directories, and URLs freely in a single command.

### Refresh stale documents

```sh
ez-rag reingest
```

Re-ingests every document whose file has changed on disk since it was last ingested.
`[STALE]` flags in `ez-rag list` output are the signal. Use `--all` to force re-ingest
of every document regardless of staleness.

### Search for relevant chunks

```sh
ez-rag search --output xml <question words...>
```

Returns the most semantically similar document chunks to your question using hybrid
BM25 + embedding search. No LLM call. The XML-delimited output uses tags as structural
delimiters — chunk content is placed verbatim between them, no XML escaping:

```xml
<results mode="hybrid">
<result index="1" score="0.98" source="/abs/path/to/file.md" chunk="10">
chunk content here
</result>
<result index="2" score="0.74" source="https://example.com/page" chunk="3">
chunk content here
</result>
</results>
```

Read the returned chunks and synthesize an answer for the user.

### Load surrounding context

```sh
ez-rag chunk <file> <chunkIndex> --window 1
```

Fetches a specific chunk by the `chunkIndex` returned from `search`. Use `--window N`
to also retrieve the N chunks before and after the target — useful when a search hit
references context that continues in an adjacent chunk. The window silently clamps at
file boundaries so you never have to know the exact chunk count.

Use this when a search result is too short to answer the question on its own.

### Check store health

```sh
ez-rag status
```

Shows aggregate counts (documents, chunks, stale count, store size, last ingest time)
and the active configuration (provider, model, embedding settings, chunk parameters).
Does **not** list individual documents — use `ez-rag list` for that.

## Workflow

When the user asks about document content:

1. **Check first** — `ez-rag list` to see which files are already indexed and whether
   any are stale.
2. **Ingest if needed** — `ez-rag ingest <path>` for new files not yet in the store;
   `ez-rag ingest <https://...>` to fetch and index a web page by URL.
   For files already indexed but marked `[STALE]`, run `ez-rag reingest` instead.
3. **Search** — run `ez-rag search --output xml` with 2–3 different phrasings of the
   question. Different wording often surfaces different chunks, especially when scores
   are flat. Example: search for both `"connection timeout"` and `"retry configuration"`
   when the user asks how to handle network errors.
4. **Load chunk context** — for each result chunk that is the primary source of an
   answer, run `ez-rag chunk <file> <chunkIndex> --window 1` to retrieve the
   surrounding chunks. Skip this only when the chunk is clearly self-contained (a
   standalone definition or complete code example with no dangling references);
5. **Synthesize** — read the chunks and answer the user's question in your own words,
   citing which file each piece of information came from.

When the user explicitly asks to ingest files, do that and confirm how many files and
chunks were added.
