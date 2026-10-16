---
name: upstream-merge
description: Merge upstream nightscout/AndroidAPS dev into the KMP merge branch. Use after upstream publishes a new tag (4.0.0-beta1, 4.0.0-rc1, ...), or whenever the user asks to merge with dev.
---

# Merging upstream dev

The KMP fork follows upstream 4.0 as it moves from beta to rc to release. After each new upstream tag, merge
upstream `dev` into the merge branch. Merge often: small merges have few conflicts, and git remembers how each
conflict was solved.

## Names in this repo

- Upstream remote: **`Master3423`** = https://github.com/nightscout/AndroidAPS.git (not `upstream`).
- Merge branch: `test-beta1-dev-20261013`, worktree `AaAPS-kmp-beta1merge`.
- The worktree shares its git folder with the UKF repo (`AaAPS3422a320/.git`), so git settings and the rerere
  cache are shared too.

## Steps

1. **Fetch and look.**
   ```bash
   git fetch Master3423 dev --tags
   git ls-remote --tags Master3423 | grep 4.0
   git rev-list --count HEAD..Master3423/dev
   ```
   The working tree must be clean (`git status`). Ask the user to commit first if it is not.

2. **Dry run.** Lists the files that will conflict, without touching the working tree:
   ```bash
   git merge-tree --write-tree --name-only --no-messages HEAD Master3423/dev
   ```
   Show the user the commit count and the conflict list before going on.

3. **Remember resolutions.** Once per clone (already set here, check it):
   ```bash
   git config rerere.enabled true
   ```
   git then stores how each conflict was solved and solves the same conflict the same way in the next merge.

4. **Fallback tag** instead of a new branch or worktree. No CI change is needed:
   ```bash
   git tag pre-merge-YYYYMMDD
   ```
   If the merge goes badly: `git reset --hard pre-merge-YYYYMMDD`.

5. **Merge, without committing.**
   ```bash
   git merge --no-commit Master3423/dev
   ```
   Files rerere solved show as "Resolved ... using previous resolution". Check them anyway.

6. **Solve the rest.** Keep both sides: our features (AutoISF, graphs, read-only client, sync fixes, desktop
   fixes, ...) and upstream's changes. Read both sides before choosing; never take one side whole without reading it.
   - `buildSrc/src/main/kotlin/Versions.kt`: keep the fork's current `appVersion` (`4.0.0-rc1` since 2026-10-16).
     Upstream's `dev` always carries a `-dev-x` name, never use that. When upstream publishes a new tag (rc2, the
     release), ask the user whether the fork follows the tag name. If it does, also add the name to the `tagName`
     choices in `.github/workflows/aaps-ci.yml`. CI derives the APK names and Drive folders from `appVersion`.
   - After solving a file, `git add` it, so rerere records the answer.
   - `git diff --name-only --diff-filter=U` must end empty.

7. **Prove it.** Use redirect, not a pipe, so the exit code is real.
   - Android: `./gradlew.bat assembleFullDebug --no-daemon > merge-build.log 2>&1` (and `assembleAapsclient2Debug`).
   - Desktop: `./gradlew.bat :desktop:shell:compileKotlin --no-daemon`.
   - iOS klibs: `./gradlew.bat compileKotlinIosArm64 --no-daemon` (the tests only run on the Mac).
   - Unit tests of every module with a conflict: `./gradlew.bat :<module>:testAndroidHostTest --no-daemon`.
   - Compare failing tests with the `pre-merge-YYYYMMDD` tag before calling a failure new.

8. **Hand over.** Leave the merge staged and not committed. The user reviews, commits and pushes. Report the
   upstream range merged, every conflict and how it was solved, and the build and test results.

## Notes

- Do not rebase the merge branch: it is pushed and CI builds from it.
- The Windows/macOS installer workflow (`.github/workflows/windows-msi.yml`) and Branch CI run on the push.
