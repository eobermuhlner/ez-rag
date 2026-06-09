## Problem Statement

The `ez-rag mcp-server` command currently supports only the stdio transport, which requires an MCP client to launch the process directly and communicate over stdin/stdout. This makes it impossible to connect to the server from a remote machine, from a browser-based client, or from multiple clients simultaneously. Users who want to run ez-rag as a persistent, network-accessible MCP service have no supported path.

## Solution

Add a `--transport` option to `mcp-server` that lets the user choose between `stdio` (the existing default) and `http`. When `http` is selected, the server starts an embedded web server and exposes the MCP protocol over HTTP+SSE, making it reachable on a configurable port. A `--port` option controls which port is used, defaulting to `8080`. On successful startup in HTTP mode, the server prints the SSE endpoint URL to stdout so the user knows where to connect.

Stdio mode is unchanged; backward compatibility is fully preserved.

## User Stories

1. As a CLI user, I want `ez-rag mcp-server` with no flags to behave exactly as before, so that my existing MCP client configuration keeps working without changes.
2. As a CLI user, I want to run `ez-rag mcp-server --transport stdio` explicitly, so that I can be explicit about the transport in scripts or documentation.
3. As a CLI user, I want to run `ez-rag mcp-server --transport http` to start a network-accessible MCP server, so that I can connect to it from any MCP client that supports HTTP+SSE.
4. As a CLI user, I want the server to print the SSE endpoint URL when started in HTTP mode, so that I immediately know where to point my client.
5. As a CLI user, I want `--port 9090` to change the HTTP server's listening port, so that I can avoid conflicts with other services on my machine.
6. As a CLI user, I want the HTTP server to use a sensible default port (8080) when `--port` is omitted, so that I don't have to specify it every time.
7. As a CLI user, I want `ez-rag mcp-server --help` to document both `--transport` and `--port`, so that I can discover the options without reading external documentation.
8. As a CLI user, I want logging to be suppressed by default in HTTP mode (as it already is in stdio mode), so that the terminal output stays clean.
9. As a CLI user, I want to enable verbose logging with `--verbose` in HTTP mode, so that I can debug connection issues when needed.
10. As a CLI user, I want `--transport` to reject unknown values with a clear error message, so that typos are caught immediately.
11. As a developer, I want the HTTP server to keep running after `mcp-server` starts (no premature exit), so that it is available for the lifetime of the process.
12. As a developer integrating ez-rag into a larger system, I want to connect multiple clients to the same running HTTP server instance, so that I avoid the overhead of launching a new process per client.
13. As a developer, I want the tool callbacks (status, search, query, ingest, and others) to be available over the HTTP transport, so that the same RAG functionality is reachable regardless of transport.
14. As a developer, I want the `--store-dir` option to work in HTTP mode, so that I can point the server at a specific vector store file.
15. As a developer, I want the `--provider` and `--embedding-provider` flags to work in HTTP mode, so that I can choose the AI backend independently of the transport.

## User Acceptance Tests

1. Given a running instance of `ez-rag mcp-server` with no flags, when an MCP client connects over stdio, then the client can invoke all tools and receive valid responses — identical behaviour to before this feature was added.

2. Given a running instance of `ez-rag mcp-server --transport stdio`, when an MCP client connects over stdio, then the client can invoke all tools and receive valid responses.

3. Given a user runs `ez-rag mcp-server --transport http`, when the server starts, then the terminal displays a message of the form `MCP server listening on http://localhost:8080/sse`.

4. Given a running instance of `ez-rag mcp-server --transport http`, when an MCP client connects to `http://localhost:8080/sse`, then the client can invoke all tools (status, search, query, ingest, and others) and receive valid responses.

5. Given a user runs `ez-rag mcp-server --transport http --port 9090`, when the server starts, then the terminal displays `MCP server listening on http://localhost:9090/sse` and the server accepts connections on port 9090.

6. Given a running instance of `ez-rag mcp-server --transport http`, when two separate MCP clients connect simultaneously, then both clients receive valid responses without interfering with each other.

7. Given a user runs `ez-rag mcp-server --transport unknownvalue`, when the command is executed, then it exits immediately with a non-zero status code and a human-readable error message identifying the invalid transport value.

8. Given a user runs `ez-rag mcp-server --help`, when the output is inspected, then both `--transport` and `--port` are listed with their descriptions and defaults.

9. Given a user runs `ez-rag mcp-server --transport http` without `--verbose`, when the server is running and processing requests, then no log output appears in the terminal beyond the startup URL line.

10. Given a user runs `ez-rag mcp-server --transport http --verbose`, when the server processes a request, then diagnostic log output appears in the terminal.

11. Given a user runs `ez-rag mcp-server --transport http --store-dir /path/to/store`, when the server is running, then tool invocations operate against the specified store directory.

12. Given a user runs any ez-rag subcommand other than `mcp-server` (e.g. `ingest`, `query`, `status`), when the command executes, then no embedded web server is started.

## Definition of Done

- All user acceptance tests pass.
- `ez-rag mcp-server` with no flags behaves identically to before this feature (no regression in stdio mode).
- All other subcommands are unaffected; no embedded web server starts for non-mcp-server invocations.
- `ez-rag mcp-server --help` documents `--transport` and `--port`.
- All new and modified automated tests pass.
- No regression in the existing test suite.

## Out of Scope

- Streamable HTTP transport (MCP spec 2025-03-26) — not available in MCP SDK 0.10.0 / Spring AI 1.0.0.
- TLS / HTTPS support.
- Authentication or authorization on the HTTP endpoint.
- Configurable SSE endpoint paths (`/sse`, `/mcp/message`).
- Running both stdio and HTTP transports simultaneously in the same process.
- Dynamic port allocation (random free port).
- Health-check or readiness endpoints.

## Further Notes

The HTTP transport uses HTTP+SSE as defined by the pre-2025-03-26 MCP specification. This is the only HTTP transport variant available in MCP SDK 0.10.0. A future upgrade to a newer SDK version may introduce streamable HTTP, which would be a separate feature.

---

## Technical Annex
> Written against codebase as of: 2026-06-08

This section contains the architectural and automated testing decisions derived from the planning session. It is intended for architect and developer review. Specific class names, method signatures, and implementation details are appropriate here.

When this document is later used to generate tasks, the task-generation step will verify each decision against the current state of the codebase and flag any conflicts before proceeding.

### Architectural Decisions

#### 1. `--transport` as a Kotlin enum

`McpServerCommand` gains a `--transport` picocli `@Option` backed by a Kotlin `enum class Transport { stdio, http }`. Picocli validates the value automatically and rejects unknown values with a `USAGE` exit code, satisfying User Story 10 without manual validation code. The `--port` option is a plain `Int` with default `8080`.

```kotlin
enum class Transport { stdio, http }

@Option(names = ["--transport"], description = ["Transport: stdio (default) or http."], defaultValue = "stdio")
var transport: Transport = Transport.stdio

@Option(names = ["--port"], description = ["HTTP port (HTTP transport only). Default: \${DEFAULT-VALUE}"], defaultValue = "8080")
var port: Int = 8080
```

The `@Command` description on `McpServerCommand` should be updated from "Start the MCP server over stdio." to reflect both transport modes.

#### 2. `preParseProviderFlags` — extended to handle `--transport` and `--port`

`preParseProviderFlags` in `EzRagApplication.kt` performs a linear scan of `args[]` before the Spring context starts. It must be extended as follows:

- When `mcp-server` is absent: set `spring.main.web-application-type=none` (see decision 3 for why this is now explicit).
- When `mcp-server` is present and `--transport http` is detected:
  - `spring.ai.mcp.server.stdio=false`
  - `spring.main.web-application-type=servlet`
  - `server.port=<value of --port, default 8080>`
  - `spring.ai.mcp.server.name=ez-rag`, `spring.ai.mcp.server.version=1.0.0` (unchanged)
  - Console logging pattern is NOT suppressed (HTTP mode uses `root: off` default, overridden by `--verbose`)
- When `mcp-server` is present and `--transport stdio` (or absent):
  - Current behavior unchanged: `spring.ai.mcp.server.stdio=true`, `logging.pattern.console=""`, name/version set.
  - `spring.main.web-application-type=none`

The `--port` flag is scanned with the same `name=value` and `name value` patterns already used for `--provider`, `--embedding-provider`, etc.

#### 3. `application.yml` — remove `web-application-type: none`

`spring.main.web-application-type: none` must be **removed** from `application.yml`.

Rationale: `SpringApplicationBuilder.properties()` populates Spring's *default properties* source, which is the lowest-priority source in Spring Boot's property hierarchy. `application.yml` sits above it and would silently override `web-application-type=servlet` injected by the preparser for HTTP mode. Removing the entry from `application.yml` leaves the preparser's value as the only source, so it applies regardless of mode. The preparser sets `none` for all non-HTTP paths to prevent the embedded web server from starting when `spring-boot-starter-web` is on the classpath.

#### 4. `build.gradle.kts` — add `spring-boot-starter-web`

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web")
```

The embedded Tomcat server starts only when `web-application-type=servlet` (HTTP mode). For all other invocations, `web-application-type=none` prevents it from starting. The JVM flags (`-XX:TieredStopAtLevel=1`, `-XX:+UseSerialGC`) are left unchanged; users who need server-grade JVM tuning for a long-lived HTTP instance can override via `EZ_RAG_OPTS`.

#### 5. `McpHttpTransportConfiguration` — new `@Configuration` class

Package: `ch.obermuhlner.ezrag.config`

Activated by: `@ConditionalOnProperty(name = ["spring.ai.mcp.server.stdio"], havingValue = "false")`

Responsibilities:
- Declare a `HttpServletSseServerTransportProvider` bean, constructed with an injected `ObjectMapper`, SSE path `/sse`, and message path `/mcp/message`.
- Wrap it in a `ServletRegistrationBean<HttpServletSseServerTransportProvider>` so the embedded Tomcat routes requests to it.

```kotlin
@Configuration
@ConditionalOnProperty(name = ["spring.ai.mcp.server.stdio"], havingValue = "false")
class McpHttpTransportConfiguration {

    @Bean
    fun httpServletSseServerTransportProvider(objectMapper: ObjectMapper): HttpServletSseServerTransportProvider =
        HttpServletSseServerTransportProvider(objectMapper, "/mcp/message", "/sse")

    @Bean
    fun mcpServletRegistration(
        provider: HttpServletSseServerTransportProvider
    ): ServletRegistrationBean<HttpServletSseServerTransportProvider> =
        ServletRegistrationBean(provider, "/sse", "/mcp/message")
}
```

This class is intentionally separate from `McpServerCommand` to keep CLI parsing concerns isolated from Spring wiring concerns.

#### 6. `McpServerCommand.call()` — print startup URL for HTTP mode

After the `CountDownLatch` is set up and the shutdown hook is registered, but before `done.await()`:

```kotlin
if (transport == Transport.http) {
    println("MCP server listening on http://localhost:$port/sse")
}
done.await()
```

By the time `call()` is reached, the Spring context is fully started and the embedded Tomcat is already listening, so the message is accurate.

#### 7. `HttpServletSseServerTransportProvider` constructor signature

From inspection of `mcp-0.10.0.jar`:
```
HttpServletSseServerTransportProvider(ObjectMapper, String, String)
```
Parameters are: `objectMapper`, `messageEndpoint` (`/mcp/message`), `sseEndpoint` (`/sse`).

### Automated Testing Decisions

A good test verifies externally observable behaviour through the public interface of the module under test. Tests must not assert on private fields, Spring bean names, or internal wiring details.

#### `EzRagCommandTest.kt` — new `preParseProviderFlags` unit tests (no Spring)

Prior art: existing `preParseProviderFlags` tests in `EzRagCommandTest.kt` (`--rerank-model`, `--rerank-candidates`).

New cases to cover:
- `mcp-server --transport http` → `stdio=false`, `web-application-type=servlet`, `server.port=8080`
- `mcp-server --transport http --port 9090` → `server.port=9090`
- `mcp-server --transport http --port=9090` (equals syntax) → `server.port=9090`
- `mcp-server --transport stdio` → `stdio=true`, `web-application-type=none`
- `mcp-server` (no `--transport`) → `stdio=true`, `web-application-type=none`
- Non-mcp-server subcommand (e.g. `ingest`) → `web-application-type=none`, no MCP properties set
- Verify that `spring.main.web-application-type=none` is set for all non-HTTP paths (covers the regression guard from decision 3)

#### `SubcommandTest.kt` — new option acceptance/rejection tests (picocli only, no Spring)

Prior art: existing option-acceptance tests in `SubcommandTest.kt`.

New cases:
- `mcp-server --transport http --help` exits with code `0` (transport accepted, help printed without calling `call()`)
- `mcp-server --transport stdio --help` exits with code `0`
- `mcp-server --transport http --port 9090 --help` exits with code `0`
- `mcp-server --transport unknown` exits with `CommandLine.ExitCode.USAGE` (invalid enum value rejected by picocli)

Note: `--help` is paired with transport options to prevent `call()` from being invoked and blocking on `CountDownLatch.await()`.

#### `McpHttpTransportConfigurationTest.kt` — new focused Spring context test

Prior art: `McpServerCommandTest.kt` (`@ExtendWith(SpringExtension::class)`, `@Import`, `@TestPropertySource`).

Structure:
```kotlin
@ExtendWith(SpringExtension::class)
@Import(McpHttpTransportConfiguration::class)
@TestPropertySource(properties = ["spring.ai.mcp.server.stdio=false"])
class McpHttpTransportConfigurationTest {

    @TestConfiguration
    class TestConfig {
        @Bean fun objectMapper() = ObjectMapper()
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `HttpServletSseServerTransportProvider bean is present`() {
        assertThat(applicationContext.getBean(HttpServletSseServerTransportProvider::class.java)).isNotNull()
    }
}
```

This test verifies that the `@ConditionalOnProperty` activates correctly and the bean is wired without requiring a running servlet container.
