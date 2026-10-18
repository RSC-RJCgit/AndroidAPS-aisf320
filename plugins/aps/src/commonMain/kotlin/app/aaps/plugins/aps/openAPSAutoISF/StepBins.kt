package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SC

// S10 and S15 have two meanings (2026-10-18):
// - Live (v3) and the upstream phone step counter send them as single five-minute bins: 5-10 and 10-15 min ago.
//   Rows received from Live are stored with the Live device ("openaps://...").
// - A watch row (StepCountListener) stores them as totals: the last 10 and the last 15 min.
// The dosing thresholds (moving = S15 > 30, the BasalUp brake S15 <= 30, ActivitySensitivity's equal 200/300 limits on
// S5, S10 and S15) came from v3 and were tuned on bins, so dosing reads bins from either source.

/** True for a row received from the Live phone, whose S10 and S15 are bins. */
internal fun isLiveStepRow(sc: SC): Boolean = sc.device.startsWith("openaps://")

/** Steps 5 to 10 minutes ago. */
internal fun steps10Bin(sc: SC): Int =
    if (isLiveStepRow(sc)) sc.steps10min else (sc.steps10min - sc.steps5min).coerceAtLeast(0)

/** Steps 10 to 15 minutes ago. */
internal fun steps15Bin(sc: SC): Int =
    if (isLiveStepRow(sc)) sc.steps15min else (sc.steps15min - sc.steps10min).coerceAtLeast(0)
