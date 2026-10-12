package app.aaps.core.objects.wizard

enum class SplitHpCheck { Wait, Cancel, Allow }

/** Follow-up carb doses need a recent HP1 prediction from the glucose sample. */
fun splitHpCheck(hpMilliMmol: Long, measuredAt: Long, now: Long): SplitHpCheck {
    if (measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return SplitHpCheck.Wait
    return if (hpMilliMmol < 4_000L) SplitHpCheck.Cancel else SplitHpCheck.Allow
}
