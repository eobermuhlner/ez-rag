# PRD: `install-skill` command — agentic coding tool skill installation

## Problem Statement

After installing ez-rag, users who work with an agentic coding tool (Claude Code, OpenCode, or any generic agent) must manually copy a `SKILL.md` file to the correct location for their tool. The correct location and frontmatter format differ by tool, and users must know both. There is no automated way to install or refresh the skill after upgrading ez-rag.

## Solution

Add an `install-skill` subcommand that auto-detects which agentic coding tools are present and installs the bundled ez-rag skill to the correct location for each detected tool, with the correct frontmatter. The command supports a `--global` flag (install into the user's home-level skill directory) and a repeatable `--tool` flag (override detection). The `init` command gains an `--install-skill` flag that runs skill installation as part of workspace initialisation.

## User Stories

1. As a developer, I want to run `ez-rag install-skill` in my project and have the skill installed for my AI coding tool automatically, so that I do not have to know where or how to place the file manually.
2. As a developer using Claude Code, I want the skill installed at `.claude/skills/ez-rag/SKILL.md`, so that Claude Code picks it up automatically.
3. As a developer using OpenCode, I want the skill installed at `.agents/skills/ez-rag/SKILL.md`, so that OpenCode can discover and use it.
4. As a developer using a generic agent, I want the skill installed at `.agents/skills/ez-rag/SKILL.md` even when no known tool is detected, so that any SKILL.md-compatible agent can use it.
5. As a developer, I want to install the skill globally with `--global`, so that it is available in every project on my machine without repeating the install.
6. As a developer using Claude Code globally, I want `--global` to install to `~/.claude/skills/ez-rag/SKILL.md`, so that Claude Code picks it up across all projects.
7. As a developer using OpenCode globally, I want `--global` to install to `~/.config/opencode/skills/ez-rag/SKILL.md`, so that OpenCode picks it up across all projects.
8. As a developer using a generic agent globally, I want `--global` to install to `~/.agents/skills/ez-rag/SKILL.md`, so that any agent can find it.
9. As a developer, I want to force installation for a specific tool with `--tool claude-code`, so that I can install the skill even when the tool's config directory is not present.
10. As a developer, I want `--tool` to be repeatable (e.g. `--tool claude-code --tool opencode`), so that I can install for multiple tools in one command.
11. As a developer, I want the command to print `Installed ez-rag skill for <tool>: <path>` for each new file, so that I can confirm where the skill was placed.
12. As a developer, I want the command to print `Updated ez-rag skill for <tool>: <path>` when the file already existed, so that I know the skill was refreshed to the latest version.
13. As a developer, I want re-running `ez-rag install-skill` after upgrading ez-rag to overwrite the previous skill file, so that the skill stays in sync with the installed version.
14. As a developer, I want parent directories to be created automatically if they do not exist, so that the command works in a fresh project with no existing AI-tool config directories.
15. As a developer initialising a new project, I want to run `ez-rag init --install-skill` and have both the `.ez-rag/` workspace and the skill installed in a single command, so that setup is a one-liner.
16. As a developer, I want `ez-rag init` without `--install-skill` to print a tip suggesting `ez-rag install-skill`, so that I discover the command without reading documentation.
17. As a developer, I want the Claude Code skill to include `allowed-tools: [Bash]` in its frontmatter, so that Claude Code knows which tools the skill requires.
18. As a developer using OpenCode or a generic agent, I want the skill frontmatter to contain only `name` and `description`, so that unknown fields do not confuse the agent runtime.
19. As a developer working in a project with both `.claude/` and `.opencode/` present, I want the skill installed for both tools automatically, so that whichever tool I use picks it up.
20. As a developer, I want the `.agents/` directory to never be used as a detection signal for OpenCode, so that a generic agents directory does not trigger OpenCode-specific installation.

## Implementation Decisions

### Module: `AgentToolDetector`

A new, pure detection module:
- Input: project directory, home directory, explicit tool list (empty = auto-detect)
- Output: ordered list of `AgentTool` values to install for
- Enum: `AgentTool { CLAUDE_CODE, OPENCODE, GENERIC }`
- Detection rules:
  - `CLAUDE_CODE`: `<home>/.claude/` exists OR `<projectDir>/.claude/` exists
  - `OPENCODE`: `<home>/.config/opencode/` exists OR `<projectDir>/.opencode/` exists
  - `GENERIC`: fallback when neither `CLAUDE_CODE` nor `OPENCODE` is detected
  - `.agents/` is never a detection signal
- When explicit tools are provided: returns them as-is, bypassing detection entirely

### Module: `SkillInstaller`

A new installation module:
- Input: `AgentTool`, `isGlobal: Boolean`, project directory, home directory
- Output: `SkillInstallResult(path: Path, wasUpdated: Boolean)`
- Reads skill body from a classpath resource (`skills/ez-rag-skill-body.md`), which contains no frontmatter
- Assembles the full file by prepending tool-specific frontmatter:
  - `CLAUDE_CODE`: `name`, `description`, `allowed-tools: [Bash]`
  - `OPENCODE`, `GENERIC`: `name`, `description` only
- Install path resolution:
  - `CLAUDE_CODE` project: `<projectDir>/.claude/skills/ez-rag/SKILL.md`
  - `CLAUDE_CODE` global: `<home>/.claude/skills/ez-rag/SKILL.md`
  - `OPENCODE` project: `<projectDir>/.agents/skills/ez-rag/SKILL.md`
  - `OPENCODE` global: `<home>/.config/opencode/skills/ez-rag/SKILL.md`
  - `GENERIC` project: `<projectDir>/.agents/skills/ez-rag/SKILL.md`
  - `GENERIC` global: `<home>/.agents/skills/ez-rag/SKILL.md`
- Always overwrites; sets `wasUpdated = true` when the file already existed before the write
- Creates parent directories as needed

### Module: `InstallSkillCommand`

New CLI subcommand registered in `EzRagCommand`:
- **Name**: `install-skill`
- **Options**: `--global` (Boolean, default false), `--tool` (String, repeatable)
- **Logic**: resolves project dir (cwd), home dir; calls `AgentToolDetector` then `SkillInstaller` for each detected tool; prints one line per result; prints generic-fallback notice when no tool was detected
- **Output**:
  - New file: `Installed ez-rag skill for <tool>: <path>`
  - Existing file: `Updated ez-rag skill for <tool>: <path>`
  - Generic fallback preamble: `No known agentic coding tools detected, installing generic skill.`
- **Exit code**: 0 on success; 1 with error message on I/O failure

### Module: `InitCommand` (modified)

- Add `--install-skill` (Boolean, default false), `--global` (Boolean, default false), `--tool` (String, repeatable) options
- Without `--install-skill`: after initialising the workspace, prints `Run 'ez-rag install-skill' to install the skill for your AI coding tool.`
- With `--install-skill`: after initialising the workspace, delegates to `SkillInstaller` exactly as `InstallSkillCommand` does

### Resource: skill body template

A new classpath resource containing the ez-rag skill body (markdown instructions, no YAML frontmatter). This is the source of truth for skill content; the existing `.claude/skills/ez-rag/SKILL.md` in the project is regenerated from it.

### Registration

`InstallSkillCommand` added to `EzRagCommand.subcommands`.

## Testing Decisions

**What makes a good test**: tests assert on externally observable behaviour — files written to disk, their content, printed output, exit codes — not on internal implementation details like which method assembled the frontmatter string. Tests use real filesystem operations on `@TempDir` to verify actual file placement and content.

### `AgentToolDetector`

- Prior art: `InitCommandTest` — uses `@TempDir` and creates/omits directories to drive logic
- Tests cover: Claude Code detected via home dir; Claude Code detected via project dir; OpenCode detected via home dir; OpenCode detected via project dir; generic fallback when neither present; both tools detected when both present; `.agents/` in project does not trigger OpenCode; explicit `--tool` list bypasses detection

### `SkillInstaller`

- Prior art: `InitCommandTest` — inspects file contents written to `@TempDir`
- Tests cover: correct path for each tool × scope combination (6 paths); file created when it does not exist (`wasUpdated = false`); file overwritten when it exists (`wasUpdated = true`); parent directories created; frontmatter contains `allowed-tools` for Claude Code; frontmatter does not contain `allowed-tools` for OpenCode/generic; skill body content present in written file

### `InstallSkillCommand`

- Prior art: `InitCommandTest` — constructs command with overrides, asserts on `StringWriter` output and return code
- Tests cover: output line per tool installed; `Installed` vs `Updated` wording; generic fallback message printed; `--global` routes to home dir paths; `--tool` overrides detection; exit code 0 on success

### `InitCommand` (updated)

- Prior art: existing `InitCommandTest`
- Tests cover: tip message printed when `--install-skill` not set; no skill file written when `--install-skill` not set; skill file written when `--install-skill` set; `--global` and `--tool` passed through correctly when used with `--install-skill`

## Out of Scope

- Uninstalling a previously installed skill.
- Listing installed skills.
- Version-pinning or rollback of skill files.
- Installing skills for tools other than Claude Code, OpenCode, and generic.
- Updating the skill file when ez-rag itself is upgraded (user must re-run `install-skill`).
- MCP server exposure of skill installation.
- Any changes to the ingestion pipeline, search, or repository.

## Further Notes

- The `.agents/` directory is a write target (for OpenCode project-level and generic project-level installs) but is never read as a detection signal. This prevents a pre-existing `.agents/` directory from inadvertently triggering OpenCode-specific installation.
- OpenCode and generic produce identical project-level output (`<projectDir>/.agents/skills/ez-rag/SKILL.md` with `name`+`description` frontmatter). If both are detected simultaneously, the second write is effectively a no-op. This is intentional and harmless.
- The skill body resource is the single source of truth. The project-level `.claude/skills/ez-rag/SKILL.md` committed to the ez-rag repo is generated from it and should be kept in sync.
