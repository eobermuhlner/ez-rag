## Problem Statement

Users of ez-rag have no way to discover which version of the tool they have installed. This makes it difficult to report bugs, verify upgrades, or confirm that a specific feature is available. Additionally, the project has no consistent versioning process, making it hard to communicate changes between releases. The MCP server also advertises a hardcoded version string that is never updated and does not reflect the actual release.

## Solution

Add a `--version` / `-V` flag to the ez-rag CLI that prints the current version and exits. The version is managed as a plain semantic version string in `gradle.properties`, baked into the JAR at build time, and read at runtime without any manual duplication. The MCP server version is updated to use the same source. A versioning policy is documented in `CLAUDE.md` so that Claude Code automatically increments the version on every commit that introduces a user-visible change, following standard semantic versioning rules.

## User Stories

1. As a user, I want to run `ez-rag --version` and see the current version number, so that I can confirm which release I have installed.
2. As a user, I want to run `ez-rag -V` as a short alias for `--version`, so that I can get the version quickly.
3. As a user, I want the version output to include the tool name (e.g., `ez-rag 0.2.0`), so that the output is unambiguous when captured in logs or bug reports.
4. As a user, I want the version to follow semantic versioning (`MAJOR.MINOR.PATCH`), so that I can reason about backwards compatibility between releases.
5. As a developer integrating with the MCP server, I want the MCP server to advertise the same version as the CLI binary, so that client tooling sees a consistent version across both interfaces.
6. As a contributor, I want a single place to look up the current version, so that I do not have to hunt through build files.
7. As a contributor, I want clear rules for when and how to increment the version, so that versioning decisions are consistent and do not require judgement calls each time.
8. As a contributor using Claude Code, I want the version to be bumped automatically as part of every commit that changes user-visible behaviour, so that releases always reflect the correct version without manual bookkeeping.
9. As a contributor, I want patch releases for bug fixes and internal refactors, so that users can distinguish fixes from new features.
10. As a contributor, I want minor releases for new CLI options, subcommands, MCP tools, or config keys, so that users know new capabilities are available.
11. As a contributor, I want major releases for breaking changes to the CLI interface, MCP tool signatures, or configuration format, so that users are warned before upgrading.
12. As a contributor, I want version increments to be skipped for test-only or documentation-only commits, so that the version number only advances when user-visible behaviour changes.
13. As a release manager, I want to create a git tag manually when cutting a release, so that I retain control over what constitutes an official release.

## User Acceptance Tests

1. Given the ez-rag binary is installed, when I run `ez-rag --version`, then the output is `ez-rag <MAJOR>.<MINOR>.<PATCH>` and the exit code is 0.
2. Given the ez-rag binary is installed, when I run `ez-rag -V`, then the output is identical to `ez-rag --version`.
3. Given the version in `gradle.properties` is `0.2.0`, when the project is built and `ez-rag --version` is run, then the output contains `0.2.0`.
4. Given the ez-rag MCP server is running, when a client requests the server info, then the advertised version matches the version printed by `ez-rag --version`.
5. Given a bug fix is committed, when the commit is made, then the PATCH component of the version in `gradle.properties` is incremented by 1.
6. Given a new CLI option or subcommand is added, when the commit is made, then the MINOR component is incremented and the PATCH component is reset to 0.
7. Given a breaking change to the CLI interface is committed, when the commit is made, then the MAJOR component is incremented and MINOR and PATCH are reset to 0.
8. Given a test-only or documentation-only change is committed, when the commit is made, then the version in `gradle.properties` is unchanged.

## Definition of Done

- `ez-rag --version` and `ez-rag -V` print `ez-rag <version>` and exit with code 0.
- The version string follows `MAJOR.MINOR.PATCH` with no `-SNAPSHOT` suffix.
- The version is defined in exactly one place (`gradle.properties`) and flows into the binary at build time with no manual duplication.
- The MCP server advertises the same version as the CLI.
- `CLAUDE.md` documents the versioning policy (when to bump, which component, and what to skip).
- All existing tests continue to pass.
- No regression in `--help` output or other CLI behaviour.

## Out of Scope

- Automated git tagging — tags are created manually at release time.
- Publishing to a package registry (e.g., Homebrew, Maven Central).
- Changelog generation.
- CI/CD pipeline changes.
- A `--version` flag on individual subcommands (only the root command gets it).

## Further Notes

- picocli's `mixinStandardHelpOptions = true` on `EzRagCommand` already wires the `-V` / `--version` flag; only a `versionProvider` needs to be supplied.
- Spring Boot's `bootBuildInfo` task generates `META-INF/build-info.properties` inside the JAR. Reading this resource at runtime avoids any manual string duplication and works correctly with `installDist` and the AppCDS archive flow.
- The initial version after this change is `0.1.0` (dropping the `-SNAPSHOT` suffix and resetting to a clean baseline).

---

## Technical Annex
> Written against codebase as of: 2026-06-14

### Architectural Decisions

**Version source of truth**

Create `gradle.properties` at the project root:
```properties
version=0.1.0
```

Update `build.gradle.kts`:
- Remove the inline `version = "0.0.1-SNAPSHOT"` line.
- Add `version = property("version") as String` to read from `gradle.properties`.
- Add the `springBoot { buildInfo() }` block so `bootBuildInfo` generates `META-INF/build-info.properties` inside the JAR at build time. This file contains `build.version=<version>` among other fields.

**`VersionProvider` (new class)**

Location: `src/main/kotlin/ch/obermuhlner/ezrag/VersionProvider.kt`

Implements `picocli.CommandLine.IVersionProvider`. Not a Spring `@Component` — instantiated by `SpringPicocliFactory`, which falls back to `CommandLine.defaultFactory().create(cls)` for non-beans, so no Spring context dependency is introduced.

```kotlin
class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        val props = Properties()
        VersionProvider::class.java.getResourceAsStream("/META-INF/build-info.properties")
            ?.use { props.load(it) }
        val version = props.getProperty("build.version", "unknown")
        return arrayOf("ez-rag $version")
    }
}
```

**`EzRagCommand` update**

Add `versionProvider = VersionProvider::class` to the `@Command` annotation. No other changes to `EzRagCommand`.

**`EzRagApplication` update**

Replace the hardcoded `"spring.ai.mcp.server.version" to "1.0.0"` in `preParseProviderFlags` with a runtime read of the same `build-info.properties` resource, using the same `Properties` lookup pattern as `VersionProvider`. This ensures the MCP server always advertises the same version as the CLI.

**CLAUDE.md versioning policy**

Add a new section to `CLAUDE.md`:

```
## Versioning

The version is defined in `gradle.properties` (`version=x.y.z`). Before every commit that introduces a user-visible change, increment the version following semantic versioning:

- **patch** (`x.y.z+1`): bug fixes, internal refactors, dependency updates, documentation changes
- **minor** (`x.y+1.0`): new CLI options, new subcommands, new MCP tools, new config keys
- **major** (`x+1.0.0`): breaking changes to CLI interface, MCP tool signatures, or configuration format

Do NOT bump the version for test-only changes.
Do NOT create git tags — tagging is done manually at release time.
```

### Automated Testing Decisions

**What makes a good test here**: test observable behaviour (the string returned by `getVersion()`, the exit code and stdout of the CLI), not implementation details like which resource file is opened.

**`VersionProviderTest`** (unit test, new)
- Verify `VersionProvider().getVersion()` returns an array of exactly one element.
- Verify the single element starts with `"ez-rag "`.
- Verify the version portion matches `\d+\.\d+\.\d+` (no SNAPSHOT suffix).
- Prior art: `McpSearchToolTest` for the pattern of constructing a component directly and asserting on its return value.

**CLI `--version` test** (unit test, new, in `EzRagCommandTest` or similar)
- Use `CommandLine(EzRagCommand(...)).execute("--version")` to verify exit code is 0 and output contains `"ez-rag "` followed by a semver string.
- Prior art: look at how `HelpCommand` or other command tests exercise the picocli layer without starting a full Spring context.
