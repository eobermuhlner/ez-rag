## Problem Statement

Every time the version in `gradle.properties` is bumped and merged to `master`, a developer must manually build the distribution archive, create a git tag, and publish a GitHub Release. This manual step is error-prone, easy to forget, and creates a gap between the committed version and the published artifact.

## Solution

A GitHub Actions workflow automatically detects when a new version has been committed to `master` and, if no release for that version already exists, builds the project, runs the test suite, and publishes a GitHub Release containing the distribution archive and auto-generated release notes.

## User Stories

1. As a maintainer, I want releases to be published automatically when the version changes, so that I don't have to remember to do it manually after every merge.
2. As a maintainer, I want the pipeline to run tests before publishing a release, so that a broken build is never shipped to users.
3. As a maintainer, I want the pipeline to be a no-op when the version has not changed, so that routine commits to `master` don't produce duplicate or spurious releases.
4. As a maintainer, I want the release to be tagged `v{version}` in git, so that released versions are traceable in the repository history.
5. As a maintainer, I want the distribution archive attached to the release, so that users can download and run the tool without needing to build it themselves.
6. As a maintainer, I want release notes to be auto-generated from commits and merged PRs since the previous tag, so that users can see what changed in each release without me writing a changelog manually.
7. As a user, I want to download a ready-to-run distribution ZIP from the GitHub Releases page, so that I can install the tool without a build environment.
8. As a maintainer, I want to be able to re-run the workflow safely, so that transient failures (e.g. network blips during artifact upload) can be retried without creating duplicate releases.

## User Acceptance Tests

1. Given the version in `gradle.properties` has been bumped to a value that has no corresponding git tag, when a commit is pushed to `master`, then a new GitHub Release named `v{version}` is created with the distribution ZIP attached.
2. Given a GitHub Release for the current version already exists, when a commit is pushed to `master` without changing the version, then no new release or tag is created.
3. Given one or more unit tests are failing, when a commit is pushed to `master` with a new version, then the workflow fails before creating a release and no GitHub Release is published.
4. Given a release has been published, when a user downloads the attached ZIP, extracts it, and runs `bin/ez-rag --version`, then the output matches the released version number.
5. Given a release has been published, when a user opens the release on GitHub, then the release notes list the commits or merged pull requests since the previous release tag.

## Definition of Done

- A GitHub Actions workflow file exists in the repository under `.github/workflows/`.
- Pushing a version-bumped commit to `master` automatically produces a tagged GitHub Release with the distribution ZIP attached.
- Pushing a commit without a version change produces no release and does not fail the workflow.
- A build failure (test failure or compilation error) prevents a release from being published.
- The release is idempotent: re-running the workflow for an already-released version is a safe no-op.
- Release notes are auto-generated and visible on the GitHub Releases page.
- No developer action beyond merging to `master` is required to publish a release.

## Out of Scope

- Building platform-native binaries or installers (`.exe`, `.dmg`, `.deb`).
- Publishing to package registries (Maven Central, Homebrew, SDKMAN, etc.).
- Maintaining a hand-written `CHANGELOG.md`.
- Staging or pre-release environments.
- Signing or notarizing release artifacts.
- Sending release notifications (Slack, email, etc.).

## Further Notes

The `CLAUDE.md` instruction states that git tags are created manually at release time. This feature intentionally changes that convention: the pipeline will create and push the tag automatically. The version number itself remains a manual step (bumped in `gradle.properties` before committing), keeping developers in control of when a release happens.

---

## Technical Annex
> Written against codebase as of: 2026-06-15

### Architectural Decisions

**Single workflow file:** The entire release pipeline lives in `.github/workflows/release.yml`. No reusable workflows or composite actions are needed at this scale.

**Trigger:** `on: push: branches: [master]` — fires on every push to `master`, including merged pull requests.

**Version detection (tag-based):** The workflow reads the version from `gradle.properties` using a shell one-liner:

```bash
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
```

It then checks whether the tag `v$VERSION` already exists in the remote:

```bash
git fetch --tags
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "Tag v$VERSION already exists — skipping release."
  exit 0
fi
```

This approach is idempotent: re-running the workflow for an already-tagged version is a safe no-op.

**Java setup:** `actions/setup-java@v4` with `distribution: temurin` and `java-version: 21`, matching the project's toolchain requirement. Gradle caching enabled via the `cache: gradle` option.

**Build step:** `./gradlew build` — this compiles, runs unit tests (integration and eval tags excluded by default per `build.gradle.kts`), and packages. If this step fails, the workflow stops and no release is created.

**Artifact step:** `./gradlew distZip` produces `build/distributions/ez-rag-{version}.zip`. The exact path is `build/distributions/ez-rag-$VERSION.zip`.

**Release creation:** Use `softprops/action-gh-release@v2` (well-maintained third-party action):

```yaml
- uses: softprops/action-gh-release@v2
  with:
    tag_name: v${{ env.VERSION }}
    generate_release_notes: true
    files: build/distributions/ez-rag-${{ env.VERSION }}.zip
```

**Permissions:** The workflow job needs `permissions: contents: write` to push the tag and create the release. The built-in `GITHUB_TOKEN` is sufficient — no additional secrets required.

**Tag push:** The `softprops/action-gh-release` action creates the tag automatically when `tag_name` is specified and does not yet exist. No separate `git tag` + `git push` step is needed.

**Checkout depth:** `actions/checkout@v4` with `fetch-depth: 0` to ensure all existing tags are available for the existence check.

### Automated Testing Decisions

The release pipeline is a GitHub Actions workflow, not application code. It cannot be unit-tested locally. Verification is by end-to-end observation:

- **Manual acceptance test (pre-merge):** Push a branch that bumps the version, open a PR, merge to `master`, and confirm the workflow creates a release. Confirm re-running the workflow on the same version is a no-op.
- **No unit tests are written for the workflow itself.**
- Existing unit tests (`./gradlew test`) act as a quality gate within the pipeline — they must all pass before a release is published.

Prior art for the build command: `./gradlew build` is already the standard test command used in `CLAUDE.md`.
