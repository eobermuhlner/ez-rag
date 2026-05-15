## Problem Statement

There is no project yet. Before any RAG functionality can be built, a developer needs a correctly wired Spring Boot + Kotlin + Gradle project with a working CLI entry point, configuration hierarchy, and logging strategy suitable for a command-line tool that will be integrated with agentic coding assistants.

## Solution

Scaffold the `ez-rag` project with Kotlin, Spring Boot 3.4.x, Gradle Kotlin DSL, the Gradle Application Plugin, picocli for argument parsing, and a base CLI structure with a top-level command and subcommand stubs. Establish the configuration hierarchy (env vars → `~/.ez-rag/config.yml` → CLI flags) and suppress all framework logs by default with a `--verbose` override.

## User Stories

1. As a developer, I want a Gradle Kotlin DSL build file, so that the build is type-safe and consistent with the Kotlin codebase.
2. As a developer, I want the project to target JDK 21, so that I can use the latest LTS features supported by Spring Boot 3.4.x.
3. As a developer, I want Spring Boot 3.4.x as the base framework, so that I get dependency management, auto-configuration, and Spring AI compatibility.
4. As a developer, I want the Gradle Application Plugin configured, so that `./gradlew installDist` produces a ready-to-run `ez-rag` shell script.
5. As a developer, I want the Spring Boot fat JAR task configured, so that the application is self-contained and deployable without a separate classpath.
6. As a user, I want to run `ez-rag --help` and see a list of available subcommands, so that I can discover the tool's capabilities.
7. As a user, I want to run `ez-rag <subcommand> --help`, so that I can see usage for a specific command.
8. As a user, I want all Spring Boot startup banners and framework INFO logs suppressed by default, so that CLI output is clean and not polluted by framework noise.
9. As a user, I want to pass `--verbose` to any subcommand to enable debug logging, so that I can troubleshoot issues without permanently changing configuration.
10. As a user, I want API keys read from environment variables (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`), so that secrets are never stored in files committed to version control.
11. As a user, I want persistent defaults (provider, model, store path, chunk settings) stored in `~/.ez-rag/config.yml`, so that I don't have to repeat flags on every invocation.
12. As a user, I want CLI flags to override config file values, so that I can experiment without modifying my persistent config.
13. As a developer, I want the base package to be `ch.obermuhlner.ezrag`, with group ID `ch.obermuhlner` and artifact ID `ez-rag`, so that the project follows standard Maven conventions.
14. As a developer, I want picocli integrated with Spring Boot's `CommandLineRunner`, so that subcommands are Spring-managed beans with full dependency injection.
15. As a user, I want the tool to exit with a non-zero exit code on errors, so that scripts and agentic tools can detect failure.
16. As a developer, I want the configuration hierarchy implemented as a dedicated `ConfigService` bean, so that all other components can depend on a single resolved-config contract without knowing the source.

## Implementation Decisions

- **Build**: Gradle Kotlin DSL (`build.gradle.kts`). Spring Boot Gradle Plugin for fat JAR (`bootJar`). Gradle Application Plugin for distribution scripts. The `mainClass` is set to the picocli top-level command.
- **CLI entry point**: A `@SpringBootApplication` class that implements `CommandLineRunner` and delegates to picocli's `CommandLine` dispatcher. This wires Spring DI into picocli command beans.
- **Subcommand stubs**: `IngestCommand`, `QueryCommand`, `SearchCommand`, `StatusCommand`, `McpServerCommand`, `ShellCommand` — each a Spring-managed picocli `@Command` bean. In this PRD they are stubs; other PRDs implement them. (`SearchCommand` is specified in PRD 07 but stubbed here alongside the others.)
- **ConfigService**: A single bean that merges config from (lowest to highest priority): `~/.ez-rag/config.yml` → environment variables → CLI flags. Exposes a typed config record/data class to the rest of the application. Config file is YAML parsed via Jackson or SnakeYAML.
- **Logging**: `application.yml` sets root log level to `OFF` and Spring/Hibernate loggers to `OFF`. The `--verbose` flag programmatically sets the root logger to `DEBUG` before the Spring context starts.
- **Spring Boot banner**: Disabled in `application.yml` (`spring.main.banner-mode=off`).
- **Exit code**: picocli's `IExitCodeExceptionMapper` maps exceptions to non-zero exit codes. `System.exit` is called with the picocli exit code.

## Testing Decisions

- **What makes a good test**: Test the observable contract of each module — what it returns or what side effects it produces — not which private methods it calls.
- **ConfigService**: Unit-test the merge priority (CLI flag beats config file beats env var) using in-memory config sources. No Spring context needed.
- **CLI wiring**: Integration test that `--help` exits 0 and prints usage; that an unknown subcommand exits non-zero. Use picocli's `CommandLine` directly without Spring.
- **No tests for stubs**: Subcommand stubs contain no logic; tests belong in the PRDs that implement them.

## Out of Scope

- Actual implementation of `ingest`, `query`, `search`, `status`, `mcp-server`, `shell` — covered in PRDs 02–07.
- Provider-specific Spring AI configuration — covered in PRD 04.
- CI/CD pipeline setup.
- Windows-specific testing.

## Further Notes

The `ConfigService` data class is the single most important contract established in this PRD. All subsequent PRDs depend on it exposing the correct fields (provider, embeddingProvider, model, embeddingModel, storePath, chunkSize, chunkOverlap, topK, systemPrompt, outputFormat, verbose). Design it with all fields from the start, even though most are unused until later PRDs.
