# PRD: Local `.ez-rag/config.yml` Support

## Problem Statement

A developer using ez-rag across multiple projects must currently rely on a single global configuration at `~/.ez-rag/config.yml`. There is no way to set project-specific defaults (e.g., a different `chunkSize`, `provider`, or `searchMode`) without passing CLI flags every time or permanently overriding the global config. This friction makes it harder to adopt different RAG configurations per project.

## Solution

Add support for a project-local `config.yml` inside the `.ez-rag/` workspace directory. When both the home config (`~/.ez-rag/config.yml`) and a local config (`.ez-rag/config.yml`) are present, they are merged field-by-field: each field set in the local config overrides the corresponding field from the home config, while fields absent from the local config fall back to the home config value. The `status` command reports which config files were loaded.

## User Stories

1. As a developer, I want to place a `config.yml` in my project's `.ez-rag/` directory, so that project-specific settings are applied automatically without CLI flags.
2. As a developer, I want my project-local config to override only the fields I specify, so that I can keep global defaults for everything else without duplicating the full config.
3. As a developer, I want settings from `~/.ez-rag/config.yml` to serve as the base, so that I can maintain a single source of global defaults across all projects.
4. As a developer working in a subdirectory of my project, I want the local config to be found automatically by walking parent directories, so that it behaves consistently with how the `.ez-rag/` workspace directory is resolved everywhere else.
5. As a developer, I want `ez-rag status` to show which config files were loaded (home and/or local), so that I can quickly verify which configuration is active without guessing.
6. As a developer, I want CLI flags and environment variables to still override both config files, so that CI pipelines and one-off overrides continue to work as expected.
7. As a developer, I want the local config to use the `.yml` extension, so that it is consistent with the existing home config convention.
8. As a developer, I want the local config to NOT affect `storeDir`, so that there is no circular dependency between finding the workspace directory and reading its config.
9. As a developer, I want the local config to be absent by default after `ez-rag init`, so that I am not forced to maintain a config file I don't need.
10. As a developer, I want the priority order to be CLI flags > startup flags > env vars > local config > home config > defaults, so that the escape hatches I am already familiar with continue to work.
11. As a developer, I want the local config file to be writable/readable without special permissions (unlike credentials), so that it can be safely committed to source control as project-level defaults.

## Implementation Decisions

### Module: Config File Reading

The existing `readConfigFile(path)` function reads a YAML file and returns an `EzRagConfig` with defaults baked in. For field-level merging, we need to distinguish "field was present in the file" from "field was absent (defaulted)". The cleanest approach is to introduce a low-level `readConfigRaw(path): Map<String, Any>?` function that returns the raw YAML key-value map without applying defaults. The merge of home and local raw maps happens before `EzRagConfig` is constructed:

```
mergedRaw = homeRaw + localRaw   // local keys win on conflict
EzRagConfig constructed from mergedRaw
```

This keeps the merge logic in one place and avoids introducing a new partially-nullable config type.

### Module: ConfigService

`ConfigService` already accepts a `configFileSource: () -> EzRagConfig?` lambda. Its interface does **not** change. The merging of home + local config happens in the lambda supplied at construction time (inside `EzRagConfiguration.kt`), not inside `ConfigService` itself. This keeps `ConfigService` unaware of how many files exist.

The `storeDir` field is excluded from the local config's influence: `resolveExplicitStoreDir()` continues to read only from CLI flags, startup flags, environment variables, and the home config.

### Module: EzRagConfiguration (Spring wiring)

The `configService()` bean is updated to:
1. Resolve the local `.ez-rag/` directory using `EzRagDirResolver` (same algorithm as the rest of the application).
2. Read the home config raw map (`~/.ez-rag/config.yml`).
3. Read the local config raw map (`<resolved-ez-rag-dir>/config.yml`).
4. Merge the two raw maps (local overrides home).
5. Construct a single `EzRagConfig` from the merged map.
6. Pass this as the `configFileSource` lambda to `ConfigService`.

The bean also tracks which config file paths were actually loaded and exposes them (e.g., via a `ConfigSources` data class or simple pair) so that `StatusCommand` can display them.

### Module: StatusCommand

A new "Config files" section is added to both text and JSON output:
- **Text**: lists the path of each loaded config file with a label (`home config`, `local config`), or `none` if neither was found.
- **JSON**: adds a `configSources` array of file path strings.

The `StatusCommand` receives the loaded config file paths as constructor parameters (consistent with how `config` and `credentials` are already injected).

### Priority Chain (unchanged contract, extended source)

```
CLI flags
  > startup pre-parsed flags
  > environment variables
  > local config (.ez-rag/config.yml)   ← NEW
  > home config (~/.ez-rag/config.yml)
  > hard-coded defaults
```

`storeDir` is resolved only from: CLI flags > startup flags > env vars > home config (local config is excluded).

## Testing Decisions

### What makes a good test

Tests assert externally observable behavior (the resolved `EzRagConfig` field values, the text/JSON output of `StatusCommand`) and do not assert which private helper was called or how many times files were opened. Each test sets up its inputs (YAML file content, env vars) and asserts the output. Tests use `@TempDir` for isolated file system state.

### Modules to test

**`ConfigFileReaderTest`** (extend existing):
- `readConfigRaw` returns `null` when file does not exist.
- `readConfigRaw` returns the raw map when the file exists.
- Field-level merge: a field present only in home raw map is used.
- Field-level merge: a field present only in local raw map is used.
- Field-level merge: a field present in both uses the local value.
- Merging two empty maps produces a `null`/default config.

**`ConfigServiceTest`** (extend existing):
- Local config field beats home config field.
- Home config field is used when local config does not set it.
- Env var beats local config field.
- CLI flag beats local config field.
- `storeDir` from local config is ignored (only home config and env var are used).

**`StatusCommandTest`** (extend existing):
- Text output lists the home config path when only home config is loaded.
- Text output lists the local config path when only local config is loaded.
- Text output lists both paths when both configs are loaded.
- Text output shows a "none" indicator when neither config file is present.
- JSON output includes a `configSources` field with the loaded paths.

Prior art for tests:
- `ConfigFileReaderTest` — file-based YAML tests using `@TempDir`.
- `ConfigServiceTest` — unit tests of priority/resolution logic with lambdas.
- `StatusCommandTest` — direct instantiation of `StatusCommand` with injected dependencies and `StringWriter` capture.
- `CredentialsServiceTest` — two-file resolution with `projectLocalFileReader` / `homeFileReader` lambdas (structural template for two-source config loading).

## Out of Scope

- Supporting `.yaml` extension (the existing convention is `.yml`).
- Creating a template `config.yml` during `ez-rag init`.
- Allowing local config to override `storeDir`.
- File permission checking on `config.yml` (it contains no secrets and may be committed to source control).
- Supporting more than two tiers of config files (e.g., per-user-directory configs).
- Migrating existing home configs from `.yml` to another format.

## Further Notes

- The credentials system already implements a structurally similar two-tier pattern (`CredentialsService` with `projectLocalFileReader` + `homeFileReader` lambdas). The implementation here mirrors that pattern at the raw-map level rather than at the service level.
- Since `EzRagDirResolver` stops before the filesystem root, the local config search is safe and will not accidentally pick up an `.ez-rag/` directory in `/`, `/home`, or `/tmp`.
- The local config file may be safely committed to source control (it contains no credentials). The `.gitignore` auto-update in `GitIgnoreUpdater` does not need to be extended.
