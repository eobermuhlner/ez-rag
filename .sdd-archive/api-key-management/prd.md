# PRD: API Key Management via Credentials File

## Problem Statement

Managing API keys exclusively through environment variables is inconvenient for local development. Users must set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` in every new shell session or globally in their shell profile, which mixes secrets with general shell configuration. There is no clear location to store API keys specific to a project that requires a particular provider, and no guidance in the tool itself when a key is missing.

## Solution

Introduce a dedicated `credentials.yml` file (YAML format, matching the existing `config.yml` format) that stores API keys. The file can live in the user's home directory (`~/.ez-rag/credentials.yml`) as a personal default, or in the project directory (`.ez-rag/credentials.yml`) for project-scoped overrides. Environment variables continue to work and take precedence, preserving compatibility with CI/CD pipelines. The `status` command gains a credentials section showing where each key is loaded from without revealing the key value.

## User Stories

1. As a developer, I want to store my OpenAI API key in `~/.ez-rag/credentials.yml` so that I don't have to export it in every new terminal session.
2. As a developer, I want to store my Anthropic API key in `~/.ez-rag/credentials.yml` so that I can switch between providers without managing multiple environment variables.
3. As a developer working on a project pinned to a specific provider, I want to store the matching API key in `.ez-rag/credentials.yml` inside the project directory so that the correct key is used automatically when I work in that directory.
4. As a developer, I want the project-local credentials file to override my home credentials file so that project-specific keys take precedence without modifying my global setup.
5. As a developer, I want environment variables to continue overriding credentials files so that CI/CD pipelines and container deployments work without file-based configuration.
6. As a developer, I want ez-rag to automatically add `.ez-rag/credentials.yml` to my project's `.gitignore` so that I cannot accidentally commit API keys to version control.
7. As a developer, I want to be notified the first time ez-rag adds an entry to `.gitignore` so that the change is not invisible.
8. As a developer, I want ez-rag to warn me if my credentials file has world- or group-readable permissions so that I am aware of a potential security risk.
9. As a developer, I want the warning about insecure file permissions to include the exact `chmod` command to fix it so that I can act immediately.
10. As a developer, I want ez-rag to continue running even when the credentials file has insecure permissions so that I am not blocked in non-production environments.
11. As a developer, I want to see a clear, actionable error message when a required API key is missing so that I know exactly how to fix it.
12. As a developer, I want the error message to show both the environment variable name and the credentials file path so that I can choose the approach that fits my workflow.
13. As a developer, I want `ez-rag status` to show where each API key is loaded from (env var name, file path, or not set) so that I can diagnose misconfiguration easily.
14. As a developer, I want `ez-rag status` to never print the actual key value so that it is safe to share status output in bug reports or screenshots.
15. As a developer, I want credentials files to use the same YAML format as `config.yml` so that the two files feel consistent and I only need to know one format.
16. As a developer, I want key names in `credentials.yml` to use kebab-case (`openai-api-key`, `anthropic-api-key`) consistent with the convention in `config.yml` so that the format is predictable.
17. As a developer, I want the home credentials file (`~/.ez-rag/credentials.yml`) to be read on every invocation so that changes take effect immediately without restarting any daemon.
18. As a developer, I want to manually create and edit `credentials.yml` without any CLI wizard so that I stay in full control of my secrets.

## Implementation Decisions

### New module: `CredentialsFileReader`

A pure function (or single-method object) that accepts a file path, returns a nullable `Credentials` data class, and:
- Returns `null` if the file does not exist.
- Reads the file using SnakeYAML (already a dependency).
- Accepts both `openai-api-key` (kebab) and `openaiApiKey` (camelCase) keys for forward compatibility.
- Checks POSIX file permissions and emits a warning to a provided `PrintWriter` if the file is readable by group or others (i.e., permissions are not `0600` or stricter). Warning but does not throw.
- Permission checking is skipped on non-POSIX filesystems (Windows) without error.

### New data class: `Credentials`

Holds the resolved API key strings and, for each key, a `CredentialSource` enum (env var name, file path, or unset). This allows `StatusCommand` to display provenance without coupling it to resolution logic.

```
data class Credentials(
    val openaiApiKey: String,
    val openaiApiKeySource: CredentialSource,
    val anthropicApiKey: String,
    val anthropicApiKeySource: CredentialSource,
)

sealed class CredentialSource {
    data class EnvVar(val name: String) : CredentialSource()
    data class File(val path: String) : CredentialSource()
    object Unset : CredentialSource()
}
```

### New module: `CredentialsService`

Resolves credentials according to the priority chain:

```
env vars  (OPENAI_API_KEY, ANTHROPIC_API_KEY)
  ↓
project-local  (.ez-rag/credentials.yml relative to working directory)
  ↓
home           (~/.ez-rag/credentials.yml)
  ↓
unset
```

Accepts injected sources (env map, project-local reader, home reader) so it is fully testable without filesystem or environment side effects. Returns a `Credentials` instance with source annotations.

### New module: `GitIgnoreUpdater`

Checks whether `.gitignore` in the current working directory already contains an entry for `.ez-rag/credentials.yml`. If not, appends the entry. Prints a single notice line to the provided output writer when an entry is added. Does nothing if `.gitignore` does not exist (no file is created).

`GitIgnoreUpdater` is called only when a project-local credentials file is successfully read.

### Modified module: `ProviderConfiguration`

Replace direct `System.getenv("OPENAI_API_KEY")` and `System.getenv("ANTHROPIC_API_KEY")` calls with reads from `CredentialsService`. The `CredentialsService` bean is injected into `ProviderConfiguration`.

### Modified module: `StatusCommand`

Add a credentials section to the status output. For each key, display its source:
- `set (env var OPENAI_API_KEY)` — if loaded from environment
- `set (~/.ez-rag/credentials.yml)` — if loaded from home file
- `set (.ez-rag/credentials.yml)` — if loaded from project-local file
- `not set` — if absent everywhere

In JSON output format, add a `credentials` object with the same source information.

### No changes to `EzRagConfig` or `ConfigService`

API keys are secrets, not configuration. They are intentionally kept out of the `EzRagConfig` data class and `ConfigService` priority chain, which handles provider/model/path settings. This keeps the two concerns separate.

### No CLI command for writing credentials

Accepting secrets as CLI arguments risks leaking them into shell history. Users create and edit `credentials.yml` manually.

## Testing Decisions

**What makes a good test:** tests verify observable behavior through the public interface only — return values, emitted output, and side effects on injected fakes. Tests never inspect internal state or assert on implementation classes.

**Modules to test:**

- **`CredentialsFileReader`**: write tests using temporary files (JUnit 5 `@TempDir`). Verify: returns `null` for missing file, parses `openai-api-key` and `anthropic-api-key` in kebab-case, parses camelCase variants, emits permission warning when file is group- or world-readable (using a mock/fake `PrintWriter`), emits no warning for `0600` permissions.

- **`CredentialsService`**: inject fake readers and a fake env map. Verify: env var beats project-local file, env var beats home file, project-local beats home file, home file used when no project-local file exists, all-unset returns `Unset` sources, source annotations reflect the winning source.

- **`GitIgnoreUpdater`**: use `@TempDir`. Verify: entry appended when missing, notice printed, entry not duplicated when already present, does nothing when `.gitignore` absent.

- **`StatusCommand`**: extend existing tests. Verify: credentials section appears in text output, credential values are never printed, source strings match expected format, JSON output includes `credentials` key.

**Prior art:** `ConfigServiceTest.kt` is the direct precedent — it injects a fake env map and a lambda for the config file source and asserts on the resolved `EzRagConfig`. `CredentialsService` tests follow the same pattern.

## Out of Scope

- A CLI subcommand to write or rotate credentials (`ez-rag config set-key`).
- Support for additional credential types beyond `openai-api-key` and `anthropic-api-key` (e.g., `ollama-url` is not a secret and stays in `config.yml`).
- Encrypted credentials storage.
- Automatic creation of a template `credentials.yml` on first run.
- Project-level `.ez-rag/config.yml` gitignore management (only `credentials.yml` is auto-added).
- Support for credential profiles or named environments.

## Further Notes

- The `~/.ez-rag/` directory is already used for `config.yml` and the ONNX model cache, so the home credentials file fits naturally into the existing layout.
- POSIX permission checking can use `java.nio.file.Files.getPosixFilePermissions`. On Windows, this throws `UnsupportedOperationException`, which should be caught and silently ignored.
- The `credentials.yml` filename (with extension) was chosen over the bare `credentials` name to make the format self-documenting and to enable IDE syntax highlighting.
