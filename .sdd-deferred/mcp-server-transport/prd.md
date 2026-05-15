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
13. As a developer, I want the tool callbacks (status, search, query, ingest) to be available over the HTTP transport, so that the same RAG functionality is reachable regardless of transport.
14. As a developer, I want the `--store` option to work in HTTP mode, so that I can point the server at a specific vector store file.
15. As a developer, I want the `--provider` and `--embedding-provider` flags to work in HTTP mode, so that I can choose the AI backend independently of the transport.

## Implementation Decisions

### Modules to build or modify

**`EzRagApplication` — extend `preParseProviderFlags`**

The existing pre-parse function detects the `mcp-server` subcommand and injects `spring.ai.mcp.server.stdio=true` before the Spring context starts. It must also detect `--transport` and `--port` in the raw args array.

- When `mcp-server` is present and `--transport http` is detected:
  - Set `spring.ai.mcp.server.stdio=false`
  - Set `spring.main.web-application-type=servlet`
  - Set `server.port` to the value of `--port` (default `8080`)
  - Do not set `logging.pattern.console=""` (logging suppression is handled via the existing `root: off` default unless `--verbose` is passed)
- When `mcp-server` is present and `--transport stdio` (or absent):
  - Current behavior unchanged (`stdio=true`, `web-application-type=none`, console pattern suppressed)

**`McpServerCommand` — add `--transport` and `--port` options**

Add two picocli `@Option` fields:
- `--transport`: an enum or string with values `stdio` and `http`, defaulting to `stdio`
- `--port`: an integer, defaulting to `8080`

In `call()`, after the latch setup, print the startup URL to stdout when transport is `http`:
```
MCP server listening on http://localhost:<port>/sse
```

The `CountDownLatch` remains in `call()` for both modes.

**`McpHttpTransportConfiguration` — new `@Configuration` class**

A new Spring configuration class activated only when `spring.ai.mcp.server.stdio=false` (i.e., HTTP mode). It is responsible for:

- Creating a `HttpServletSseServerTransportProvider` bean (from `io.modelcontextprotocol.sdk:mcp`) with the default SSE endpoint path (`/sse`) and message endpoint path (`/mcp/message`)
- Registering that provider as a `ServletRegistrationBean` so the embedded web server routes requests to it

This class is intentionally separate from `McpServerCommand` to keep CLI parsing concerns isolated from Spring wiring concerns.

**`build.gradle.kts` — add web dependency**

Add `spring-boot-starter-web` as a compile dependency. In stdio mode, `web-application-type=none` (injected by `preParseProviderFlags`) prevents the embedded web server from starting. In HTTP mode, `web-application-type=servlet` activates it. The dependency is always on the classpath; the web server only starts when the mode requires it.

### Transport selection protocol

The `--transport` value is resolved in two places:

1. **`preParseProviderFlags`** — a raw-string scan of `args[]` that runs before Spring starts, used to set Spring Boot properties.
2. **`McpServerCommand.call()`** — the picocli-parsed value, used to decide whether to print the startup URL.

Both read from the same `--transport` CLI flag. The pre-parser does a simple linear scan (consistent with the existing `--provider` / `--port` pre-parsing pattern).

### HTTP transport variant

Spring AI 1.0.0 bundles MCP SDK 0.10.0, which provides `HttpServletSseServerTransportProvider` — an HTTP+SSE transport implementing the pre-2025-03-26 MCP spec. This is the only HTTP transport available in this version; streamable HTTP is not available.

### Logging in HTTP mode

The existing `logging.pattern.console=""` suppression is only applied in stdio mode (where stdout must remain protocol-clean). In HTTP mode, the `root: off` default in `application.yml` already silences most noise. Verbose output is enabled by `--verbose` (which raises the root log level to DEBUG) in both modes.

## Testing Decisions

A good test verifies externally observable behavior through the public interface of the module under test, not the internal implementation. Tests should not assert on private fields, Spring bean names, or internal wiring details that could change without breaking behavior.

**`preParseProviderFlagsTest` (unit tests, new)**

Pure Kotlin tests with no Spring context. Call `preParseProviderFlags` with various arg arrays and assert on the returned `Map<String, String>`. These tests are fast and the most important coverage for this feature, since the pre-parser is the critical path for correct Spring configuration. Prior art: the existing `EzRagCommandTest` for plain Kotlin tests without Spring.

Cover:
- `--transport http` produces `stdio=false`, `web-application-type=servlet`, `server.port=8080`
- `--transport http --port 9090` produces `server.port=9090`
- `--transport stdio` produces `stdio=true`, `web-application-type=none`
- No `--transport` flag produces `stdio=true` (default)
- `--transport` flag with other subcommands (e.g. `ingest`) does not set MCP properties

**`SubcommandTest` (modify existing)**

Picocli-only tests (no Spring). Add:
- `mcp-server --transport http` is accepted (exit code is not `USAGE`)
- `mcp-server --transport stdio` is accepted
- `mcp-server --transport http --port 9090` is accepted
- `mcp-server --transport unknown` exits with `USAGE` error

Prior art: existing `SubcommandTest` patterns for option acceptance.

**`McpHttpTransportConfigurationTest` (integration test, new)**

A focused Spring context test that loads `McpHttpTransportConfiguration` with `spring.ai.mcp.server.stdio=false` and `spring.main.web-application-type=servlet`. Asserts that a `HttpServletSseServerTransportProvider` bean exists in the context. Prior art: `McpServerCommandTest` uses `@ExtendWith(SpringExtension::class)` with `@Import` and `@TestPropertySource`.

## Out of Scope

- Streamable HTTP transport (MCP spec 2025-03-26) — not available in MCP SDK 0.10.0 / Spring AI 1.0.0
- TLS / HTTPS support
- Authentication or authorization on the HTTP endpoint
- Configurable SSE endpoint paths (`/sse`, `/mcp/message`)
- Running both stdio and HTTP transports simultaneously in the same process
- Dynamic port allocation (random free port)
- Health-check or readiness endpoints

## Further Notes

The `HttpServletSseServerTransportProvider` is a servlet-based component; it requires `spring-boot-starter-web` (Tomcat by default). If the project later migrates to a reactive stack, the transport provider would need to change to a WebFlux-compatible variant.

The `preParseProviderFlags` function performs raw string scanning rather than full picocli parsing. This is intentional: picocli only runs after the Spring context is initialized, but Spring needs these properties at startup. The scanning logic should be kept minimal and covered thoroughly by unit tests.
