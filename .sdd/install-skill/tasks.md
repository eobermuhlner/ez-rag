# Tasks: `install-skill` command

## Task [01-install-skill-project-level]

Add the `install-skill` subcommand with full auto-detection and project-level installation for all three supported tools (Claude Code, OpenCode, generic fallback). This is the foundational end-to-end slice: it bundles the skill body as a classpath resource, detects which tools are present, selects the correct install path and frontmatter for each, writes the `SKILL.md` files, and prints one output line per result.

Detection rules (project-level only in this task; global detection markers are introduced in Task 02):
- `CLAUDE_CODE`: `.claude/` exists in the current project directory
- `OPENCODE`: `.opencode/` exists in the current project directory
- `GENERIC`: fallback when neither of the above is detected
- `.agents/` in the project directory is never a detection signal

Install paths (project-level):
- `CLAUDE_CODE`: `.claude/skills/ez-rag/SKILL.md`
- `OPENCODE`: `.agents/skills/ez-rag/SKILL.md`
- `GENERIC`: `.agents/skills/ez-rag/SKILL.md`

### Implementation steps

- [x] Add `src/main/resources/skills/ez-rag-skill-body.md` containing the skill body without frontmatter
- [x] Implement `AgentToolDetector` (project-dir detection only; returns ordered list of `AgentTool` values)
- [x] Implement `SkillInstaller` for project-level paths (all three tools); reads body from classpath resource; assembles frontmatter; returns `SkillInstallResult(path, wasUpdated)`
- [x] Implement `InstallSkillCommand` (no flags yet); orchestrates detection, installation, output
- [x] Register `InstallSkillCommand` in `EzRagCommand.subcommands`
- [x] Write tests for `AgentToolDetector` (project-dir only)
- [x] Write tests for `SkillInstaller` (project-level paths)
- [x] Write tests for `InstallSkillCommand` (output and exit code)

### Acceptance criteria

- [x] `install-skill` in a project with `.claude/` writes `.claude/skills/ez-rag/SKILL.md` and prints `Installed ez-rag skill for claude-code: .claude/skills/ez-rag/SKILL.md`
- [x] The Claude Code `SKILL.md` frontmatter contains `allowed-tools: [Bash]`
- [x] The Claude Code `SKILL.md` frontmatter contains `name: ez-rag` and a non-empty `description`
- [x] `install-skill` in a project with `.opencode/` writes `.agents/skills/ez-rag/SKILL.md` with frontmatter containing only `name` and `description` (no `allowed-tools`)
- [x] `install-skill` in a project with neither `.claude/` nor `.opencode/` writes `.agents/skills/ez-rag/SKILL.md` and prints `No known agentic coding tools detected, installing generic skill.` followed by the install line
- [x] `.agents/` existing alone in the project directory does NOT trigger OpenCode detection (generic fallback is used instead)
- [x] Both `.claude/` and `.opencode/` present → both files written, two output lines printed
- [x] Re-running prints `Updated ...` instead of `Installed ...` for files that already existed
- [x] Parent directories are created automatically when they do not exist
- [x] The written `SKILL.md` contains non-empty skill body text below the frontmatter
- [x] Exit code is 0 on success

### Quality gates

- [x] `./gradlew build` produces no compiler warnings
- [x] `./gradlew test` passes with no failures

---

## Task [02-install-skill-global-detection-and-flag]

Extend `install-skill` with home-directory detection markers and a `--global` flag. Home-dir markers (`~/.claude/`, `~/.config/opencode/`) act as additional detection signals alongside the project-dir markers from Task 01. The `--global` flag switches install paths from project-level to user-level for all tools.

Global install paths:
- `CLAUDE_CODE`: `~/.claude/skills/ez-rag/SKILL.md`
- `OPENCODE`: `~/.config/opencode/skills/ez-rag/SKILL.md`
- `GENERIC`: `~/.agents/skills/ez-rag/SKILL.md`

### Implementation steps

- [x] Extend `AgentToolDetector` to also check home-dir markers (`~/.claude/`, `~/.config/opencode/`)
- [x] Extend `SkillInstaller` with global path resolution for all three tools
- [x] Add `--global` flag to `InstallSkillCommand`; pass `isGlobal` to `SkillInstaller`
- [x] Write tests for home-dir detection in `AgentToolDetector`
- [x] Write tests for global path resolution in `SkillInstaller`
- [x] Write tests for `--global` flag in `InstallSkillCommand`

### Acceptance criteria

- [x] `install-skill` in a project where only `~/.claude/` exists (no project-level `.claude/`) detects Claude Code and writes to the project-level path `.claude/skills/ez-rag/SKILL.md`
- [x] `install-skill` in a project where only `~/.config/opencode/` exists detects OpenCode and writes to `.agents/skills/ez-rag/SKILL.md`
- [x] `--global` with Claude Code detected writes to `<home>/.claude/skills/ez-rag/SKILL.md`
- [x] `--global` with OpenCode detected writes to `<home>/.config/opencode/skills/ez-rag/SKILL.md`
- [x] `--global` with no tool detected (generic fallback) writes to `<home>/.agents/skills/ez-rag/SKILL.md`
- [x] Without `--global`, project-level paths from Task 01 are unchanged
- [x] Exit code is 0 on success

### Quality gates

- [x] `./gradlew build` produces no compiler warnings
- [x] `./gradlew test` passes with no failures

---

## Task [03-install-skill-tool-flag]

Add a repeatable `--tool <name>` flag to `install-skill`. When any `--tool` value is present, auto-detection is skipped entirely. The command installs for exactly the specified tools, creating directories as needed even when the tool's config directory does not yet exist. Valid tool names: `claude-code`, `opencode`, `generic`. Invalid names produce an error message and exit code 1.

### Implementation steps

- [x] Update `AgentToolDetector` to accept an explicit tool list; return that list as-is when non-empty (bypassing detection)
- [x] Add repeatable `--tool` option to `InstallSkillCommand`; pass explicit list to `AgentToolDetector`
- [x] Write tests for explicit-list path in `AgentToolDetector`
- [x] Write tests for `--tool` flag in `InstallSkillCommand`

### Acceptance criteria

- [x] `--tool claude-code` writes `.claude/skills/ez-rag/SKILL.md` even when neither `.claude/` nor `~/.claude/` exist
- [x] `--tool claude-code --tool opencode` writes both paths and prints two output lines
- [x] When `--tool` is specified, the generic fallback message is NOT printed
- [x] `--tool generic` writes `.agents/skills/ez-rag/SKILL.md` explicitly, with the generic-fallback notice suppressed (tool was explicitly requested, not a fallback)
- [x] An unrecognised tool name (e.g. `--tool foobar`) prints `Error: unknown tool 'foobar'` and exits with code 1
- [x] `--tool` and `--global` can be combined; path resolves to the global location for the specified tool

### Quality gates

- [x] `./gradlew build` produces no compiler warnings
- [x] `./gradlew test` passes with no failures

---

## Task [04-init-install-skill-integration]

Update the `init` command to print a tip suggesting `ez-rag install-skill` after workspace initialisation, and add an `--install-skill` flag that runs skill installation inline. The tip is printed unconditionally (regardless of whether any agentic coding tool is detected). The `--install-skill` flag accepts the same `--global` and `--tool` options as `install-skill`.

### Implementation steps

- [x] Add unconditional tip message to `InitCommand.call()` after workspace initialisation
- [x] Add `--install-skill` (Boolean), `--global` (Boolean), `--tool` (repeatable String) options to `InitCommand`
- [x] When `--install-skill` is set, delegate to `SkillInstaller` (via `AgentToolDetector`) after workspace init
- [x] Write new tests for tip message and `--install-skill` behaviour
- [x] Verify all pre-existing `InitCommand` tests still pass

### Acceptance criteria

- [x] `ez-rag init` output contains the text `ez-rag install-skill` (tip is unconditional)
- [x] `ez-rag init` does NOT write any `SKILL.md` file
- [x] `ez-rag init --install-skill` writes `SKILL.md` for the auto-detected tool and prints the install line(s)
- [x] `ez-rag init --install-skill --global` writes to the global path for the detected tool
- [x] `ez-rag init --install-skill --tool opencode` writes `.agents/skills/ez-rag/SKILL.md` regardless of what is detected
- [x] All pre-existing `InitCommand` tests pass without modification

### Quality gates

- [x] `./gradlew build` produces no compiler warnings
- [x] `./gradlew test` passes with no failures (including all pre-existing `InitCommand` tests)
