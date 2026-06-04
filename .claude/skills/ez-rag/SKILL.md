---
name: ez-rag
description: >
  Maintain and query a local document knowledge base using ez-rag. Use this skill whenever
  the user wants to index files or a directory so Claude can answer questions from them,
  search for relevant information across ingested documents, check what documents are
  already in the store, or asks "what do the docs say about X?". Trigger on: "ingest these
  files", "add to the knowledge base", "search for X in the docs", "what does the
  README say about Y?", "index this folder", or any document-retrieval or RAG workflow.
  Do NOT use this for the `ez-rag mcp-server` subcommand.
---

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
```

Recursively indexes `.txt`, `.md`, and `.pdf` files into the vector store.

### Refresh stale documents

```sh
ez-rag reingest
```

Re-ingests every document whose file has changed on disk since it was last ingested.
`[STALE]` flags in `ez-rag list` output are the signal. Use `--all` to force re-ingest
of every document regardless of staleness.

### Search for relevant chunks

```sh
ez-rag search <question words...>
```

Returns the most semantically similar document chunks to your question. No LLM call —
pure embedding similarity. Read the returned chunks and synthesize an answer for the user.

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
2. **Ingest if needed** — `ez-rag ingest <path>` for new files not yet in the store.
   For files already indexed but marked `[STALE]`, run `ez-rag reingest` instead.
3. **Search** — run `ez-rag search` with 2–3 different phrasings of the question.
   Different wording often surfaces different chunks, especially when scores are flat.
   Example: search for both `"connection timeout"` and `"retry configuration"` when
   the user asks how to handle network errors.
4. **Load chunk context** — for each result chunk that is the primary source of an
   answer, run `ez-rag chunk <file> <chunkIndex> --window 1` to retrieve the
   surrounding chunks. Skip this only when the chunk is clearly self-contained (a
   standalone definition or complete code example with no dangling references);
5. **Synthesize** — read the chunks and answer the user's question in your own words,
   citing which file each piece of information came from.

When the user explicitly asks to ingest files, do that and confirm how many files and
chunks were added.

## Sandbox note

You MUST run the `ez-rag` commands outside of the sandbox. 
They require `dangerouslyDisableSandbox: true` 
