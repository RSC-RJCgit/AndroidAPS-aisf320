# Session notes — 2026-07-15/16

Fork: `AaAPS3422a320`, branch `TDDautos2` (AAPS 3.4.2.1 + aisf 3.2.0 + TDD work).
Session covered AU264 → AU276next, two standalone patches, crash fixes, the delayed-bolus
redesign, and several utility scripts.

---

## Version ledger

| Version | Contents |
|---|---|
| AU264 | Import summary: pump-domain detection + keep-pump checkbox (checkboxes clipped in scroll pane — bug found on-phone) |
| AU265 | Import: checkboxes moved out of scroll pane (always visible); diagnostic log line |
| AU266 | Import: keep name / BG source / Sync options always shown (virtual-pump gate removed); core-key preservation (`NsClient*`, `BgSource*`, `Tidepool*`, `OpenHumans*`, `Xdrip*`) |
| AU267 | `TriggerSmbDeliveryRatio` DI binding (trigger-chooser crash fix); export-password cancel fix |
| AU268 | `ActionSetSmbDeliveryRatio` DI binding (add-automation crash fix) |
| AU269–271 | (user rounds: cccker, lastCarb …) incl. carbs-OR migration + presets |
| AU272 | smbDEL: AcceUp0.5 guards migration; `DetermineBasalAutoISF` typo fix (`rT.r eason`) |
| AU273 | Delayed bolus: zero-immediate-dose case now schedules the delayed check |
| AU274 | Delayed bolus: careportal notes `Db0` / `Db10` / `Db20` / `Db30` |
| AU275 | Delayed-bolus redesign + full rename (see below) |
| AU276next | `PrepareTreatmentsDataWorker` marker fix (graph yellow-pair detection matches new + legacy note text) |

---

## 1. Settings import — "keep current settings" (rounds 3–4, confirmed working)

- **Checkboxes invisible on AU264**: they were inside the dialog's fixed-height (210dp)
  `NestedScrollView`, below the metadata table — present but clipped, no scroll hint.
  Fix: restructure `dialog_alert_import_summary.xml` — scroll pane holds only table +
  details button; the four checkboxes sit below it, always visible.
- **Sync leak**: "Keep Synchronization" preserved only the plugin *selection*; NS URL/
  password/receive-flags were imported anyway. Cause: NSClient settings are **core keys**
  (`core/keys` `BooleanKey.NsClient*`, `StringKey.NsClientUrl/ApiSecret/AccessToken`, …),
  invisible to the plugin-ownership matcher. Fix: `coreKeysByNamePrefix(...)` — Keep Sync
  adds all `NsClient*/Tidepool*/OpenHumans*/Xdrip*` core keys; Keep BG source adds
  `BgSource*`. Verified: every NS-related core key's enum name starts with `NsClient`
  (incl. `NsClient3UseWs`, `NsClientSecondary*`).
- **Pod-phone visibility**: keep name/BG source/sync were gated on
  `activePlugin.activePump is VirtualPump` ("follower options"). Gate removed — the three
  always show when import is possible. "Keep pump config" still appears only when the
  import would actually change the pump domain (on a pod phone: essentially always,
  pre-checked when a live session would be disturbed).
- Primary/secondary NS use distinct keys; import maps key-to-key — imported main-NS values
  always come from the exporting phone's main config.
- Confirmed working on-phone (name, virtual pump, BG source, Sync incl. secondary).
- Details: `settings_import_keep_pump_config.md` (rounds 1–4 + tooling notes).

## 2. Standalone patch #1 — import feature for vanilla 3421

- Fresh shallow clone of `nightscout/AndroidAPS` tag `3.4.2.1` →
  `StudioProjects3421\AAPS3421-vanilla` (branch `import-keep-current-3421`, commit `44801ee`).
- Port notes: vanilla has **no `LongKey.kt`** (dropped from reflection list), no
  automation-states / AapsDirectory blocks (omitted). Dialog + layout copied verbatim
  (diff was purely the feature).
- Patch: `StudioProjects3421\0001-Import-settings-options-to-keep-current-pump-name-BG.patch`
  — verified `git apply --check` + real `git am` on pristine worktree
  (`AAPS3421-pristine-check`, kept for test-APK builds).
- Build environment on this PC: **JDK = `C:\Users\arjay\.jdks\openjdk-23.0.2`**
  (PATH java is ancient; AS-bundled jbr broken — missing jvm.cfg; jbr-17 can't compile
  Java-21 sources). AAPS refuses to build with uncommitted changes (commit first).
  `local.properties` must be copied in (gitignored).

## 3. Automation crashes — missing Dagger bindings (AU256 legacy)

- Crash buffer (`adb logcat -b crash -d`) showed repeated
  `IllegalArgumentException: No injector factory bound for Class<TriggerSmbDeliveryRatio>`
  (trigger chooser + background `AutomationPluginHandler`), later the same for
  `ActionSetSmbDeliveryRatio` (action chooser, i.e. "add automation").
- Root cause: commit `4b61d71a29 320TDD2AU256DelRatio` added both classes to
  `AutomationPlugin`'s rosters but never registered them in `AutomationModule`
  (`@ContributesAndroidInjector`). The choosers instantiate **every** roster class to build
  their menus, so they crashed for *any* automation being added — regardless of type.
  Editing existing automations never touched the unbound classes, hence "edit ok".
  A missing binding is a runtime failure only — no compile error, silent skip path.
- Fixes: bindings added (AU267 trigger, AU268 action). Guard against recurrence:
  **`check_di_bindings.ps1`** (repo root) — fails with a list if any `Trigger*`/`Action*`
  class is absent from `AutomationModule.kt`; run before builds after adding automations.

## 4. Automation data migrations (one-shot, marker-guarded, in `AutomationPlugin`)

- **Carbs-OR pairing** (`CarbsAgoMigrationDone`): every stored `Last bolus ago` condition
  is replaced in place by `OR(Last bolus ago, Last carbs)` with identical minutes and
  comparator; nested groups handled; groups already containing a carbs trigger skipped.
  One-shot by design: later user edits are never overridden. Same OR-pairs applied to all
  seven `TriggerBolusAgo` instances in `AutomationPresets.kt` (note: `registerAll` is
  **disabled** — presets are reference-only; live automations were recreated manually in
  the UI and are covered by the migration).
- **AcceUp0.5 guards** (`AcceUpGuardsMigrationDone`): adds `Delta >= 0` and
  `State LowBG=NO50rec` to the automation titled `AcceUp0.5` — closes the night-time
  re-boost hole where Extra50%'s accel-weight cut (0.07) re-armed AcceUp during a fall.
- Mechanism: migrations run in `onStart` after `loadFromSP()`, mutate the same objects the
  UI edits, `storeToSP()` — identical path to manual edits. Import cooperates (flags are
  exportable; unmigrated imports get migrated after the post-import restart).

## 5. Automation review (12 screenshots, 10 unique)

- SMB-delivery-ratio intent consistent everywhere: **0.22 = boost** (RecentPod, High6PP,
  AcceUp0.5), **0.18 = revert/back-off** (Extra50%, 50SetRecent, High6PPoff, HighPP130Off,
  RecentPodOff, OffHighProf, CarbsTHoff). No revert automation forgets the ratio.
- Flags: (1) RecentPod ↔ RecentPodOff can oscillate each 5-min TT expiry while conditions
  persist (accepted); (2) AcceUp0.5 lacked falling-BG guard → fixed via migration (§4);
  (3) HighPP130Off branch 1 redundant (branch 3 is strictly broader) — cosmetic.
- Engine facts established: `ActionStartTempTarget` has a built-in precondition
  `TT NOT EXISTS`; **action preconditions are aggregated (AND) and gate the whole
  automation** (`AutomationEventObject.preconditionCanRun()`), shown as the event's
  "Preconditions:" section. Hence *no automation can replace an active TT* — and the
  code-based Skittles TT 5.7 (`startTempTargetIfNeeded`, skip-if-TT-active) faithfully
  mirrors the original UI behavior. GentleHypoRisk sets no TT; unaffected either way.

## 6. Export password ("expired" after reinstall)

- The 10-year validity fix is intact (`ExportPasswordDataStoreImpl`, 3650 days). Debug
  overrides (20 min / 2 days) require `DebugUnattendedExport(Dev)` marker files — none on
  the phone.
- **An empty password store reports `isExpired = true`** — "expired" really means "no
  usable stored password". The store lives in app-private DataStore + Android Keystore:
  **uninstall/reinstall wipes it** (signature-mismatch installs of the vanilla test APK
  forced exactly that). Settings import never touches it.
- Full list of things that clear the store: uninstall/app-data clear; setting/changing any
  protection password via the dialog (`PasswordCheckImpl`); `PasswordReset` /
  `ExportPasswordReset` marker files; and (fixed in AU267) starting a manual export and
  cancelling at the password prompt — the pre-prompt `clearPasswordDataStore` calls were
  removed, cancel now leaves the stored password intact.
- Recovery: one manual export with password entry re-primes for 10 years per phone.

## 7. Delayed bolus (50%-profile wizard mechanism) — investigation & redesign

**Terminology (locked in):** *delayed bolus* = 50%-profile wizard follow-up
(10/20/30-min BG checks, gap × 90%). *Split bolus* = equal-parts mechanism when the total
exceeds maxBolus at 100%. The code previously named the delayed mechanism "SplitBolus…",
which caused real confusion — fully renamed in AU275.

- **Zero-dose gap (AU273)**: original guard `insulinAfterConstraints > 0` meant a 50%
  bolus zeroed out by IOB never scheduled the delayed check. Now `(insulin > 0 || carbs > 0)`
  — the zero-immediate case schedules with `originalDose = 0`.
- **Careportal notes (AU274)**: `Db0` at scheduling (wizard and QuickWizard);
  `Db10/20/30` per check — `x.xU` delivered / `wait` retrying / `end` gave up. Notes are
  exclusive to the delayed bolus; the equal-parts split writes no careportal notes.
- **Why 21:50 (0.35 U, 13 g, 50% profile) never triggered**: log showed
  `enableSMB_always=true` — the original spec gated scheduling on the SMB *preference*
  being off ("only when enabled, profile is 50% AND SMBs are disabled", first commit).
  On this config the feature could **never** fire — a silent, config-dependent dead
  feature; earlier validation had evidently run with SMBs off. AU272 *did* contain the
  feature (present since `320TDDEX138`).
- **Redesign (AU275, per explicit spec: the wizard must own the SMB conflict, not defer)**:
  SMB-preference gate removed. The wizard sets a **35-min SMB block** on scheduling
  (`LongKey.DelayedBolusBlockSmbUntil`); `DelayedBolusWorker` releases it early on
  delivery, give-up (attempt 3) or user cancel. The loop (`OpenAPSAutoISFPlugin`) honors
  the delayed key and the equal-split key **independently** (max wins) and names the
  actual blocker in the constraint reason ("Delayed bolus active — SMBs blocked until …").
  Separate keys close the cross-talk hole (delayed finishing can't release an equal-split
  window).
- **Rename (AU275)**: `SplitBolusWorker` → `DelayedBolusWorker` (git-mv), work name
  `DelayedBolusWork`, all logs "Delayed bolus: …"; `BooleanKey.WizardDelayedBolusEnabled`
  (stored key string unchanged — user setting survives); preference UI "Enable delayed
  bolus" with corrected summary; shared cancel flag → `followUpBolusCancelled`
  (stop button cancels either mechanism's follow-ups). Upgrade caveat: a delayed check
  pending at install time is dropped once (old class name in WorkManager queue).
- **Graph (AU276next)**: yellow-pair detection matches notes `"Delayed bolus attempt"`
  **and** legacy `"Split bolus attempt"` so historical boluses keep their highlight.
- On-phone verification recipe: 50% profile, SMBs on, wizard bolus → `Db0` note +
  constraint line in OpenAPS tab → `Db10/20/30` notes tell the outcome.

## 8. Standalone patch #2 — delayed bolus for fresh aisf 3.2.0 base

- The rename invalidated the old delayed-bolus patch → regenerated as the complete
  current-form feature.
- Base: fresh shallow clone of `T-o-b-i-a-s/AndroidAPS` branch `3.4.2.1+aisf3.2.0` →
  `StudioProjects3421\AAPS3421aisf-delayedbolus` (commit `df65870`, compiled clean).
- Patch: `StudioProjects3421\0001-Delayed-bolus-for-50-profile-wizard-boluses.patch`
  — 14 files, 267 insertions; verified `git am` on pristine worktree
  (`aisf-delayedbolus-pristine`). Toggle defaults **off** → patch is inert until enabled.
- Port adaptations: base *has* `LongKey.kt` (unlike vanilla nightscout 3421); base bolus
  callback restructured (`else if` → `else { … }`); yellow highlight grafted onto the
  base's stock bolus triangle drawing; detection block adapted to the base's inline
  datapoint pipeline.

## 9. Utility scripts (PowerShell + .bat wrappers, in `C:\Users\arjay\Downloads`)

- **`pull_apks.ps1/.bat`** — phone `/sdcard/Download/7` → PC `Downloads\7` via adb.
  Oldest-first, ≤20/run, delete-from-phone only after verified pull (`pull -a` keeps
  timestamps). Retention: the newest 20 APKs always stay on the phone.
- **`pull_google_apks.ps1/.bat`** — `G:\My Drive\AAPS` → `Downloads\GoogleAPKs`.
  Oldest 20 APKs **and** oldest 20 ZIPs per run (zips moved intact, never expanded);
  searches all subfolders; destination mirrors the source structure (same-named APKs from
  different version folders can't collide); size-verified copy before delete; emptied
  source subfolders removed; newest 20 of each type always remain in Drive (rolling
  backup). Drive deletions go to Drive trash (~30-day undo).
- adb path: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`; with two devices use
  `-s <serial>` (Flip5 = `R5CW72WEG5A`). Crash buffer: `adb logcat -b crash -d`
  (clear with `-c`).

## 10. Misc findings

- **AAPSClient sync settings**: all `ns_upload`/`ns_receive_*` keys are
  `showInNsClientMode = false` and every Adaptive*Preference hides them in nsclient mode
  — by design (a follower always accepts everything). Keys still exist in SP and survive
  import; behavior in the client doesn't depend on them. Corollary: an event missing in
  AAPSClient is missing in NS itself.
- **Sensor change not detected**: created on the master from the xDrip broadcast's sensor
  start time (`XdripSourcePlugin`), guarded: bundle must contain the start time; skipped
  if within 5 min of, or older than, the stored `SENSOR_CHANGE`. Reaches NS only with
  upload on. Diagnose: master log, search "Sensor start time". Workaround: Careportal →
  "CGM Sensor Insert" on the master.
- **Commit-time API-level errors** ("current min is 1"): Android Lint running before
  Gradle sync finished — sync and retry, or untick "Analyze code" in the commit dialog.
- **PowerShell 5.1 gotchas** (additions to the ledger): inner double-quotes in arguments
  to native commands break quoting — use `git commit -F <file>`; `>` redirection writes
  UTF-16; CRLF normalization inflates commit-time diffstats (check `git show --stat`).
- Windows Gradle file-lock (`classes.jar … used by another process`): stale/parallel
  daemon (often Android Studio's) — `gradlew --stop` mine, retry, or just build in AS.
- Discord: creating an own server needs no permission; channels in community servers do
  (Manage Channels); threads/forum posts usually allowed; unsolicited-DM rules are
  socially enforced; invite links via DM/GitHub fine.
