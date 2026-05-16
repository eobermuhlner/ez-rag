# Tasks: Store Directory Discovery & `init` Subcommand

## Task [01-store-dir-rename]

Rename the `storePath` concept to `storeDir` across the entire codebase. The config value now represents a **directory** (`.ez-rag`) rather than a file (`.ez-rag/vector-store.json`); each command derives the store file path by appending `vector-store.json` to the resolved directory. This is a breaking change to the CLI flag name (`--store` → `--store-dir`), the config YAML key (`storePath`/`store-path` → `storeDir`/`store-dir`), and the environment variable (`STORE_PATH` → `STORE_DIR`). The end-to-end user behavior (finding the store in the current working directory) is otherwise unchanged.

### Implementation steps

- [x] Rename `storePath` → `storeDir` in `EzRagConfig`, updating the default value from `.ez-rag/vector-store.json` to `.ez-rag`
- [x] Rename `storePath` → `storeDir` in `CliFlags`
- [x] Update `ConfigFileReader` to parse YAML keys `storeDir` and `store-dir` (dropping `storePath`/`store-path`)
- [x] Update `ConfigService` to resolve `storeDir` from `cliFlags.storeDir`, `STORE_DIR` env var, and `file.storeDir`
- [x] Update `--store` CLI option to `--store-dir` in `IngestCommand`, `SearchCommand`, `QueryCommand`, `StatusCommand`, and `McpServerCommand`
- [x] Update each command to derive the store file path as `<storeDir>/vector-store.json`
- [x] Rename constructor injection parameter from `storePathOverride` to `storeDirOverride` in all commands; update all test usages to pass a directory path
- [x] Update `ConfigServiceTest` to assert `storeDir` field and use new YAML/env-var keys

### Acceptance criteria

- [x] `ConfigService` resolves `storeDir` correctly from YAML key `storeDir`
- [x] `ConfigService` resolves `storeDir` correctly from YAML key `store-dir`
- [x] `ConfigService` resolves `storeDir` correctly from env var `STORE_DIR`
- [x] When nothing is configured, `storeDir` defaults to `.ez-rag`
- [x] `--store-dir /tmp/mystore` makes `ingest` write the vector store to `/tmp/mystore/vector-store.json`
- [x] All existing command tests pass after the rename (same end-to-end behavior, different field names)

### Quality gates

- [x] `./gradlew build` compiles without errors
- [x] `grep -r "storePath\|STORE_PATH\|store-path\|\"--store\"" src/` returns no results

---

## Task [02-parent-dir-walk]

A new `EzRagDirResolver` (plain Kotlin class, no Spring) walks from a given start directory up to the filesystem root looking for a `.ez-rag/` subdirectory, falling back to `.ez-rag/` in the start directory when none is found. All five commands (`ingest`, `search`, `query`, `status`, `mcp-server`) use it as the final fallback when no explicit `--store-dir`, `STORE_DIR` env var, or `storeDir` config entry is provided.

Resolution priority (highest to lowest):
1. `--store-dir` CLI flag — skips walk
2. `STORE_DIR` env var — skips walk
3. `storeDir` config file entry — skips walk
4. Parent directory walk via `EzRagDirResolver` from CWD
5. `.ez-rag/` in CWD (built into the resolver as the fallback)

*Depends on Task 01.*

### Implementation steps

- [x] Implement `EzRagDirResolver` as a plain Kotlin class; write unit tests first (TDD)
- [x] Wire `EzRagDirResolver` into `IngestCommand` as the default store dir resolution
- [x] Wire `EzRagDirResolver` into `SearchCommand`, `QueryCommand`, `StatusCommand`, `McpServerCommand`
- [x] Add integration tests for `IngestCommand` and `StatusCommand` invoked from a subdirectory of the project root
- [x] Verify that `--store-dir` bypasses the walk in `SearchCommand` test

### Acceptance criteria

- [x] `EzRagDirResolver` returns the `.ez-rag/` path when it exists in the start directory
- [x] `EzRagDirResolver` returns the `.ez-rag/` path when it exists in a parent (not the start dir)
- [x] `EzRagDirResolver` returns the `.ez-rag/` path when it exists in a grandparent
- [x] `EzRagDirResolver` returns `<startDir>/.ez-rag/` as fallback when no `.ez-rag/` exists anywhere up to the filesystem root
- [x] `EzRagDirResolver` does not treat a `.ez-rag` *file* (not a directory) as a valid store directory
- [x] `EzRagDirResolver` terminates without error when the start directory is the filesystem root itself
- [x] `StatusCommand` invoked with a start directory that is a subdirectory of the actual `.ez-rag/` parent finds and uses the correct store
- [x] `IngestCommand` invoked with a start directory that is a subdirectory of the actual `.ez-rag/` parent writes to the correct store
- [x] Setting `STORE_DIR` env var bypasses the parent directory walk in any command
- [x] Setting `storeDir` in a config file bypasses the parent directory walk in any command
- [x] An explicit `--store-dir` flag bypasses the parent directory walk in any command

### Quality gates

- [x] `./gradlew test` passes (all existing and new tests)
- [x] `EzRagDirResolver` source contains no Spring annotations (`@Component`, `@Service`, `@Bean`, etc.)

---

## Task [03-init-subcommand]

A new `init` subcommand creates `.ez-rag/` in the current working directory and updates `.gitignore`. `GitIgnoreUpdater` is generalized so its file-write logic is reused for both the `credentials.yml` entry (existing flow) and the new `vector-store.json` entry (`init`). `InitCommand` is registered on `EzRagCommand`.

*Does not depend on Task 02 — `init` always operates on CWD.*

### Implementation steps

- [x] Generalize `GitIgnoreUpdater` to accept an arbitrary entry string; write new unit tests first (TDD)
- [x] Update existing `GitIgnoreUpdater` usage (credentials flow) to use the generalized API
- [x] Implement `InitCommand`: create `.ez-rag/`, call `GitIgnoreUpdater` for `vector-store.json`, print result message, exit 0; write unit tests first
- [x] Register `InitCommand` in `EzRagCommand`'s `subcommands` array

### Acceptance criteria

- [x] `ez-rag init --help` exits with code 0 (confirms subcommand is registered)
- [x] `ez-rag init` creates `.ez-rag/` directory in CWD when it does not exist
- [x] `ez-rag init` prints a message containing the absolute path of the created directory and exits with code 0
- [x] `ez-rag init` exits with code 0 and prints a message containing the absolute path when `.ez-rag/` already exists
- [x] `ez-rag init` does not delete or modify existing contents of `.ez-rag/` on re-run
- [x] `ez-rag init` adds `.ez-rag/vector-store.json` to `.gitignore` when `.gitignore` exists in CWD
- [x] `ez-rag init` exits with code 0 when `.gitignore` does not exist in CWD
- [x] After `ez-rag init` runs in a project that already has `.ez-rag/credentials.yml` in `.gitignore`, both `credentials.yml` and `vector-store.json` entries are present (and not duplicated)
- [x] `GitIgnoreUpdater` does not add a duplicate entry for `vector-store.json` when it is already present

### Quality gates

- [x] `./gradlew test` passes (all existing and new tests)
- [x] `./gradlew build` compiles without errors
