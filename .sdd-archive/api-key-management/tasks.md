# Tasks: API Key Management via Credentials File

## Task 01-home-credentials-file-to-provider

When a user creates `~/.ez-rag/credentials.yml` containing `openai-api-key` or `anthropic-api-key`, ez-rag reads the key and passes it to the selected API client. Environment variables still take precedence over the file. This slice establishes the `Credentials`/`CredentialSource` data model, the `CredentialsFileReader`, and the `CredentialsService` (handling home-file and env-var sources only), then wires `CredentialsService` into `ProviderConfiguration` replacing direct `System.getenv` calls.

### Implementation steps

- [x] Add `Credentials` data class and `CredentialSource` sealed class (`EnvVar(name)`, `File(path)`, `Unset`) in the config package
- [x] Add `CredentialsFileReader` that reads YAML from a given path, supports both kebab-case (`openai-api-key`) and camelCase (`openaiApiKey`) key names, warns on insecure file permissions via an injected `PrintWriter` (non-POSIX filesystems: silently skip), and returns `null` when the file is absent
- [x] Add `CredentialsService` accepting an injected env map and a home-file reader lambda; resolve with priority env-var > home-file; populate `CredentialSource` for each key
- [x] Wire `CredentialsService` as a Spring bean injected into `ProviderConfiguration`; replace both `System.getenv("OPENAI_API_KEY")` and `System.getenv("ANTHROPIC_API_KEY")` calls

### Acceptance criteria

- [x] `CredentialsFileReader` returns `null` for a non-existent path
- [x] `CredentialsFileReader` parses `openai-api-key` (kebab-case) correctly from a temp YAML file
- [x] `CredentialsFileReader` parses `openaiApiKey` (camelCase) as a fallback
- [x] `CredentialsFileReader` emits a permission warning to the provided writer when the file is group- or world-readable
- [x] `CredentialsFileReader` emits no warning when file permissions are `0600` or stricter
- [x] `CredentialsService` resolves from env var and records `CredentialSource.EnvVar` with the correct variable name
- [x] `CredentialsService` resolves from home file when no env var is set and records `CredentialSource.File` with the home file path
- [x] `CredentialsService` records `CredentialSource.Unset` when neither env var nor home file provides a key
- [x] `CredentialsService` env-var beats home-file when both provide a value
- [x] `ProviderConfiguration` reads the OpenAI and Anthropic API keys from `CredentialsService` instead of `System.getenv` directly

### Quality gates

- [x] No compiler warnings (`./gradlew build` produces no warnings)
- [x] All existing tests pass (`./gradlew test`)
- [x] No direct `System.getenv("OPENAI_API_KEY")` or `System.getenv("ANTHROPIC_API_KEY")` calls remain in `ProviderConfiguration`

---

## Task 02-project-local-credentials-with-gitignore

When a user creates `.ez-rag/credentials.yml` in the project directory, its keys take precedence over the home credentials file. The first time ez-rag reads the project-local credentials file, it appends `.ez-rag/credentials.yml` to the project's `.gitignore` (if the file exists and the entry is not already there) and prints a one-line notice to the user. `GitIgnoreUpdater` is called from the Spring wiring site, keeping `CredentialsService` free of git concerns. `CredentialSource.File(path)` carries the full path so home-file and project-local sources are distinguishable.

### Implementation steps

- [x] Extend `CredentialsService` to accept a project-local reader lambda (in addition to the home reader); resolve priority order: env-var > project-local > home; `CredentialSource.File(path)` carries the absolute path of whichever file won
- [x] Add `GitIgnoreUpdater` that checks `.gitignore` in a given directory for the credentials entry and appends it with a one-line notice if absent; does nothing (no error, no modification) if `.gitignore` does not exist; never duplicates the entry
- [x] Call `GitIgnoreUpdater` from the Spring bean factory method (or `EzRagConfiguration`) after successfully reading the project-local credentials file — not from inside `CredentialsService`
- [x] Wire the project-local reader into the Spring bean for `CredentialsService`

### Acceptance criteria

- [x] `GitIgnoreUpdater` appends `.ez-rag/credentials.yml` when the entry is absent from `.gitignore`
- [x] `GitIgnoreUpdater` prints a notice line to the injected writer when it adds the entry
- [x] `GitIgnoreUpdater` does not duplicate the entry when it is already present
- [x] `GitIgnoreUpdater` does nothing (no error, no modification) when `.gitignore` does not exist
- [x] `CredentialsService` project-local key beats home-file key when both are set, and `CredentialSource.File` carries the project-local path (not the home path)
- [x] `CredentialsService` falls back to home file when no project-local file exists, and `CredentialSource.File` carries the home path (not the project-local path)
- [x] `GitIgnoreUpdater` is not called when the project-local credentials file is absent

### Quality gates

- [x] No compiler warnings (`./gradlew build` produces no warnings)
- [x] All existing tests pass (`./gradlew test`)
- [x] All `GitIgnoreUpdater` tests use a `@TempDir` directory so the real project `.gitignore` is never modified during the test suite

---

## Task 03-status-command-credentials-section

`ez-rag status` displays a credentials section showing where each API key is sourced from — env var name, file path, or "not set" — without printing the actual key value. Both text and JSON output formats include this section. `StatusCommand` receives a `Credentials` snapshot via its constructor (consistent with its existing pattern of injecting `PrintWriter` and `Path` for testability) rather than a live `CredentialsService`.

*Depends on Task 02 for `CredentialSource.File(path)` carrying a usable path string.*

### Implementation steps

- [x] Add a `credentials: Credentials` constructor parameter to `StatusCommand`; provide a default value (all `Unset`) so existing Spring wiring still compiles
- [x] Wire the `Credentials` snapshot into `StatusCommand` from the Spring bean factory
- [x] Add text output: print a `Credentials:` section with one line per key showing `set (env var <NAME>)`, `set (<path>)`, or `not set`
- [x] Add JSON output: include a `credentials` object with `openaiApiKey` and `anthropicApiKey` source-string fields
- [x] Extend `StatusCommandTest` with tests for the credentials section in both formats, verifying source strings and absence of actual key values

### Acceptance criteria

- [x] Text output contains `openai-api-key:` with source `set (env var OPENAI_API_KEY)` when sourced from env
- [x] Text output contains `openai-api-key:` with source `set (<file path>)` when sourced from a credentials file; the path in the output matches the file path from `CredentialSource.File`
- [x] Text output contains `openai-api-key: not set` when key is absent
- [x] Text output contains `anthropic-api-key:` with mirrored source strings under the same three conditions
- [x] Text output never contains the literal API key value
- [x] JSON output contains a `credentials` object with `openaiApiKey` and `anthropicApiKey` fields showing source strings
- [x] JSON output never contains the literal API key value

### Quality gates

- [x] No compiler warnings (`./gradlew build` produces no warnings)
- [x] All existing tests pass (`./gradlew test`)

---

## Task 04-missing-key-actionable-error

When the selected provider requires an API key and none is set, ez-rag throws with an error message that names both the environment variable and the credentials file path so the user knows exactly how to fix it. This replaces the current behavior of silently passing an empty string to the API client.

### Implementation steps

- [x] In `ProviderConfiguration`, check `CredentialSource` before building the API client; when the source is `Unset`, throw `IllegalStateException` with a message naming the env var (e.g., `OPENAI_API_KEY`) and both possible credentials file paths (`.ez-rag/credentials.yml` and `~/.ez-rag/credentials.yml`)
- [x] Add tests in `ProviderConfigurationTest` verifying the error message content for a missing OpenAI key and a missing Anthropic key

### Acceptance criteria

- [x] When OpenAI provider is selected and key source is `Unset`, the thrown exception message contains `OPENAI_API_KEY`
- [x] When OpenAI provider is selected and key source is `Unset`, the thrown exception message contains `.ez-rag/credentials.yml`
- [x] When Anthropic provider is selected and key source is `Unset`, the thrown exception message contains `ANTHROPIC_API_KEY`
- [x] When Anthropic provider is selected and key source is `Unset`, the thrown exception message contains `.ez-rag/credentials.yml`
- [x] No exception is thrown when the key is set from any source (env var or file)
- [x] Ollama provider does not require an API key and does not throw when both keys are `Unset`

### Quality gates

- [x] No compiler warnings (`./gradlew build` produces no warnings)
- [x] All existing tests pass (`./gradlew test`)
