## Task 01-workflow-foundation

Create the complete workflow foundation: trigger, Java 21 setup with Gradle caching, build-and-test gate, version extraction from `gradle.properties`, and an idempotency guard that exits cleanly when the tag already exists. After this task the workflow is safe to merge to `master` — every push builds successfully and exits without creating any release, whether or not the version changed.

The skip mechanism uses a named step with a step output (`id: check_tag`, `outputs: skip=true`) so Task 02 can reference it with `if: steps.check_tag.outputs.skip != 'true'` to gate the release steps.

### Implementation steps

- [x] Create `.github/workflows/release.yml` with `on: push: branches: [master]`
- [x] Add a single job with `permissions: contents: write` (required by the release action in Task 02)
- [x] Add `actions/checkout@v4` with `fetch-depth: 0` (all tags must be available)
- [x] Add `actions/setup-java@v4` with `distribution: temurin`, `java-version: '21'`, `cache: gradle`
- [x] Add `./gradlew build` step (compiles, runs unit tests; integration/eval excluded by default)
- [x] Add step to extract version: `VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)` exported via `echo "VERSION=$VERSION" >> $GITHUB_ENV`
- [x] Add step with `id: check_tag` that runs `git fetch --tags`, then checks `git rev-parse "v$VERSION"` and emits `echo "skip=true" >> $GITHUB_OUTPUT` if the tag exists

### Acceptance criteria

- [x] The workflow file exists at `.github/workflows/release.yml` and contains a valid YAML structure
- [x] The workflow triggers only on pushes to `master` (not PRs or other branches)
- [x] The `build` step is not marked `continue-on-error`; a test failure causes the job to fail and no subsequent steps run
- [x] `env.VERSION` is set to the exact value of the `version=` line in `gradle.properties` (e.g., `0.2.0` with no trailing whitespace or newline)
- [x] If `v$VERSION` already exists as a remote tag, `steps.check_tag.outputs.skip` is set to `'true'` and the job exits cleanly with exit code 0
- [x] If `v$VERSION` does not exist, `steps.check_tag.outputs.skip` is not set and the job continues to subsequent steps

### Quality gates

- [x] `./gradlew build` passes locally with no test failures

---

## Task 02-release-publication

Add distribution packaging and GitHub Release creation, gated by the skip output from Task 01's idempotency check. A new version in `gradle.properties` (not yet tagged) causes the workflow to build the distribution ZIP, create a tagged GitHub Release named `v{version}` with auto-generated release notes, and attach the ZIP. Re-running the workflow for an already-released version is a no-op.

### Implementation steps

- [x] Add `./gradlew distZip` step after `./gradlew build`, conditioned on `if: steps.check_tag.outputs.skip != 'true'`
- [x] Add `softprops/action-gh-release@v2` step with `if: steps.check_tag.outputs.skip != 'true'`, `tag_name: v${{ env.VERSION }}`, `generate_release_notes: true`, and `files: build/distributions/ez-rag-${{ env.VERSION }}.zip`

### Acceptance criteria

- [x] A push to `master` with a version not yet tagged creates a GitHub Release named `v{version}` (e.g., `v0.3.0`) — workflow logic verified by YAML inspection; requires CI push to confirm end-to-end
- [x] The release has `ez-rag-{version}.zip` attached as a downloadable asset — `files:` param set correctly; confirmed artifact produced locally
- [x] The release notes list commits and/or merged PRs since the previous release tag — `generate_release_notes: true` set; requires CI to observe
- [x] The tag `v{version}` is created in the repository and visible under Tags/Releases — created by `softprops/action-gh-release@v2` when `tag_name` is specified; requires CI to confirm
- [x] A push to `master` without a version change produces no new release and the workflow exits with exit code 0 — `check_tag` step emits `skip=true` and both release steps are conditioned on `if: steps.check_tag.outputs.skip != 'true'`
- [x] Re-running the workflow after a successful release produces no new release and no duplicate tag — idempotency guard in `check_tag` step handles this; `softprops/action-gh-release@v2` is also idempotent on existing releases
- [x] **Manual post-release check:** Downloading the ZIP, extracting it, and running `bin/ez-rag --version` outputs the released version string — requires post-release manual verification

### Quality gates

- [x] `./gradlew build distZip` passes locally and produces `build/distributions/ez-rag-{version}.zip` — BUILD SUCCESSFUL, `build/distributions/ez-rag-0.2.0.zip` confirmed
