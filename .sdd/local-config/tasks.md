# Tasks: Local `.ez-rag/config.yml` Support

## Task [01-two-tier-config-merge]

Enable project-local config overrides. A developer places `config.yml` inside the `.ez-rag/` workspace directory; the application reads both `~/.ez-rag/config.yml` (home) and `<ez-rag-dir>/config.yml` (local), merges them field-by-field (local fields win), and produces a single resolved `EzRagConfig`. The workspace directory is located via the same parent-directory walk algorithm used everywhere else (`EzRagDirResolver.resolve(cwd)`). `ConfigService`'s interface is unchanged — merging happens in the lambda passed to its constructor. `storeDir` is excluded from the local config's influence to avoid a circular dependency (the workspace dir is already known at the point config is read). The bean also creates a `ConfigSources` data class (home path, local path, both nullable) and registers it as a Spring bean so downstream components (e.g., `StatusCommand`) can display which files were loaded.

### Implementation steps

- [x] Write failing tests for `readConfigRaw(path)`: returns `null` when the file is missing, returns the raw key-value map when the file exists
- [x] Implement `readConfigRaw(path: String): Map<String, Any>?` in `ConfigFileReader.kt`
- [x] Write failing `ConfigFileReaderTest` cases for the merge helper: local field overrides home field; home field used when local omits it; `storeDir` key from local raw map is stripped before merge; both maps empty yields no override
- [x] Implement `mergeConfigRaw(home: Map<String, Any>?, local: Map<String, Any>?): EzRagConfig?` — strips `storeDir`/`store-dir` from local map, then applies `home + (local - storeDir)`, returns `null` only when both inputs are null
- [x] Write failing `ConfigServiceTest` cases: local config field beats home config field; home field used when local omits it; env var beats local config; CLI flag beats local config; `resolveExplicitStoreDir()` is unaffected by a `storeDir` key in the local config file
- [x] Update `EzRagConfiguration.configService()` to call `EzRagDirResolver().resolve(Paths.get("").toAbsolutePath())` to locate the workspace directory, read both raw maps, merge them, pass the merged `EzRagConfig` (or `null`) as the `configFileSource` lambda
- [x] Introduce `ConfigSources(homeConfigPath: String?, localConfigPath: String?)` data class and register it as a Spring `@Bean` in `EzRagConfiguration`, recording which files were actually found on disk
- [x] Make all tests pass

### Acceptance criteria

- [x] A field set only in `~/.ez-rag/config.yml` resolves to that value when `.ez-rag/config.yml` does not set it
- [x] A field set in `.ez-rag/config.yml` overrides the same field in `~/.ez-rag/config.yml`
- [x] A field absent from both config files resolves to the hard-coded default in `EzRagConfig()`
- [x] `storeDir` written in `.ez-rag/config.yml` does NOT change the result of `resolveExplicitStoreDir()` — its value equals what the home config or env var provides (or `null` when neither is set)
- [x] An environment variable for a field beats the local config value for that field
- [x] A CLI flag for a field beats the local config value for that field
- [x] When invoked from a subdirectory that is two levels below a directory containing `.ez-rag/config.yml`, `readConfigRaw` is called with the path found by `EzRagDirResolver` (the parent's `.ez-rag/config.yml`), and the merged config contains that file's field value

### Quality gates

- [x] No new compiler warnings introduced
- [x] All pre-existing tests continue to pass

---

## Task [02-status-config-sources]

Extend `ez-rag status` to show which config files are active. `StatusCommand` receives the `ConfigSources` bean (introduced in Task 01) and includes a new "Config files" section in both text and JSON output listing the absolute paths of loaded config files. This makes it immediately observable which combination of home config and local config is in effect.

Depends on: Task 01 (requires `ConfigSources` bean and two-tier loading to exist).

### Implementation steps

- [x] Write failing `StatusCommandTest` cases: text output contains home config path when only home config was loaded; text output contains local config path when only local config was loaded; text output contains both paths when both were loaded; text output shows a "none" indicator when neither config file was present
- [x] Write failing `StatusCommandTest` case: JSON output has a `configSources` array whose entries match exactly the paths of the config files that were loaded (empty array when neither loaded)
- [x] Add a `configSources: ConfigSources` constructor parameter to `StatusCommand` (defaulting to `ConfigSources(null, null)` for test convenience, matching the existing pattern for `config` and `credentials`)
- [x] Update `StatusCommand` text rendering to print a "Config files" section showing each non-null path labelled as home or local, or "none" when both are null
- [x] Update `StatusCommand` JSON rendering to include a `configSources` array of the non-null paths
- [x] Inject the `ConfigSources` bean into `StatusCommand` via Spring wiring
- [x] Make all tests pass

### Acceptance criteria

- [x] Text output contains the absolute path of the home config file when it was loaded
- [x] Text output contains the absolute path of the local config file when it was loaded
- [x] Text output does NOT list a config file path that was not loaded
- [x] Text output shows a "none" indicator (or equivalent) in the "Config files" section when neither config file was found
- [x] JSON output's `configSources` array contains exactly the paths of the config files that were loaded, and is an empty array (not absent) when neither was loaded

### Quality gates

- [x] No new compiler warnings introduced
- [x] All pre-existing tests continue to pass
