# Delayed Split Bolus — Feature Notes

## Overview

When the bolus wizard delivers a bolus (e.g. at 50% profile), a second equal bolus
("split dose") is conditionally delivered later, provided certain glucose criteria are
met at the time of each check.

The feature is implemented on the **`test-delayed-split-bolus`** branch, forked from
`tdd-sensitivity-autosens-off`.

---

## Concept

> "If the bolus calculator gives a bolus when percent profile is 50%, is it able to
> give a further delayed equal bolus again under certain criteria?"

**Answer:** Yes. After the primary wizard bolus succeeds, three timed checks are
scheduled. If criteria are met at any check, the same dose is delivered and no further
checks run. If criteria are never met by 30 minutes, no split dose is given.

---

## Check Schedule

| Check | Delay after primary bolus | Action if criteria met | Action if not met |
|-------|--------------------------|------------------------|-------------------|
| Attempt 1 | +10 minutes | Deliver split dose, stop | Schedule attempt 2 |
| Attempt 2 | +20 minutes | Deliver split dose, stop | Schedule attempt 3 |
| Attempt 3 | +30 minutes | Deliver split dose, stop | Log and stop — no dose |

---

## Criteria (all must be true at check time)

| Metric | Threshold | mg/dL equivalent |
|--------|-----------|-----------------|
| BGL | > 4.5 mmol/L | > 81.1 mg/dL |
| Delta (5 min change) | > 0.1 mmol/L | > 1.8 mg/dL |
| Short avg delta | > 0.2 mmol/L | > 3.6 mg/dL |
| Long avg delta | > 0.05 mmol/L | > 0.9 mg/dL |

Criteria are re-evaluated fresh at each check time using the live `GlucoseStatusProvider`.

---

## Split Dose Amount

Exactly equal to `insulinAfterConstraints` from the original wizard bolus — i.e. the
same dose that was actually delivered (after constraint checks), not the raw calculated
dose.

---

## File Changed

**`core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt`**

### Imports added

```kotlin
import android.os.Handler
import android.os.Looper
```

### Call site — inside `commonProcessing()` bolus success callback

```kotlin
commandQueue.bolus(this, object : Callback() {
    override fun run() {
        if (!result.success) {
            uiInteraction.runAlarm(...)
        } else {
            if (useAlarm && carbs > 0 && carbTime > 0) {
                automation.scheduleTimeToEatReminder(...)
            }
            if (insulinAfterConstraints > 0) {
                scheduleSplitBolusChecks(insulinAfterConstraints, attemptNumber = 1)
            }
        }
    }
})
```

### New private members

```kotlin
// Thresholds (mg/dL — matching GlucoseStatus units)
private val SPLIT_BGL_THRESHOLD_MGDL = 4.5 * 18.0182
private val SPLIT_DELTA_THRESHOLD_MGDL = 0.1 * 18.0182
private val SPLIT_SD_THRESHOLD_MGDL = 0.2 * 18.0182
private val SPLIT_LD_THRESHOLD_MGDL = 0.05 * 18.0182

private fun splitCriteriaMet(gs: GlucoseStatus): Boolean =
    gs.glucose > SPLIT_BGL_THRESHOLD_MGDL &&
    gs.delta > SPLIT_DELTA_THRESHOLD_MGDL &&
    gs.shortAvgDelta > SPLIT_SD_THRESHOLD_MGDL &&
    gs.longAvgDelta > SPLIT_LD_THRESHOLD_MGDL

private fun scheduleSplitBolusChecks(dose: Double, attemptNumber: Int) {
    if (attemptNumber > 3) return
    val delayMs = T.mins(10L * attemptNumber).msecs()
    Handler(Looper.getMainLooper()).postDelayed({
        val gs = glucoseStatusProvider.glucoseStatusData
        if (gs != null && splitCriteriaMet(gs)) {
            // deliver split dose
            aapsLogger.info(LTag.CORE, "Split bolus attempt $attemptNumber: criteria met ...")
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = dose
                notes = "Split bolus (attempt $attemptNumber, wizard dose repeat)"
                uel.log(Action.BOLUS, Sources.WizardDialog, notes, listOf(ValueWithUnit.Insulin(dose)))
                commandQueue.bolus(this, object : Callback() {
                    override fun run() {
                        if (!result.success)
                            uiInteraction.runAlarm(result.comment, ...)
                    }
                })
            }
        } else if (attemptNumber < 3) {
            aapsLogger.info(LTag.CORE, "Split bolus attempt $attemptNumber: criteria NOT met — scheduling attempt ${attemptNumber + 1}")
            scheduleSplitBolusChecks(dose, attemptNumber + 1)
        } else {
            aapsLogger.info(LTag.CORE, "Split bolus: criteria not met at 30 min — no split dose delivered")
        }
    }, delayMs)
}
```

---

## Logging

All check outcomes are written to the AAPS log under `LTag.CORE`. Filter logcat for
`"Split bolus"` to trace behaviour during testing.

Example log lines:
```
Split bolus attempt 1: criteria NOT met (BGL=5.2) — scheduling attempt 2 in 10 min
Split bolus attempt 2: criteria met (BGL=6.1 delta=0.22 SD=0.31 LD=0.12) — delivering split dose 1.5U
```

---

## Safety Notes

- The split dose is subject to normal pump delivery (not bypassing constraints at
  delivery time — the dose amount itself was already constrained when the wizard ran).
- If the pump rejects the bolus, the existing `runAlarm` error path fires.
- The `Handler` is bound to the main looper; if the app process is killed between
  wizard time and check time the pending split will not fire (expected behaviour for
  a test branch).
- This feature fires for **all** wizard boluses where `insulinAfterConstraints > 0`,
  not only 50% profile situations. The 50% profile context was the motivation but
  is not a gate condition — add a profile percentage check to `splitCriteriaMet` if
  needed.

---

## Branch

```
git checkout test-delayed-split-bolus
```

Base branch: `tdd-sensitivity-autosens-off`
