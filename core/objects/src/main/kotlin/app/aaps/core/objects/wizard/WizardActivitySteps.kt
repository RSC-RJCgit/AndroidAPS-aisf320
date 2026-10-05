package app.aaps.core.objects.wizard

import app.aaps.core.objects.utils.StepCountSource
import app.aaps.core.utils.LiveStepsMirror

/**
 * Wizard "still moving now" vs leftover hour-bucket steps.
 *
 * Phone S5 / S15 are not useful alone (often 0 while walking unless a watch fills
 * them). 10 Sep Live: S30>=200 with S5<80 on 49 minutes of real walking; S5>=80
 * with S30<200 only twice. The loop already treats movement as S5>100 OR S30>200;
 * wizard uses those same floors and never S60 (18:13 plane meal: S60=1065 leftover
 * from 17:20-17:51, S30 already 44).
 *
 * S30 is the phone-reliable term. S5 is OR-only so a watch can still trip it;
 * S15 is not used. Missing samples fail open to "not moving" (do not cut a meal).
 *
 * WalkingSoon percent is 70 when it actually applies (milder than the old hard 50).
 * QuickWizard "always on" must call stillMovingNow before setting walkingSoon.
 */
object WizardActivitySteps {
    // 2026-10-05, per explicit request: S5 100 -> 20 and S30 200 -> 100, matching the loop gates in OpenAPSAutoISFPlugin
    // (S5<=20 / S30<=100 = standing). Light walking now counts as moving: the Walking soon auto-tick and the delayed
    // top-up's moving/seated switch (80% while moving) both trip on smaller step counts. The S30-lingers guard below is unchanged.
    const val STILL_NOW_S30 = 100
    const val STILL_NOW_S5 = 20
    const val MOVING_PERCENT = 70.0

    // S30 alone lingers ~25 min after you stop (3 Oct 14:00: S30=315 while S5=S15=0 for 15 min ticked Walking soon).
    // So S30 only counts while S15 or S5 shows recent steps. Missing S15 (null) keeps the old S30-only behaviour.
    fun stillMovingNow(steps5min: Int, steps30min: Int, steps15min: Int? = null): Boolean =
        steps5min >= STILL_NOW_S5 ||
            (steps30min >= STILL_NOW_S30 && (steps15min == null || steps15min > 0 || steps5min > 0))

    fun walkingSoonImmediatePercent(walkingSoon: Boolean, standingPct: Double): Double {
        if (!walkingSoon) return standingPct
        return minOf(standingPct, MOVING_PERCENT)
    }

    fun stillMovingNow(source: StepCountSource, now: Long): Boolean? {
        val mirrored = source.isMirroring()
        val values = source.latest(now, if (mirrored) LiveStepsMirror.MAX_AGE_MS else 15 * 60_000L)
            ?: return if (mirrored) null else false
        val s5 = values[5]
        val s15 = values[15]
        val s30 = values[30]
        if (s5 != null && s5 >= STILL_NOW_S5) return true
        if (s30 != null && s30 >= STILL_NOW_S30 && (s15 == null || s15 > 0 || (s5 ?: 0) > 0)) return true
        return if (s5 != null && s30 != null) false else null
    }
}
