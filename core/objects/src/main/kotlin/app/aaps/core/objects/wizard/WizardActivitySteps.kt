package app.aaps.core.objects.wizard

import app.aaps.core.data.model.SC
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer

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

    fun latestStepsSample(persistenceLayer: PersistenceLayer, now: Long): SC? {
        val samples = persistenceLayer.getStepsCountFromTimeToTime(now - T.mins(15).msecs(), now)
        return samples.maxByOrNull { it.timestamp }
    }

    fun stillMovingNow(persistenceLayer: PersistenceLayer, now: Long): Boolean {
        val sample = latestStepsSample(persistenceLayer, now) ?: return false
        return stillMovingNow(sample.steps5min, sample.steps30min)
    }
}
