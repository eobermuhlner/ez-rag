# PRD: Store Directory Discovery & `init` Subcommand

## Problem Statement

When a user runs `ez-rag` from a subdirectory of their project, the tool cannot find the `.ez-rag/` store because it only looks in the current working directory. This forces users to always run `ez-rag` from the project root, which is inconvenient and inconsistent with tools like `git` that naturally discover their data directory by walking up the directory tree.

Additionally, there is no explicit way to initialize a new `.ez-rag/` workspace — users must know to create the directory manually or let `ingest` create it implicitly.

## Solution

- All commands that resolve the store directory walk parent directories from CWD until they find a `.ez-rag/` directory, stopping at the filesystem root. If none is found they fall back to `.ez-rag/` in CWD (preserving existing behavior).
- A new `init` subcommand explicitly initializes a `.ez-rag/` workspace in CWD, creating the directory and updating `.gitignore`.
- The configuration concept is updated from `storePath` (a file path) to `storeDir` (a directory path), making it more flexible for future additions inside the `.ez-rag/` directory.

## User Stories

1. As a developer, I want `ez-rag search` to find my vector store when I run it from a subdirectory of my project, so that I don't have to navigate to the project root every time.
2. As a developer, I want `ez-rag query` to find my vector store when I run it from a subdirectory of my project, so that I can query the knowledge base from anywhere in my project tree.
3. As a developer, I want `ez-rag ingest` to find the existing `.ez-rag/` directory when I run it from a subdirectory, so that ingested documents are added to the correct store.
4. As a developer, I want `ez-rag status` to find my vector store when I run it from a subdirectory, so that I can check the store status from anywhere in my project tree.
5. As a developer using the MCP server, I want `ez-rag mcp-server` to find the `.ez-rag/` directory by walking parent directories, so that the MCP server connects to the correct store.
6. As a developer, I want `ez-rag init` to create a `.ez-rag/` directory in my current directory, so that I have an explicit way to initialize a new workspace.
7. As a developer, I want `ez-rag init` to update `.gitignore` to exclude the vector store file, so that large derived files are not accidentally committed.
8. As a developer, I want `ez-rag init` to print an informational message and exit successfully when `.ez-rag/` already exists, so that re-running init is safe and non-destructive.
9. As a developer, I want the `--store-dir` flag to take precedence over the parent directory walk, so that I can explicitly override the store location when needed.
10. As a developer, I want the `STORE_DIR` environment variable to take precedence over the parent directory walk, so that CI/CD pipelines can pin the store location.
11. As a developer, I want the `storeDir` config file key to take precedence over the parent directory walk, so that project-specific config overrides the automatic discovery.
12. As a developer, I want the parent directory walk to stop at the filesystem root, so that the tool has a predictable termination condition.
13. As a developer, I want the vector store file to be derived automatically from the discovered store directory, so that I only need to know the directory location, not the internal file name.
14. As a developer, I want `ez-rag init` to succeed even when `.gitignore` does not exist, so that init works in projects without a `.gitignore`.

## Implementation Decisions

### Module: `EzRagDirResolver` (new, deep)

Encapsulates the parent directory walk in a single, testable unit. Interface:

- Input: a starting `Path` (typically CWD)
- Output: the `Path` of the found `.ez-rag/` directory, or the fallback `.ez-rag/` under the starting directory

Algorithm:
1. Check if `<current>/.ez-rag/` is a directory.
2. If yes, return it.
3. If `<current>` has no parent (filesystem root), return `<startDir>/.ez-rag/` as the fallback.
4. Otherwise, move to the parent and repeat.

This module has no Spring dependencies and no I/O side effects beyond directory existence checks — making it straightforward to test with `@TempDir`.

### Module: `InitCommand` (new)

Registered as a picocli subcommand named `init` on `EzRagCommand`. Responsibilities:

- Create `.ez-rag/` in CWD using `Files.createDirectories` (idempotent).
- Call `GitIgnoreUpdater` to add the vector store entry.
- Print `"Initialized .ez-rag/ in <absolute path>"` on success, or `".ez-rag/ already exists at <absolute path>"` if the directory was already present.
- Always exit with code 0.

### Module: `GitIgnoreUpdater` (modify)

Currently hardcodes the `credentials.yml` entry. Generalize to accept a list of entries to ensure are present. `InitCommand` passes both `credentials.yml` and `vector-store.json`; the credentials flow continues to pass only `credentials.yml`.

Alternative: keep two separate constants and add a second `ensureEntry(entry)` method. Either approach is acceptable as long as it avoids duplication of the file-write logic.

### Config rename: `storePath` → `storeDir`

| Location | Before | After |
|---|---|---|
| `EzRagConfig` field | `storePath: String = ".ez-rag/vector-store.json"` | `storeDir: String = ".ez-rag"` |
| `CliFlags` field | `storePath: String?` | `storeDir: String?` |
| `ConfigFileReader` YAML keys | `storePath`, `store-path` | `storeDir`, `store-dir` |
| `ConfigService` resolution | `cliFlags.storePath ?: … ?: file.storePath` | `cliFlags.storeDir ?: … ?: file.storeDir` |
| CLI option | `--store` (each command) | `--store-dir` (each command) |
| Environment variable | `STORE_PATH` | `STORE_DIR` |

The vector store filename `vector-store.json` is derived by each command from the resolved directory: `<storeDir>/vector-store.json`. It is not configurable.

### Store resolution priority (all commands)

1. `--store-dir` CLI flag (explicit, skips walk)
2. `STORE_DIR` environment variable (skips walk)
3. `storeDir` config file entry (skips walk)
4. Parent directory walk via `EzRagDirResolver` starting from CWD
5. Fallback: `.ez-rag/` in CWD

### Commands modified

`IngestCommand`, `SearchCommand`, `QueryCommand`, `StatusCommand`, `McpServerCommand` — each replaces its hardcoded `.ez-rag/vector-store.json` default with a call to `EzRagDirResolver` and appends `/vector-store.json` to get the store file path.

### No migration

Existing users with `storePath` in their config file will see the key ignored (the new parser reads `storeDir`/`store-dir`). Their store will be discovered via the parent walk if the `.ez-rag/` directory exists. No automatic migration is provided.

## Testing Decisions

**What makes a good test**: test observable behavior only — the path returned by the resolver, the files created by `init`, the output printed to the writer, and the exit code returned. Do not assert on private fields or internal state.

### `EzRagDirResolver` (unit tests with `@TempDir`)

- Returns `.ez-rag/` in start directory when it exists there.
- Finds `.ez-rag/` in a parent directory when not in CWD.
- Finds `.ez-rag/` in a grandparent directory.
- Falls back to `.ez-rag/` in start directory when no `.ez-rag/` exists anywhere.
- Does not mistake a `.ez-rag` *file* (not a directory) for the store directory.

Prior art: `DirectoryWalkerTest`, `GitIgnoreUpdaterTest` — both use `@TempDir` and assert on filesystem state.

### `InitCommand` (unit tests with `@TempDir`)

- Creates `.ez-rag/` when it does not exist.
- Prints success message containing the absolute path.
- Returns exit code 0 on success.
- Returns exit code 0 and prints informational message when `.ez-rag/` already exists.
- Does not delete contents of existing `.ez-rag/` on re-run.
- Adds `vector-store.json` entry to `.gitignore` when `.gitignore` exists.
- Does not fail when `.gitignore` does not exist.

Prior art: `StatusCommandTest`, `IngestCommandTest` — both inject `PrintWriter(StringWriter())` and `@TempDir` paths.

### `GitIgnoreUpdater` (unit tests with `@TempDir`)

- Existing tests remain valid.
- New tests for the vector store entry: adds `.ez-rag/vector-store.json`, does not duplicate it, prints notice.

### `EzRagDirResolver` integration via command tests

`SearchCommandTest`, `QueryCommandTest`, `StatusCommandTest`, `IngestCommandTest` — add a test case where the command is invoked with a CWD that is a subdirectory of the actual store directory, verifying the correct store is used.

Prior art: same test files as above — they already inject `storePathOverride` / constructor paths; the new tests will inject a subdirectory and expect the resolver to find the parent's `.ez-rag/`.

### Config rename tests

`ConfigServiceTest` — verify that `storeDir` / `store-dir` YAML keys are parsed correctly and that the resolved config contains the right directory path.

## Out of Scope

- Searching parent directories for `config.yml` or `credentials.yml` (only the store directory is discovered).
- Making the vector store filename (`vector-store.json`) configurable.
- Automatic migration of `storePath` → `storeDir` in existing config files.
- An `ez-rag destroy` or `ez-rag clean` subcommand.
- Any changes to how the home-directory config (`~/.ez-rag/config.yml`) or credentials are resolved.

## Further Notes

- The `.ez-rag/` directory name uses a hyphen, consistent with all existing paths in the codebase.
- `EzRagDirResolver` should be a plain Kotlin class with no Spring annotations so it can be instantiated directly in tests.
- `init` is intentionally minimal: it does not create a default `config.yml` stub. Configuration and credentials are created by existing flows when first needed.
- The `McpServerCommand` uses a `@Bean` method (`mcpToolCallbackProvider`) for store resolution; the same `EzRagDirResolver` call applies there, replacing the current hardcoded default.
