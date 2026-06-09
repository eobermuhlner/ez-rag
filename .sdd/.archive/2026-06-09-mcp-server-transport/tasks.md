# Tasks: mcp-server-transport

## Task [01-transport-cli-and-preparse]

Adds `--transport` and `--port` CLI options to `McpServerCommand` and extends `preParseProviderFlags` to detect and route the transport mode before Spring starts. From this task forward, the CLI accepts both transports, rejects unknown values with a USAGE exit code, shows both options in `--help`, and `preParseProviderFlags` injects the correct Spring properties for each path: `web-application-type=servlet` for HTTP mode, `web-application-type=none` for everything else. The stdio transport continues to work unchanged.

### Implementation steps

- [x] Add `enum class Transport { stdio, http }` as a top-level enum (same file as `McpServerCommand` or a separate file in the `command` package)
- [x] Add `--transport` picocli `@Option` (default `"stdio"`) and `--port` picocli `@Option` (default `8080`) to `McpServerCommand`
- [x] Update `@Command` description on `McpServerCommand` from "Start the MCP server over stdio." to reflect both transport modes
- [x] Extend `preParseProviderFlags` in `EzRagApplication.kt`:
  - When `mcp-server` is absent: set `spring.main.web-application-type=none`
  - When `mcp-server` is present and `--transport http` is detected: set `spring.ai.mcp.server.stdio=false`, `spring.main.web-application-type=servlet`, `server.port=<port value, default 8080>`, `spring.ai.mcp.server.name=ez-rag`, `spring.ai.mcp.server.version=1.0.0`
  - When `mcp-server` is present and `--transport stdio` or `--transport` is absent: set `spring.ai.mcp.server.stdio=true`, `spring.main.web-application-type=none`, `logging.pattern.console=""`, `spring.ai.mcp.server.name=ez-rag`, `spring.ai.mcp.server.version=1.0.0`
  - Scan `--port` with both `name value` (space-separated) and `name=value` (equals) patterns, same as existing flags
- [x] Add unit tests in `EzRagCommandTest.kt` for `preParseProviderFlags`: `mcp-server --transport http`, `mcp-server --transport http --port 9090`, `mcp-server --transport http --port=9090`, `mcp-server --transport stdio`, `mcp-server` (no `--transport`), non-mcp-server subcommand (e.g. `ingest`)
- [x] Add `SubcommandTest.kt` cases: `mcp-server --transport http --help` exits `0`, `mcp-server --transport stdio --help` exits `0`, `mcp-server --transport http --port 9090 --help` exits `0`, `mcp-server --transport unknown` exits `CommandLine.ExitCode.USAGE`

### Acceptance criteria

- [x] `ez-rag mcp-server --transport unknown` exits with `CommandLine.ExitCode.USAGE`
- [x] `ez-rag mcp-server --help` output contains both `--transport` and `--port` with descriptions and default values
- [x] `preParseProviderFlags(["mcp-server", "--transport", "http"])` returns `spring.ai.mcp.server.stdio=false`, `spring.main.web-application-type=servlet`, `server.port=8080`
- [x] `preParseProviderFlags(["mcp-server", "--transport", "http", "--port", "9090"])` returns `server.port=9090`
- [x] `preParseProviderFlags(["mcp-server", "--transport", "http", "--port=9090"])` returns `server.port=9090`
- [x] `preParseProviderFlags(["mcp-server"])` returns `spring.ai.mcp.server.stdio=true`, `spring.main.web-application-type=none`
- [x] `preParseProviderFlags(["ingest"])` returns `spring.main.web-application-type=none` and does not contain `spring.ai.mcp.server.stdio`

### Quality gates

- [x] No compiler warnings (`-Werror` is set in `build.gradle.kts`)
- [x] `./gradlew test` passes with no regressions in existing tests

---

## Task [02-http-transport-spring-infrastructure]

Wires up the embedded HTTP server so that `mcp-server --transport http` starts an HTTP+SSE MCP server accessible at `http://localhost:<port>/sse`. Adds `spring-boot-starter-web`, removes the static `web-application-type: none` from `application.yml` (safe because Task 01's preparser now explicitly sets this property for every invocation path), creates `McpHttpTransportConfiguration` that activates only in HTTP mode, and adds the startup URL print to `McpServerCommand.call()`. After this task all requirements user stories are fulfilled.

Note: This task depends on Task 01 having extended `preParseProviderFlags` to set `spring.main.web-application-type=none` for all non-HTTP invocations. Removing `web-application-type: none` from `application.yml` without that preparser change would cause all subcommands to start an embedded Tomcat.

### Implementation steps

- [x] Add `implementation("org.springframework.boot:spring-boot-starter-web")` to `build.gradle.kts` dependencies
- [x] Remove `web-application-type: none` from `application.yml` (the preparser from Task 01 now owns this property for all paths)
- [x] Create `McpHttpTransportConfiguration` in `ch.obermuhlner.ezrag.config`:
  - Annotate with `@Configuration`, `@ConditionalOnProperty(name = ["spring.ai.mcp.server.stdio"], havingValue = "false")`, and `@ConditionalOnMissingBean(McpServerTransportProvider::class)` (guard against future SDK versions providing this bean via autoconfiguration)
  - Declare `httpServletSseServerTransportProvider(objectMapper: ObjectMapper): HttpServletSseServerTransportProvider` bean constructed with message endpoint `/mcp/message` and SSE endpoint `/sse`
  - Declare `mcpServletRegistration(provider: HttpServletSseServerTransportProvider): ServletRegistrationBean<HttpServletSseServerTransportProvider>` bean mapped to `/sse` and `/mcp/message`
- [x] In `McpServerCommand.call()`, before `done.await()`, add: `if (transport == Transport.http) { println("MCP server listening on http://localhost:$port/sse") }`
- [x] Write `McpHttpTransportConfigurationTest` verifying the `HttpServletSseServerTransportProvider` bean is present when `spring.ai.mcp.server.stdio=false`; use `@ExtendWith(SpringExtension::class)`, `@Import(McpHttpTransportConfiguration::class)`, `@TestPropertySource(properties = ["spring.ai.mcp.server.stdio=false"])`, and an inner `@TestConfiguration` that provides an `ObjectMapper` bean
- [x] Extend `preParseProviderFlags` to detect `--verbose`/`-v` and suppress `logging.level.root` only when verbose is absent; move `logging.level.root=off` from `application.yml` into the preparser for all non-verbose paths so `--verbose` in HTTP mode enables Spring Boot default (INFO) logging

### Acceptance criteria

- [x] `McpHttpTransportConfigurationTest` passes: `HttpServletSseServerTransportProvider` bean is present when `spring.ai.mcp.server.stdio=false`
- [x] `./gradlew build` compiles and links against `spring-boot-starter-web` without error
- [x] Existing `McpServerCommandTest` continues to pass (stdio transport unaffected; it passes `spring.ai.mcp.server.enabled=false` which keeps the context minimal)
- [x] Running `ez-rag mcp-server --transport http` prints `MCP server listening on http://localhost:8080/sse` to stdout before blocking
- [x] Running `ez-rag mcp-server --transport http --port 9090` prints `MCP server listening on http://localhost:9090/sse`
- [x] Running any non-mcp-server subcommand (e.g. `ez-rag status`) does not start an embedded Tomcat

### Quality gates

- [x] No compiler warnings (`-Werror` enforced)
- [x] `./gradlew test` passes including both new and existing tests
