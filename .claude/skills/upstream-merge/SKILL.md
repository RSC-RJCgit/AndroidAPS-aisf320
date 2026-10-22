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

4. **Fallback tag**, and for a big merge a **trial branch**. No CI change is needed:
   ```bash
   git tag pre-merge-YYYYMMDD
   git switch -c merge-dev-YYYYMMDD     # big merge only (dozens of conflicts, or client control / pairing changes)
   ```
   A tag is a clean undo only until the merge is pushed. After that the way back is a force-push (rewrites the
   branch the phones' builds come from) or `git revert -m 1` (git then thinks the upstream commits are merged, and
   the next merge silently leaves them out). A trial branch avoids both: Branch CI builds every pushed branch, the
   user tests it, then `git merge --ff-only merge-dev-YYYYMMDD` on the merge branch, or the branch is dropped.
   CI names APKs and Drive folders from `appVersion`, not the branch, so tell the user the trial APKs can land
   next to the normal ones.

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
   - `git checkout --conflict=diff3 -- <files>` adds the common base to each block, which shows which side changed
     what. A script that rewrites a file must keep its CRLF line endings (read and write with `newline=""`).
   - After the conflicts, look for what merged cleanly but no longer fits: names upstream renamed that our own
     code still uses (grep the old name), and `when` over a sealed type that must list a case only we added (for
     example our `ClientControlMessage.AutomationStateSet` in upstream's `requiredRole`).

7. **Prove it.** Only when the user agrees: a local Gradle build locks Android Studio's files, and the user
   normally lets CI check the commit after pushing it. Use redirect, not a pipe, so the exit code is real.
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
