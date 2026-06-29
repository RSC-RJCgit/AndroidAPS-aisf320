# Cancelled Bolus Carbs Handling

## Problem

When a bolus is cancelled via the stop button on `BolusProgressDialog`, the pump may return `success=true` (partial or full delivery before the cancel was processed). In the original code, `carbsRunnable.run()` stored the full original carb amount regardless — leaving the entered carbs (e.g. 25g entered by mistake) recorded even though the bolus was stopped.

## Root Cause

In `CommandBolus.execute()`:
```kotlin
val r = activePlugin.activePump.deliverTreatment(detailedBolusInfo)
if (r.success) carbsRunnable.run()
```
No check for `BolusProgressData.stopPressed` — carbs stored unconditionally on success.

## Solution

When `stopPressed == true` and `r.success == true`, instead of storing carbs automatically, store the original carb amount in `BolusProgressData.cancelledCarbs`. When `BolusProgressDialog` dismisses, it detects this and shows a dialog displaying how much insulin was actually delivered, asking the user how many grams were actually eaten.

## Files Changed

### `core/interfaces/.../pump/BolusProgressData.kt`
- Added `var cancelledCarbs: Double = 0.0`
- Reset to `0.0` in `set()`

### `implementation/.../queue/commands/CommandBolus.kt`
- Added `originalCarbs: Double = 0.0` constructor parameter
- In `execute()`: if `r.success && stopPressed && originalCarbs > 0`, sets `BolusProgressData.cancelledCarbs = originalCarbs` instead of running `carbsRunnable`

### `implementation/.../queue/CommandQueueImplementation.kt`
- Passes `originalCarbs` when constructing `CommandBolus`

### `ui/.../dialogs/BolusProgressDialog.kt`
- Injected `PersistenceLayer`, `DateUtil`, `UiInteraction`
- In `dismiss()`: captures `cancelledCarbs`, `delivered`, and `insulin` from `BolusProgressData` before resetting
- Calls `showCancelledCarbsDialog()` if `cancelledCarbs > 0`
- Dialog message: `"Bolus cancelled — X.XXU of Y.YYU delivered. How many grams were actually eaten?"`
- Input defaults to **0**
  - User enters > 0 → carbs stored via `persistenceLayer.insertOrUpdateCarbs()`
  - User enters 0 (or presses OK) → notification fires for 60 minutes
  - User cancels dialog → nothing stored, no notification

### `core/interfaces/.../notifications/Notification.kt`
- Added `const val CANCELLED_BOLUS_CARBS_REMINDER = 96`

### `core/ui/src/main/res/values/strings.xml`
- Added `cancelled_bolus_carbs_reminder`: `"Bolus cancelled — no carbs stored. Remember to log the correct amount when you use bolus calculator with IOB and COB ticked."`

## Flow

1. User enters 25g by mistake, presses OK — bolus starts
2. User presses stop on bolus progress dialog
3. Pump returns `success=true` (some insulin delivered)
4. `cancelledCarbs = 25.0` set — carbs **not** stored automatically
5. Dialog dismisses → prompt appears:
   > *"Bolus cancelled — 0.35U of 2.00U delivered. How many grams were actually eaten?"*
   > `[0]`
6. User enters correct amount (e.g. 5g) → stored
7. User enters 0 → notification: *"Bolus cancelled — no carbs stored. Remember to log the correct amount when you use bolus calculator with IOB and COB ticked."*
8. User cancels dialog → silent discard

## Design Notes

- Default input is **0** so the safe/easy path (no carbs, reminder fires) requires no deliberate action
- Entering a non-zero amount is intentional
- IOB from any partial delivery is correctly recorded by the pump regardless — the user decides the matching COB manually based on what was actually eaten
