# Tasks: XML Output Format for `search` Subcommand

## Task [01-json-source-rename]

Rename the file path key from `"file"` to `"source"` in `formatJson(SearchResult)`. The value can be either a file path or a URL depending on how content was ingested, making `"source"` more accurate. This task touches only `formatJson(SearchResult)` — `formatJson(RagResult)` is out of scope (different schema, different output shape).

### Implementation steps

- [x] Write a failing test asserting `"source"` key is present and `"file"` key is absent in `formatJson(SearchResult)` output
- [x] Change `"file"` to `"source"` in `formatJson(SearchResult)` in `OutputFormatter`
- [x] Update any existing test assertions that currently expect `"file"` in `SearchResult` JSON output

### Acceptance criteria

- [x] `formatJson(SearchResult)` output contains `"source"` as the key for the file path/URL field
- [x] `formatJson(SearchResult)` output does not contain `"file"` as a key
- [x] All `OutputFormatterTest` tests pass with updated assertions
- [x] `formatJson(RagResult)` is unchanged — its `"file"` key, `"sources"` array, and tests are not modified

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new compiler warnings introduced

---

## Task [02-xml-formatter]

Add `formatXml(result: SearchResult): String` to `OutputFormatter`. The method renders a `<results mode="...">` root element containing one `<result>` element per chunk, with raw (unescaped) content between the tags. The format is pretty-printed: each element on its own line, content on the line after the opening tag. Zero chunks produces an empty `<results>` element with no children.

Format contract (from design):
```
<results mode="hybrid">
<result index="1" score="0.98" source="/abs/path/to/file.md" chunk="10">
raw chunk content here
</result>
<result index="2" score="0.74" source="https://example.com/page" chunk="3">
raw chunk content here
</result>
</results>
```

### Implementation steps

- [x] Write failing tests in `OutputFormatterTest`: `<results>` root with `mode` attribute, per-chunk attribute values (`index`, `score` to 2dp, `source`, `chunk`), content between tags, zero-chunk case
- [x] Implement `formatXml(SearchResult)` in `OutputFormatter` to make the tests pass

### Acceptance criteria

- [x] Output starts with `<results mode="` and ends with `</results>`
- [x] Each chunk produces a `<result index="N" score="X.XX" source="..." chunk="N">` element with a 1-based index, score rounded to 2 decimal places, and the chunk's source and chunkIndex values
- [x] Chunk content appears as raw unescaped text on the line after the opening `<result>` tag, with `</result>` on its own line
- [x] Zero chunks produces `<results mode="..."></results>` with no `<result>` children
- [x] Score value `0.9799999` is rendered as `0.98` (2 decimal places)

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new compiler warnings introduced

---

## Task [03-search-xml-cli]

Wire `--output xml` into `SearchCommand`. When the user passes `--output xml`, the command routes to `outputFormatter.formatXml(result)`. The `--output` option description is updated from `"text (default) or json"` to `"text (default), json, or xml"`. Existing `--output text` and `--output json` behaviour is unchanged.

Depends on: Task 02 (xml-formatter)

### Implementation steps

- [x] Write a failing test in `SearchCommandTest`: inject a stub pipeline returning one chunk, set `outputFormat = "xml"`, assert output contains `<results` and `<result`
- [x] Add the `"xml"` branch routing to `outputFormatter.formatXml(result)` in `SearchCommand.call()`
- [x] Update the `--output` option description to `"Output format: text (default), json, or xml."`

### Acceptance criteria

- [x] `--output xml` with a result-returning pipeline produces output containing `<results` and at least one `<result` element
- [x] The `mode` attribute value in `<results mode="...">` matches the `mode` field of the `SearchResult`
- [x] `--output text` output is unchanged — no `<results` or `<result` tags appear
- [x] `--output json` output is unchanged — still produces a `{` ... `}` JSON object
- [x] The `--help` text for the `search` subcommand lists `xml` as a valid output format value

### Quality gates

- [x] `./gradlew test` passes with zero failures
- [x] No new compiler warnings introduced
