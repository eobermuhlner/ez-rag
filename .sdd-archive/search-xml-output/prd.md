# PRD: XML Output Format for `search` Subcommand

## Problem Statement

When using `ez-rag search` as a tool in LLM pipelines (e.g. Claude Code), the available output formats are either human-readable plain text or JSON. Plain text is not easily machine-parseable; JSON requires an explicit parser. For LLM consumption, XML-delimited output is a natural, readable, and widely-used convention that allows an LLM to reliably extract individual results by tag structure without a JSON parser. The current `--output` flag does not support this.

Additionally, the JSON format inconsistently names the file path field `file`, while `source` is the more accurate term (the value can be either a file path or a URL depending on how content was ingested).

## Solution

Add `xml` as a new valid value for the existing `--output` flag on the `search` subcommand. The XML format wraps all results in a `<results>` root element (with a `mode` attribute) and renders each chunk as a `<result>` element with `index`, `score`, `source`, and `chunk` attributes. Chunk content is placed as raw text between the tags — no XML escaping — treating the tags as delimiters rather than strict XML.

As a bundled consistency fix, rename the `file` key to `source` in the JSON output for `SearchResult`.

## User Stories

1. As a developer piping `ez-rag search` output into a Claude Code prompt, I want XML-delimited results, so that the LLM can reliably identify and extract individual chunks by tag structure.
2. As a developer scripting with `ez-rag search`, I want a `--output xml` flag, so that I can select the XML format without changing existing default behaviour.
3. As a developer reading XML output, I want each result to show its rank index, similarity score, source path/URL, and chunk index as attributes, so that I can identify the provenance of each result at a glance.
4. As a developer using hybrid or BM25 search, I want the `<results>` element to carry the `mode` attribute, so that I can confirm which search mode produced the results.
5. As a developer consuming output with a URL source, I want the attribute named `source` (not `file`), so that the attribute name accurately reflects both file-path and URL sources.
6. As a developer processing JSON output, I want the path/URL field named `source` (not `file`), so that the JSON and XML formats are consistent.
7. As a developer with existing scripts using `--output text` or `--output json`, I want those formats unchanged, so that my scripts are not broken by this change.
8. As a developer, I want the score in XML output rounded to two decimal places, so that the output is concise and consistent with the text format.
9. As a developer consuming XML output with Markdown or code content in chunks, I want raw unescaped content between tags, so that the content is readable without an XML parser.
10. As a developer, I want an empty `<results mode="..."></results>` when no chunks are found, so that output is always structurally consistent regardless of result count.

## Implementation Decisions

### Modules to modify

**`OutputFormatter`** (deep module, pure formatting logic)
- Add `formatXml(result: SearchResult): String`
- Fix `formatJson(result: SearchResult): String` to use `source` instead of `file` as the key for the file path
- No changes to `formatText`, `formatJson(RagResult)`, or `formatText(RagResult)`

**`SearchCommand`**
- Add `xml` as a documented valid value in the `--output` option description string
- Route to `outputFormatter.formatXml(result)` when `outputFormat == "xml"`

### XML format contract

```
<results mode="hybrid">
<result index="1" score="0.98" source="/abs/path/to/file.md" chunk="10">
raw chunk content here — no escaping
</result>
<result index="2" score="0.74" source="https://example.com/page" chunk="3">
raw chunk content here
</result>
</results>
```

- Root element: `<results mode="${result.mode}">`
- Per-chunk element: `<result index="${1-based}" score="${"%.2f".format(chunk.score)}" source="${chunk.filePath}" chunk="${chunk.chunkIndex}">`
- Content placed on the line after the opening tag, `</result>` on its own line
- No XML escaping of content — tags used as LLM-friendly delimiters
- Empty results: `<results mode="..."></results>`

### JSON consistency fix

The `formatJson(SearchResult)` method currently emits `"file"` for the chunk file path. This is renamed to `"source"` to match the XML attribute name and to accurately describe the value (which may be a URL).

## Testing Decisions

Good tests verify observable output behaviour — the string content of what `OutputFormatter` returns, or the exit code and printed output of `SearchCommand`. Tests do not inspect internal state or call private methods.

**`OutputFormatterTest`** — prior art for all new XML tests
- Verify `formatXml` with two chunks: `<results>` root present, `mode` attribute present
- Verify `<result>` attribute values: correct `index`, `score` (2dp), `source`, `chunk`
- Verify content appears between tags
- Verify zero chunks produces `<results ...></results>` (no inner `<result>` elements)
- Verify existing JSON test updated: `"source"` key present, `"file"` key absent

**`SearchCommandTest`** — prior art: `--mode bm25 --output json produces top-level mode field` test
- Verify `--output xml` produces output containing `<results` and `<result`
- Verify the `mode` attribute in XML output matches the search mode

## Out of Scope

- XML output for the `query` subcommand (`RagResult`) — different output shape, separate design needed
- XML escaping or CDATA support — content is treated as raw delimited text for LLM consumption
- Renaming `file` to `source` in `formatJson(RagResult)` — that format uses a `sources` array and has a different schema; it is not changed here

## Further Notes

The XML format is intentionally not valid XML in the strict sense — chunk content is not escaped. This is a deliberate trade-off: the primary consumer is an LLM, which reads the tags as structural delimiters rather than requiring a conformant XML parser. Users who need strict XML should use `--output json` and parse with standard tooling.
