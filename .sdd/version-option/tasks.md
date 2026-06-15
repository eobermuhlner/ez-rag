## Task [01-build-info-wiring]

Move the version string out of `build.gradle.kts` and into `gradle.properties` as the single source of truth, then configure Spring Boot's `bootBuildInfo` task to bake it into `META-INF/build-info.properties` inside the JAR at build time. This is the foundation that Tasks 02 and 03 depend on.

### Implementation steps

- [x] Create `gradle.properties` at the project root containing `version=0.1.0`
- [x] Remove `version = "0.0.1-SNAPSHOT"` from `build.gradle.kts`
- [x] Add `version = property("version") as String` to `build.gradle.kts` so Gradle reads from `gradle.properties`
- [x] Add `springBoot { buildInfo() }` block to `build.gradle.kts`

### Acceptance criteria

- [x] `gradle.properties` exists at the project root and contains `version=0.1.0`
- [x] `build.gradle.kts` no longer contains the literal string `0.0.1-SNAPSHOT`
- [x] After `./gradlew bootBuildInfo`, `build/resources/main/META-INF/build-info.properties` exists and contains `build.version=0.1.0`
- [x] `./gradlew test` passes with no regressions

### Quality gates

- [x] `./gradlew test` reports 0 failures

---

## Task [02-version-provider-and-cli-flag]

Add a `VersionProvider` that reads the version from the classpath resource baked in by `bootBuildInfo` and wire it into picocli so that `ez-rag --version` / `-V` prints `ez-rag 0.1.0` and exits 0. Use TDD: write the failing test first.

### Implementation steps

- [x] Write a failing `VersionProviderTest` asserting the format of `getVersion()` output
- [x] Create `VersionProvider` implementing `picocli.CommandLine.IVersionProvider`; reads `META-INF/build-info.properties` from the classpath; returns `["ez-rag <version>"]`; falls back to `["ez-rag unknown"]` if the resource is missing; not a Spring `@Component`
- [x] Add `versionProvider = VersionProvider::class` to the `@Command` annotation on `EzRagCommand`
- [x] Add `--version` and `-V` tests to `EzRagCommandTest`

### Acceptance criteria

- [x] `VersionProvider().getVersion()` returns an array of exactly one element
- [x] That element starts with `"ez-rag "`
- [x] The version portion of that element matches `\d+\.\d+\.\d+` (no SNAPSHOT suffix)
- [x] `VersionProvider` loaded without `META-INF/build-info.properties` on the classpath returns `["ez-rag unknown"]`
- [x] `CommandLine(EzRagCommand()).execute("--version")` exits with code 0
- [x] `CommandLine(EzRagCommand()).execute("-V")` exits with code 0
- [x] The stdout of `--version` contains `"ez-rag "` followed by a semver string

### Quality gates

- [x] `./gradlew test` reports 0 failures

---

## Task [03-mcp-server-version-sync]

Replace the hardcoded `"1.0.0"` MCP server version in `preParseProviderFlags` with a runtime read from `META-INF/build-info.properties` so the MCP server always advertises the same version as the CLI. Use TDD: write the failing test first.

### Implementation steps

- [x] Write a failing test in `EzRagCommandTest`: call `preParseProviderFlags(arrayOf("mcp-server"), tempDir)` (pass a `@TempDir` as `localEzRagDir` to avoid touching the real filesystem) and assert `spring.ai.mcp.server.version` matches `\d+\.\d+\.\d+`
- [x] Extract a private helper function (or reuse the same `Properties` lookup pattern as `VersionProvider`) to read the version from `META-INF/build-info.properties` in `EzRagApplication.kt`
- [x] Replace the hardcoded `"1.0.0"` string in `preParseProviderFlags` with the result of that lookup

### Acceptance criteria

- [x] `preParseProviderFlags(arrayOf("mcp-server"), tempDir)["spring.ai.mcp.server.version"]` matches `\d+\.\d+\.\d+`
- [x] The MCP server version equals the version portion of `VersionProvider().getVersion()[0]` (i.e., `removePrefix("ez-rag ")`)
- [x] No literal `"1.0.0"` string remains in `EzRagApplication.kt`

### Quality gates

- [x] `./gradlew test` reports 0 failures

---

## Task [04-versioning-policy-doc]

Document the semantic versioning policy in `CLAUDE.md` so that Claude Code knows to increment the version in `gradle.properties` before every commit that introduces a user-visible change. The rules are expressed in terms of the conventional commit types already defined in the `## Commit Messages` section, avoiding duplication.

### Implementation steps

- [x] Add a `## Versioning` section to `CLAUDE.md` with the following rules:
  - Version lives in `gradle.properties` as `version=x.y.z`
  - **patch** (`x.y.z+1`): commits with type `fix:`, `refactor:`, or `perf:`
  - **minor** (`x.y+1.0`): commits with type `feat:` (new CLI options, subcommands, MCP tools, config keys)
  - **major** (`x+1.0.0`): any commit with `!` suffix or `BREAKING CHANGE:` footer (breaking CLI interface, MCP tool signatures, or configuration format)
  - Do NOT bump for `test:`, `docs:`, or `chore:` commits
  - Do NOT create git tags — tagging is done manually at release time

### Quality gates

- [x] `grep -c "## Versioning" CLAUDE.md` returns `1`
- [x] `./gradlew test` reports 0 failures
