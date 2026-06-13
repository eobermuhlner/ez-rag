# CLI Simplification

## Problem Statement

Users testing ez-rag find several CLI options confusing or pointless in practice:

- Embedding provider and model can be set per-invocation, but changing them after the first ingest silently breaks future searches because stored chunk vectors become incompatible with the new model. There is no place to set them once and have them apply consistently to a store.
- The `--details` flag on `ingest` duplicates the effect of `--verbose` (which is already on the parent command) and is therefore redundant.
- The `--min-score` filter on `search` has a default of 0.0 and is effectively a no-op after hybrid RRF scoring; users have no intuition for what threshold value to use.
- The output format flag is named `--output` on `query` and `shell` but `--output-format` on every other command, creating unnecessary inconsistency.

## Solution

Introduce a configuration-writing step to `init` that lets users record embedding settings (and other store-level defaults) once into the store's local config file. Remove `--embedding-provider` and `--embedding-model` as global CLI flags; the tool reads them from config instead. Remove the redundant and non-functional options. Standardise the output format flag name across all commands.

## User Stories

1. As a user, I want to run `ez-rag init --embedding-provider=openai --embedding-model=text-embedding-3-small` once, so that I do not have to repeat embedding flags on every subsequent command.
2. As a user, I want the embedding provider and model to be persisted in the store's local config file by `init`, so that all commands in that project automatically use the correct model.
3. As a user, I want to run `ez-rag init --chunk-size=500 --chunk-overlap=50` once, so that ingestion uses my preferred chunk settings without repeating them every time.
4. As a user, I want to run `ez-rag init --ollama-url=http://my-server:11434` once, so that my non-default Ollama instance is used without repeating the URL on every command.
5. As a user, I want `ez-rag init` to always overwrite existing config values without error, so that I can change my embedding provider by re-running `init` and then re-ingesting.
6. As a user, I want `ez-rag ingest` to work without having run `init` first, so that the quick-start experience is not broken for new users who accept the defaults.
7. As a user, I want `ez-rag status` to show the embedding provider and model resolved from the store's config file, so that I can verify which model my store was built with.
8. As a user, I want the output format flag to be consistently named `--output-format` on all commands, so that I do not need to remember which commands use a different name.
9. As a user, I want the `--details` flag removed from `ingest`, so that the option list is not cluttered with duplicates of `--verbose`.
10. As a user, I want the `--min-score` flag removed from `search`, so that the option list does not expose a knob that has no practical effect.
11. As a developer integrating ez-rag in a script, I want the `--output-format` flag to behave identically on `query` and `shell` as it does on other commands, so that my scripts do not need command-specific special cases.
12. As a user switching embedding providers across projects, I want each project's `.ez-rag/config.yml` to record its own embedding settings, so that different projects can use different models without conflict.

## User Acceptance Tests

1. Given a fresh directory with no `.ez-rag/` folder, when `ez-rag init --embedding-provider=openai --embedding-model=text-embedding-3-small` is run, then a `.ez-rag/config.yml` file is created containing the specified embedding provider and model.
2. Given an existing `.ez-rag/config.yml` with `embeddingProvider: onnx`, when `ez-rag init --embedding-provider=openai` is run again, then the config file is updated to reflect the new provider without error.
3. Given a store initialised with `init --chunk-size=500 --ollama-url=http://custom:11434`, when `ez-rag status` is run, then the output shows the chunk size and Ollama URL that were written by `init`.
4. Given no `init` has been run, when `ez-rag ingest myfile.txt` is run, then ingestion succeeds using the built-in defaults (ONNX provider, `all-MiniLM-L6-v2` model).
5. Given a store whose `.ez-rag/config.yml` specifies `embeddingProvider: openai`, when `ez-rag status` is run, then the Configuration section shows `embeddingProvider: openai`.
6. Given the `ez-rag` binary, when `ez-rag --help` is shown, then `--embedding-provider` and `--embedding-model` do not appear as global options.
7. Given the `ez-rag` binary, when `ez-rag init --help` is shown, then `--embedding-provider`, `--embedding-model`, `--chunk-size`, `--chunk-overlap`, and `--ollama-url` are listed as options.
8. Given the `ez-rag` binary, when `ez-rag search --help` is shown, then `--min-score` does not appear.
9. Given the `ez-rag` binary, when `ez-rag ingest --help` is shown, then `--details` does not appear.
10. Given the `ez-rag` binary, when `ez-rag query --help` is shown, then `--output-format` appears and `--output` does not.
11. Given the `ez-rag` binary, when `ez-rag shell --help` is shown, then `--output-format` appears and `--output` does not.
12. Given a query run with `ez-rag query --output-format=json "my question"`, when the command completes, then the output is valid JSON (same behaviour as `--output=json` previously).

## Definition of Done

- All user acceptance tests pass.
- `--embedding-provider` and `--embedding-model` are no longer accepted as global CLI flags.
- `ez-rag init` writes the provided embedding and store-level settings to `.ez-rag/config.yml`.
- `ez-rag ingest` (and all other commands) continue to work without a prior `init`.
- `--min-score` is removed from `search`.
- `--details` is removed from `ingest`.
- `--output` on `query` and `shell` is renamed to `--output-format`.
- No regression in existing behaviour for any other command.
- README updated to document the new `init` options and the removal of the global embedding flags.
- All automated tests pass.

## Out of Scope

- Validating embedding model/provider consistency against the Lucene index when `init` is re-run with a different model (users are responsible for reingesting).
- Migrating existing `.ez-rag/config.yml` files that were written manually before this feature.
- Adding `--embedding-provider` or `--embedding-model` back as per-command overrides on `ingest`.
- Removing or changing `--rerank-model` or `--rerank-candidates`.
- Removing or changing `--mode` on `search`.
- Any change to MCP tool definitions.

## Further Notes

- The config file format is YAML, consistent with the existing `~/.ez-rag/config.yml` home-level config already supported by `ConfigFileReader`.
- The pre-parse step in `EzRagApplication.main()` currently reads `--embedding-provider` and `--embedding-model` from CLI args to bootstrap Spring before the context starts. After this change it must instead read the local `.ez-rag/config.yml` (using the resolved store directory) to supply those values to Spring at startup.
- `StatusCommand` already renders `embeddingProvider` and `embeddingModel` from the resolved config; no rendering change is needed there — the improvement comes solely from `init` writing those values into the local config file so they are correctly resolved.

---

## Technical Annex
> Written against codebase as of: 2026-06-13

### Architectural Decisions

#### 1. New `ConfigFileWriter`

A new class `ch.obermuhlner.ezrag.config.ConfigFileWriter` that writes a YAML config file. Signature:

```kotlin
class ConfigFileWriter {
    fun write(path: Path, values: Map<String, Any>)
}
```

It should use SnakeYAML (already on the classpath via `ConfigFileReader`) to serialise the map and write atomically (write to a temp file, then rename). The key names should use camelCase to match `ConfigFileReader`'s primary key lookup order (`embeddingProvider`, `embeddingModel`, `ollamaUrl`, `chunkSize`, `chunkOverlap`).

#### 2. `InitCommand` additions

Add the following `@Option` fields to `InitCommand`:

```kotlin
@Option(names = ["--embedding-provider"], description = ["Embedding provider: openai, ollama, onnx."])
private var embeddingProvider: String? = null

@Option(names = ["--embedding-model"], description = ["Embedding model name override."])
private var embeddingModel: String? = null

@Option(names = ["--chunk-size"], description = ["Default chunk size in tokens."])
private var chunkSize: Int? = null

@Option(names = ["--chunk-overlap"], description = ["Default chunk overlap in tokens."])
private var chunkOverlap: Int? = null

@Option(names = ["--ollama-url"], description = ["Ollama base URL."])
private var ollamaUrl: String? = null
```

When any of these are provided, `InitCommand.call()` writes the non-null values to `<ezRagDir>/config.yml` via `ConfigFileWriter`. If the file already exists, merge: read the existing YAML map, overlay the new values, write back.

#### 3. `EzRagCommand` — remove two options

Delete the `--embedding-provider` and `--embedding-model` `@Option` fields (lines 66–73 of `EzRagCommand.kt`). Remove `embeddingProvider` and `embeddingModel` from the `EzRagCommand` class entirely.

#### 4. `preParseProviderFlags` — read embedding config from YAML

Remove `"--embedding-provider" to "ez.rag.embeddingProvider"` and `"--embedding-model" to "ez.rag.embeddingModel"` from the `flags` map in `preParseProviderFlags`.

After scanning CLI args, determine the store directory:
1. If `--store-dir` was found in args, use that path.
2. Otherwise, walk from `Paths.get("").toAbsolutePath()` upward looking for a `.ez-rag/` directory (replicating the essential logic of `EzRagDirResolver`).

Then attempt to read `<storeDir>/config.yml`. If found and it contains `embeddingProvider` or `embeddingModel`, add those to the result map as `"ez.rag.embeddingProvider"` and `"ez.rag.embeddingModel"`. This mirrors how the CLI flags were previously injected into Spring.

#### 5. `SearchCommand` — remove `--min-score`

Delete the `--min-score` `@Option` field and all references to `minScore` / `effectiveMinScore` in `SearchCommand`. Replace all usages with the literal `0.0` where `SearchQuery` requires a value, or update `SearchQuery` to make `minScore` optional with a default of `0.0`.

#### 6. `IngestCommand` — remove `--details`

Delete the `--details` `@Option` field. Replace `val isVerbose = verbose || detailsOption` with `val isVerbose = verbose` (the `--verbose` parent flag already covers this path).

#### 7. `QueryCommand` — rename `--output`

Rename `@Option(names = ["--output"])` to `@Option(names = ["--output-format"])`. Update `outputFormat` field declaration accordingly. No logic change required.

#### 8. `ShellCommand` — rename `--output`

Same rename as `QueryCommand`: `@Option(names = ["--output"])` → `@Option(names = ["--output-format"])`.

### Automated Testing Decisions

**What makes a good test here:** test the externally observable behaviour (config file content after `init`, CLI exit codes, option acceptance/rejection, output content). Do not assert on internal field values or Spring bean wiring.

**Prior art:** existing command tests in `src/test/kotlin/ch/obermuhlner/ezrag/command/` instantiate command classes directly with constructor-injected dependencies, bypassing Spring. Follow this pattern.

| Module | Test type | What to test |
|--------|-----------|-------------|
| `ConfigFileWriter` | Unit | Round-trip: write a map, read it back with `ConfigFileReader.readConfigRaw`, assert values match. Verify existing keys not in the written map are preserved on a merge-write. |
| `InitCommand` | Unit | After calling with `--embedding-provider=openai`, the config file at the expected path contains `embeddingProvider: openai`. After re-running with a different value, the file is updated. Running without any new flags leaves any existing config unchanged. |
| `preParseProviderFlags` | Unit | When a local config with `embeddingProvider: openai` exists and no CLI flag overrides it, the returned map contains `"ez.rag.embeddingProvider" = "openai"`. Extend existing `preParseProviderFlags` tests in `EzRagApplicationTest` (if present) or create new ones. |
| `SearchCommand` | Unit | Verify that passing `--min-score` causes a picocli `UnmatchedArgumentException` (option no longer exists). Verify existing search tests still pass without the flag. |
| `IngestCommand` | Unit | Verify that passing `--details` causes a picocli `UnmatchedArgumentException`. Verify `--verbose` still produces chunk detail output. |
| `QueryCommand` | Unit | Verify `--output-format=json` produces JSON output. Verify `--output=json` causes an `UnmatchedArgumentException`. |
| `ShellCommand` | Unit | Same rename verification as `QueryCommand`. |
