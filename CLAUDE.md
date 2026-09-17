# AaAPS3422a320 — personal AndroidAPS fork

Personal fork of AndroidAPS, heavily customized around a hand-written "coded automations" layer
inside the AutoISF plugin, plus custom overview/history graph panels. Work happens on branch
`TDDautos3425`. Commit messages follow `aisf321UK_NNNdescription`. **Do not
bump `Versions.kt`** — the user sets NNN by hand. CI APK and Drive folder
names already follow `Versions.appVersion`. Do not announce an NNN as if this
session assigned it. Do not make version-only `NNNnext` commits.

Real device/incident data for tuning decisions lives outside this repo at `C:\backup\AAPS`
(`aiv_Regan\*.csv` exports, `aiv_Regan\output\combinedRegan.txt`, `ZFlip5_Logs1\AutoISF_dated_*`
text logs pulled from the phone) and `C:\backup\AAPS\aapsLogs\AutoISF_settings_*.txt` (exported
preference snapshots). **Always check real numbers there before tuning a threshold** — this
project's established practice is evidence over guessing (e.g. the SMB-stacking caps and the
`OvernightDuraRescue` thresholds were both set from measured percentiles, not round numbers).
A companion Windows batch script at `C:\Users\arjay\OneDrive\Desktop\aaps.bat` pulls this data off
the phone via `adb` (incremental, marker-file-based; DCIM is `.jpg`-only).

## Core dosing files

- `plugins/aps/.../openAPSAutoISF/OpenAPSAutoISFPlugin.kt` — the big one. ~100+ hand-ported
  "coded automations" (each a `run { if (readyToRun(...)) { ... } }` block), reimplementing what
  used to be native AAPS Automation-tab triggers/actions directly in Kotlin so they can react every
  loop cycle instead of on a slower automation-engine tick.
- `plugins/aps/.../openAPSAutoISF/DetermineBasalAutoISF.kt` — the actual SMB/basal dosing math
  (`determine_basal()`), including the anti-stacking guards (escalating trim, cumulative 10-min SMB
  cap) and the recent-low rebound guard.

### Key idioms

- `readyToRun(key, minMinutes)` / `markRun(key)` — a per-key throttle map, the standard way to keep
  a block from re-firing every 1-minute loop cycle. Pick a throttle that matches intent: too short
  and a block can re-arm on its own output (see the `EveningTH`/`NightIobCeiling` flip-flop
  regression below); too long and it won't catch a same-night revert.
- `checkAutomationState(name, value)` / `setAutomationState(name, value)` — a generic named
  state-machine store shared with native Automation-tab triggers/actions and the automation-state
  plugin/UI. **`setState` throws if the value isn't pre-declared for that state name in the UI** —
  don't invent a new state value from code without registering it there first.
- `isTimeBetween(startH, startM, endH, endM)` — handles overnight wraparound
  (`nowMins >= startMins || nowMins < endMins` when `startMins > endMins`).
- `RT.reason.append(...)` — the no-DB-migration way to expose a live-computed value in the AIV
  history export/dialog. Parsed back out by regex in `AutoIsfHistoryExporter.kt`.

## Overnight safety-guard architecture (built after two real hypo incidents, 27 Jul and 6-7 Aug 2026)

Root cause: at this user's 1-minute loop cadence, ~60s SMB gaps are normal and invisible to an
interval-based anti-stacking test; both damaging bursts started from *low* IOB, so a simple IOB
ceiling couldn't restrain them either; only cumulative delivered *amount* separated the bursts from
routine dosing.

- `NightIobCeiling` (00:00-06:00) / `EveningIobCeiling` (20:00-00:00) — ceiling-only caps on
  `iobTH`/acce weight, ignore profile name, key purely on time + current value.
- Escalating SMB anti-stack trim + cumulative 10-min SMB-sum cap (`smbSum10Min()`,
  `smbSum30Min()`) in `DetermineBasalAutoISF.kt` — the only guards that measure cumulative
  delivered amount rather than rate or level.
- Unconditional 22:00-06:00 switch-to-Low profile at four sites: `OffHighProf`,
  `MJrecentCurrProfAcce`, `NightAcce`, `EveningTH`. `BasalUp` is blocked 22:00-06:00 (it used to
  undo this the moment BG ticked up).
- `OvernightDuraRescue` (trial, added but not yet build/device-verified) — a narrow, one-shot,
  60-minute counterpart that can switch back UP to Standard profile 02:00-04:00 when `duraISF`
  genuinely dominates, no stacking, no recent low, BG flat. The other three switch-to-Low sites
  check a shared `rescueActive` flag and yield while it's active — see the block's own doc comment
  for the exact gate and the units/freshness verification already done.
- **Known regression pattern to avoid repeating**: changing an automation's *action* value without
  re-checking its own *self-latch* condition against that new value. `EveningTH` did this once
  (action changed 50→45, latch stayed `<50`) and flip-flopped with `NightIobCeiling` every ~5 min
  for hours before being caught via careportal notes.

## Graph annotation architecture

Fixed-position text labels/rows on the overview graphs (SMB dose labels, ISF adaptation indices,
notes, etc.) all follow the same pipeline:

1. `core/graph/data/Shape.kt` — one enum value per label type, doc-commented with where it renders.
2. `core/graph/data/PointsWithLabelGraphSeries.kt` — the renderer; switches on `Shape` per data
   point. Fixed-position rows use a pixel offset computed once above the draw loop (e.g.
   `stepsRowPy`, `isfIndicesRowPy`); per-timestamp labels (like SMB doses or `OvernightDuraRescue`'s
   sibling `SMB_STACK_TOTAL`) draw at that point's own X instead. Shapes meant to always render
   regardless of the current Y-scale must be added to the `yIndependentShape` check, or they get
   culled whenever their placeholder Y falls outside the panel's current value range.
3. `workflow/Prepare*Worker.kt` — builds the actual `DataPointWithLabelInterface` list from
   persisted data (bolus/AIV/therapy-event records) and assigns it to an `overviewData.xxxSeries`
   property.
4. `core/interfaces/overview/OverviewData.kt` (+ `OverviewDataImpl.kt`) — the series property.
5. `plugins/main/.../graphData/GraphData.kt` — `fun addXxx() { addSeries(...) }`.
6. Called from **both** `OverviewFragment.kt` and `app/.../HistoryBrowseActivity.kt` — these two
   screens' graph-building loops must be kept in sync manually; History Browse has historically
   lagged behind Overview (missing annotations, wrong panel for a given `g==` index) and needed a
   catch-up pass this session.

## In-app quick-action surfaces (List1 / List2)

Two separate coded-quick-action dialogs, both unrelated to the standard Settings screen and both
living in `OverviewFragment.kt`:

- **List 1** = `ttCodesList()` (`OverviewFragment.kt`), opened by double-tapping the IOB graph area.
  Rows are MJ-state / profile-role / tier-set actions (e.g. "Re-pick coded profiles", Tier A/B/C)
  that relay coded fake-TT values (e.g. 5.148/5.150/5.216-5.220) via `applyTtControl()`.
- **List 2** = the `BasalDirectAction` enum (`OverviewFragment.kt`), opened by double-tapping the
  basal-rate icon area (`showBasalDirectActionListDialog()`). Rows are on/off toggles and one-shot
  actions (AnyDesk restart, APK install/stage, UKF1-dosing toggle, location-SMS toggle, profile-batch
  toggles, Tier-3-boost toggle, plus three stepped Boost-tuning rows). Each entry sends
  `EventAutoIsfDirectTtCode(mmol)`, consumed either by the immediate local-toggle block near the top
  of `OpenAPSAutoISFPlugin.kt`'s `EventAutoIsfDirectTtCode` subscription, or by an `activeTtNear(...)`
  block later in the invoke() loop.

**Adding a new List2 toggle for an existing Settings-screen `BooleanKey`** (the established pattern —
see `TIER3_BOOST_TOGGLE`/`UKF1_DOSING_TOGGLE`/`LOCATION_SMS_TOGGLE` for precedent) needs four edits:
1. A new `BasalDirectAction` entry with a free coded mmol value (the 5.1xx/5.2xx block is densely
   packed — check for gaps before picking one).
2. Add it to the shared `EventAutoIsfDirectTtCode`-dispatch branch in `runBasalDirectAction()`.
3. Add its current-value line to `basalDirectActionCurrentValue()` (usually `mirroredOrLocalBoolean(...)`).
4. Add the matching `else if (kotlin.math.abs(event.mmol - X) <= 0.0000001)` branch in
   `OpenAPSAutoISFPlugin.kt`'s direct-event subscription, flipping the preference + SMS + CarePortal note.
Client-side "Pump current" mirroring usually needs no extra wiring: `autoIsfSettingsSnapshot()`
already includes every `BooleanKey` whose enum name contains `"AutoIsf"` generically — only keys
whose name does NOT contain "AutoIsf" (e.g. `AutomationCodedLocationsEnabled`) need a manual
snapshot line.

**"Use live X on VirtualPump" toggles** cover two genuinely different shapes — don't conflate them:
- **Continuous mirror** (precedent: `ApsAutoIsfUseLiveStepsOnVirtual`): Virtual's own reads are
  redirected to the loop phone's real value every cycle, for as long as the toggle is on. Steps are
  parsed out of `loop.lastRun`'s reason text via a regex — see `liveStepsFromLoopReason()`.
- **One-time event seed** (precedent: `ApsAutoIsfUseLiveMjStateOnVirtual`): Virtual is NOT continuously
  reading the loop phone's ongoing state — `checkAutomationState()`/`setAutomationState()` are
  untouched, and this deliberately does not affect the AIV "MJ" column's own separate recording path.
  Instead, the real button-press event itself (the plain CarePortal Note
  `handleDirectMjUserAction()`'s START/RESTORE branches already write, e.g. "MJ active"/"NOMJremains")
  is watched for via the same cursor-tracked Note-channel pattern as `SetRole (Note channel)`, and on
  first sight, the identical local `setAutomationState()` call is made once on Virtual — after that,
  Virtual's own existing automations (MJ2old/MJ3old/MJ4/MJ5/MJ6/MoreMJ etc.) evolve the state
  independently, exactly as if the button had been pressed on Virtual itself.
Before building a new "use live X" toggle, ask which shape is actually wanted — the two look similar
but have very different blast radii (one substitutes a value continuously; the other seeds a starting
point once and lets local logic run forward).

**Widening a List1 `Stepped` 2-way row to more than 2 options** (precedent: "MJ state (manual
override)", widened from NOMJremains/MJ3 to all four MJ states): switch the `TtCode` entry from
`Stepped` to `Action` and build the dialog by hand with `setItems(options) { _, which -> ... }`
instead of relying on `Stepped`'s built-in 2-way confirm dialog — `AlertDialog`'s Positive/Negative/
Neutral buttons only stretch to 3 choices. An `Action` entry's `onSelect` runs directly with no
outer confirm wrapper (see `showTtCodesListDialog()`'s dispatch `when`), so build the full dialog
(title/message/items/cancel) inside `onSelect` yourself.

## Client is a pure mirror — diagnosing a Client/Virtual divergence

Client never runs `invoke()`'s dosing/automation blocks at all — it only relays List1/List2
commands to the loop phone and displays whatever NS sync brings it. So when Client's behavior
differs from Live's, that is *expected*: Client isn't independently deciding not to do something,
it structurally never runs the code that would.

**Known gap (not yet fixed): `UserEntries_30h` export ≠ proof of what Client actually received.**
That export (`ImportExportPrefsImpl.kt`'s `writeUserEntriesAivLocal`) reads the local `UserEntry`
audit-log table, which is a separate table from `TherapyEvent`/`TempTarget`. `UserEntry` rows are
created by the code path that *performs* an action locally — since Client never runs the automation
blocks itself, it never creates a `UserEntry` row for an action Live performed, even when the real
`TherapyEvent`/`TempTarget` record was correctly NS-synced into Client's own DB. **A gap in Client's
`UserEntries_30h` export does NOT prove Client never received the underlying data** — check Client's
actual `TherapyEvent`-backed data (combined AIV csv, `persistenceLayer.getTherapyEventDataFromTime`)
instead before concluding a sync gap.

**For "what did Live actually do/have at time X" — Live DOES have real dated data, enumerate its
own folder first.** `Live_SMA366B\` has no per-day *combined* file (no `Live_SMA366BdatedAIV`
folder, and the plain `combinedLive_SMA366B_rebuilt.txt` has no date column and spans ~5+ days —
unsafe to filter by time-of-day alone). But `Live_SMA366B\` itself directly holds real per-cycle
dated files, `AutoISF_Live_SMA366B_<YYYYMMDD>_<HHMMSS>.csv/.txt`, each a rolling dump of several
hours' rows going backward from its own filename timestamp — pick the file whose timestamp is
shortly AFTER the moment you need, and it will contain that moment's row directly. Client's mirrored
`Client_SMF731BdatedAIV\combinedClient_SMF731B<YYYYMMDD>.txt` is a good cross-check (built by
`NSDeviceStatusHandler.kt` straight from Live's NS device status) but is not a substitute for
checking Live's own folder — go there first. Real example: Live's own per-cycle CSV directly showed
MJ=`MJ2` (not `NOMJremains`) 5:10-5:29 PM on 9/15, explaining why `HighDaytimeBrake` fired on
Virtual but not Live that day — Client's mirror showed the identical thing, but Live's own file
should have been the first one checked, not skipped in favor of a "no dated Live file exists"
assumption made without actually listing `Live_SMA366B\`.

**Wanted, not yet built**: when an automation's conditions are checked but DON'T fire on Live, there
is currently no persisted record of *why not* — no snapshot of the settings/IOB/BGL/delta values
that were evaluated at that moment. Worth keeping in mind as a design goal for future diagnostic
work in this file: a real record of "conditions checked, this one was false" would have made this
kind of Live-vs-Virtual/Client divergence question answerable directly instead of via log archaeology.

## AIV history / export

`ui/.../dialogs/AutoIsfHistoryExporter.kt` and `AutoISFHistoryDialog.kt` read the persisted
`AutoIsfValues` DB entity (`database/impl/.../entities/AutoIsfValues.kt`) plus SMB bolus records to
build the CSV export and in-app history table. Several columns (e.g. `Int5`/SMBi5, `LowBG`, `HP2`)
are reconstructed at export time from raw bolus timestamps or `RT.reason` text rather than being
persisted fields — check this file before assuming a value is a real DB column.
