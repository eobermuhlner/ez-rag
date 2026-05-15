# Tasks: 01-project-setup

## Task 01-cli-skeleton

Scaffold the Gradle Kotlin DSL project and wire picocli into Spring Boot's `CommandLineRunner` so the tool is immediately runnable: `ez-rag --help` exits 0 with usage, and an unknown subcommand exits non-zero with a human-readable error. This is the minimal vertical slice — build pipeline through CLI dispatch through exit code.

### Implementation steps

- [x] Create `settings.gradle.kts` and `build.gradle.kts` with Kotlin plugin, Spring Boot 3.4.x Gradle plugin, Application Plugin, picocli dependency, group `ch.obermuhlner`, artifact `ez-rag`
- [x] Set `java.toolchain.languageVersion = JavaLanguageVersion.of(21)` and `-Werror` Kotlin compiler option
- [x] Create `EzRagApplication` as `@SpringBootApplication` + `CommandLineRunner` that builds a picocli `CommandLine` from the top-level `EzRagCommand` Spring bean and calls `System.exit(commandLine.execute(*args))`
- [x] Create `EzRagCommand` as a `@Command(name = "ez-rag", mixinStandardHelpOptions = true, subcommandListHeading = "Subcommands:%n")` Spring `@Component`
- [x] Register picocli `IFactory` Spring integration so picocli resolves subcommand beans via the Spring `ApplicationContext`
- [x] Write unit/integration test: construct `CommandLine(EzRagCommand())` directly (no Spring context), assert `--help` exits 0 and `unknown-subcommand` exits non-zero

### Acceptance criteria

- [x] `./gradlew build` completes without errors
- [x] `./gradlew installDist` produces `build/install/ez-rag/bin/ez-rag`
- [x] `java -jar build/libs/ez-rag-*.jar --help` exits 0 and prints usage text containing "ez-rag"
- [x] `java -jar build/libs/ez-rag-*.jar unknown-subcommand` exits non-zero and prints a human-readable error message to stderr with no stack trace
- [x] Group ID `ch.obermuhlner`, artifact ID `ez-rag`, base package `ch.obermuhlner.ezrag`, JDK 21 toolchain active

### Quality gates

- [x] Kotlin compiler reports zero warnings (enforced by `-Werror`)
- [x] `./gradlew test` passes with at least one test covering `--help` (exit 0) and one covering unknown subcommand (exit non-zero)

---

## Task 02-subcommand-stubs

Register six subcommand stubs — `ingest`, `query`, `search`, `status`, `mcp-server`, `shell` — as Spring-managed picocli `@Command` beans attached to the top-level command. Each stub accepts `--help` and exits cleanly. Running `ez-rag --help` lists all six. This slice makes the tool's surface area discoverable before any logic is implemented.

### Implementation steps

- [x] Create `IngestCommand`, `QueryCommand`, `SearchCommand`, `StatusCommand`, `McpServerCommand`, `ShellCommand` as `@Component @Command` beans with `mixinStandardHelpOptions = true`
- [x] Annotate `EzRagCommand` with `@Command(subcommands = [IngestCommand::class, QueryCommand::class, SearchCommand::class, StatusCommand::class, McpServerCommand::class, ShellCommand::class])`
- [x] Each stub's `call()` prints "not yet implemented" to stdout and returns exit code 0
- [x] Add `--verbose` flag (`@Option(names = ["--verbose", "-v"])`) to `EzRagCommand` for inheritance by all subcommands (use `@Command(scope = ScopeType.INHERIT)` or mixin)
- [x] Write tests: for each subcommand, construct via `CommandLine` directly and assert `<subcommand> --help` exits 0; assert `ez-rag --help` output contains all six subcommand names

### Acceptance criteria

- [x] `ez-rag --help` output lists `ingest`, `query`, `search`, `status`, `mcp-server`, `shell`
- [x] `ez-rag ingest --help`, `ez-rag query --help`, `ez-rag search --help`, `ez-rag status --help`, `ez-rag mcp-server --help`, `ez-rag shell --help` each exit 0
- [x] Each subcommand is a Spring `@Component` resolved through the picocli Spring `IFactory`
- [x] `--verbose` flag is available on the top-level command and all subcommands

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with tests covering `--help` for each subcommand

---

## Task 03-config-service

Implement `ConfigService`, a Spring bean that resolves configuration from three sources in ascending priority: `~/.ez-rag/config.yml` (YAML, parsed via SnakeYAML or Jackson) → environment variables (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`) → CLI flags passed at construction. Exposes a typed `EzRagConfig` data class with all fields needed by subsequent PRDs. This slice is unit-tested in isolation — no Spring context required for tests.

`ConfigService` accepts its three sources as constructor parameters (or a functional interface) so tests can inject in-memory doubles without touching the filesystem or environment.

Fields of `EzRagConfig` (with defaults):
- `provider: String = "openai"`
- `embeddingProvider: String = "openai"`
- `model: String = "gpt-4o-mini"`
- `embeddingModel: String = "text-embedding-3-small"`
- `storePath: String = ".ez-rag/vector-store.json"`
- `chunkSize: Int = 1000`
- `chunkOverlap: Int = 200`
- `topK: Int = 5`
- `systemPrompt: String = ""` (empty = use built-in default)
- `outputFormat: String = "text"`
- `verbose: Boolean = false`

### Implementation steps

- [x] Create `EzRagConfig` as a Kotlin data class with all eleven fields and defaults as listed above
- [x] Create `ConfigService` with injectable source parameters: a `ConfigFileSource` (returns nullable `EzRagConfig` from YAML), an `EnvVarSource` (reads `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` from the environment map), and a `CliFlags` data class passed at call time
- [x] Implement merge: start from `EzRagConfig` defaults, overlay non-null config-file values, overlay env var values where applicable, overlay non-null CLI flags
- [x] Implement YAML config file reader that returns `null` (not an exception) when `~/.ez-rag/config.yml` does not exist
- [x] Wire `ConfigService` as a Spring `@Service` bean with real filesystem and `System.getenv` sources for production use
- [x] Write pure unit tests (no Spring context) covering: CLI flag beats file value, env var beats file value, file value used when no override, missing config file yields all defaults

### Acceptance criteria

- [x] Unit test: when config file sets `provider = "anthropic"` and CLI flag sets `provider = "openai"`, resolved `provider` is `"openai"`
- [x] Unit test: when config file sets `provider = "anthropic"` and env var `PROVIDER=openai` is present, resolved `provider` is `"openai"`
- [x] Unit test: when only config file sets `provider = "anthropic"` (no CLI flag, no env var), resolved `provider` is `"anthropic"`
- [x] Unit test: when `~/.ez-rag/config.yml` does not exist, `ConfigService.resolve()` returns `EzRagConfig` with all defaults and does not throw
- [x] `EzRagConfig` data class contains all eleven fields with correct types and default values

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with no Spring context loaded for `ConfigService` unit tests (verified by test execution time < 2 s for the config test class)

---

## Task 04-logging-and-banner-suppression

Configure `application.yml` to disable the Spring Boot startup banner and silence all framework logs at root level. Wire `--verbose` so that after flag parsing (but before any command logic runs) the Logback root logger level is raised to `DEBUG`. Clean CLI output is observable immediately from the command line.

### Implementation steps

- [x] Set `spring.main.banner-mode: off` and `logging.level.root: off` in `src/main/resources/application.yml`
- [x] In `EzRagCommand.call()` (or a picocli `IParameterExceptionHandler` / `@Spec` initializer), check the `--verbose` flag; if set, cast `LoggerFactory.getILoggerFactory()` to `LoggerContext` and set root logger level to `Level.DEBUG`
- [x] Write a unit test that instantiates `EzRagCommand` directly, sets `verbose = true`, calls `call()`, and asserts `(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger).level == Level.DEBUG`; restore root logger level in `@AfterEach`
- [x] Verify `./gradlew bootRun` (or integration test stdout capture) produces no Spring framework log lines and no banner on a normal `--help` invocation

### Acceptance criteria

- [x] `ez-rag --help` stdout and stderr contain no Spring Boot banner text
- [x] `ez-rag --help` stdout and stderr contain no Spring framework INFO/DEBUG log lines
- [x] After parsing `--verbose`, the Logback root logger level equals `DEBUG` (verified by unit test)
- [x] `spring.main.banner-mode: off` and `logging.level.root: off` are present in `application.yml`
- [x] Unit test resets the root logger level in `@AfterEach` to avoid cross-test contamination

### Quality gates

- [x] Kotlin compiler reports zero warnings
- [x] `./gradlew test` passes with the logging unit test included and no global logger state leaked between tests
