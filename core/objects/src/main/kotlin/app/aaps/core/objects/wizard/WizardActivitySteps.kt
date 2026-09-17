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
    const val STILL_NOW_S30 = 200
    const val STILL_NOW_S5 = 100
    const val MOVING_PERCENT = 70.0

    fun stillMovingNow(steps5min: Int, steps30min: Int): Boolean =
        steps30min >= STILL_NOW_S30 || steps5min >= STILL_NOW_S5

    fun walkingSoonImmediatePercent(walkingSoon: Boolean, standingPct: Double): Double {
        if (!walkingSoon) return standingPct
        return minOf(standingPct, MOVING_PERCENT)
    }

    fun stillMovingNow(source: StepCountSource, now: Long): Boolean? {
        val mirrored = source.isMirroring()
        val values = source.latest(now, if (mirrored) LiveStepsMirror.MAX_AGE_MS else 15 * 60_000L)
            ?: return if (mirrored) null else false
        val s5 = values[5]
        val s30 = values[30]
        if ((s5 != null && s5 >= STILL_NOW_S5) || (s30 != null && s30 >= STILL_NOW_S30)) return true
        return if (s5 != null && s30 != null) false else null
    }
}
