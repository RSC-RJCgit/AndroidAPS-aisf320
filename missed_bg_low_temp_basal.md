# Missed BG Low Temp Basal — Change Notes

## Overview

When BG readings are missed overnight and the last known reading was below **6.5 mmol/L**, instead of simply cancelling the high temp basal, the loop now sets a **50% reduced temp basal** as a precautionary measure against hypoglycaemia.

---

## File Changed

`implementation/src/main/kotlin/app/aaps/implementation/alerts/LocalAlertUtilsImpl.kt`

---

## Changes

### 1. New import (line 25)

```kotlin
import app.aaps.core.interfaces.pump.PumpSync
```

Required to reference `PumpSync.TemporaryBasalType.NORMAL` when setting the temp basal.

---

### 2. Call added inside `checkStaleBGAlert()`

```kotlin
setReducedTempBasalIfLastBgLow(now, bgReading.value)
cancelHighTempBasalOvernight(now)          // existing — unchanged
```

The new method is called first, passing the current time and the last BG value (stored internally in mg/dL).

---

### 3. New method `setReducedTempBasalIfLastBgLow()`

```kotlin
// 6.5 mmol/L expressed in mg/dL (the internal storage unit)
private val LOW_BG_THRESHOLD_MGDL = 6.5 * 18.0182

private fun setReducedTempBasalIfLastBgLow(now: Long, lastBgMgdl: Double) {
    val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
    if (hour !in 2 until 6) return                          // 2–6 AM only
    if (lastBgMgdl >= LOW_BG_THRESHOLD_MGDL) return        // last BG must be < 6.5 mmol/L

    val profile = profileFunction.getProfile() ?: return
    val tempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(now) ?: return
    if (tempBasal.type == TB.Type.FAKE_EXTENDED) return
    val profileBasal = profile.getBasal(now)
    val tempBasalAbsolute = tempBasal.convertedToAbsolute(now, profile)
    if (tempBasalAbsolute <= profileBasal || Round.isSame(tempBasalAbsolute, profileBasal)) return  // only if running above profile

    val reducedRate = profileBasal * 0.5

    aapsLogger.warn(
        LTag.CORE,
        "Missing BG: last reading ${String.format("%.1f", lastBgMgdl / 18.0182)} mmol/L (<6.5), " +
            "setting temp basal to 50% of profile ($reducedRate U/h) for 30 min"
    )
    commandQueue.tempBasalAbsolute(
        absoluteRate = reducedRate,
        durationInMinutes = 30,
        enforceNew = true,
        profile = profile,
        tbrType = PumpSync.TemporaryBasalType.NORMAL,
        callback = null
    )
}
```

---

## Logic Summary — All Conditions Must Be True to Act

| Condition | Detail |
|---|---|
| Time window | 2:00 AM – 5:59 AM only |
| Last BG | Below 6.5 mmol/L (117 mg/dL) |
| Active temp basal | Must exist and be above profile basal rate |
| Temp basal type | Not a fake-extended (bolus-converted) basal |

**Action when all conditions met:** sets temp basal to **50% of profile basal** for **30 minutes** via `commandQueue.tempBasalAbsolute()`.

---

## Relationship to Existing `cancelHighTempBasalOvernight()`

Both methods run on every missed-readings alarm cycle (every `AlertsStaleDataThreshold` minutes, default 30 min). They share the same guards (2–6 AM, temp above profile, not fake-extended) but have different responses:

| Method | Extra Condition | Action |
|---|---|---|
| `setReducedTempBasalIfLastBgLow` | Last BG < 6.5 mmol/L | Set temp to 50% of profile basal for 30 min |
| `cancelHighTempBasalOvernight` | Any missed BG overnight | Cancel the high temp basal entirely |

In practice, if the last BG was below 6.5 mmol/L, `setReducedTempBasalIfLastBgLow` runs first and sets a 30-min temp at 50% of profile. `cancelHighTempBasalOvernight` then finds that new temp is already below profile basal and does nothing (its own guard fails), so the 50% temp stands.

If the last BG was 6.5 mmol/L or above, `setReducedTempBasalIfLastBgLow` exits early and `cancelHighTempBasalOvernight` runs as before — cancelling any high temp basal.

---

## Trigger Chain

```
KeepAliveWorker (every 5 min)
  └─ localAlertUtils.checkStaleBGAlert()
       └─ [if missed readings alarm fires]
            ├─ setReducedTempBasalIfLastBgLow(now, bgReading.value)   ← NEW
            └─ cancelHighTempBasalOvernight(now)                       ← existing
```
