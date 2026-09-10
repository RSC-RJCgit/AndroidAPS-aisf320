package app.aaps.core.objects.wizard

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max

/**
 * Delayed bolus (50%-profile wizard mechanism — NOT the equal-parts split bolus):
 * after a wizard bolus on the 50% profile, re-checks BG every 10 min from 10 through 80
 * minutes and delivers the remaining gap once glucose criteria confirm a rise.
 * Still moving now (S30>=200, S5 OR watch only): keep MOVING_PERCENT (70) of the
 * remaining gap. Seated: deliver the full remaining gap toward standing wiz%.
 * No elapsed-time 90/50 haircut.
 * Formerly named SplitBolusWorker; renamed to keep the two mechanisms unmistakable.
 *
 * Fixed 2026-08-26: this worker and the closed loop's own dosing (SMBs, temp basals) are two
 * entirely independent systems, and this one previously had no visibility into what the other
 * had already done for the very same rise, or into how much of the original carb estimate is
 * still actually outstanding -- its only gate was "is glucose still rising", not "how much
 * coverage has already arrived since". A real capture showed a 50-minute-delayed 1.45U dose
 * land on a window with zero SMBs, so nothing went wrong that time, but a first attempt at
 * fixing this (subtracting only SMB deliveries) turned out to be incomplete: with zero SMBs it
 * would have changed nothing for that exact case, and it ignored basal/TBR-driven IOB growth
 * and residual COB entirely. Replaced with two independent, more direct checks instead of
 * inferring coverage from SMB records alone:
 *  - iobDelta = (current total IOB, bolus+basal) − (total IOB captured at the ORIGINAL bolus,
 *    passed through from BolusWizard). Only counted if positive -- IOB naturally decays with no
 *    further dosing, so a normal decline contributes nothing here; growth beyond that decline
 *    can only mean something else (SMBs, a raised temp basal, anything) added insulin since,
 *    and that additional insulin is treated as coverage already delivered toward fullRequired.
 *    This subsumes the original SMB-only check (an SMB raises IOB directly, so it's already
 *    reflected here) without needing a separate bolus-history query.
 *  - cobFraction = current COB ÷ the ORIGINAL carbs entered (also passed through from
 *    BolusWizard), clamped to [0,1]. fullRequired was sized for the full original carb amount;
 *    if most of it has already been absorbed by delivery time, the remaining insulin-relative-
 *    carbs gap shrinks with it, independent of anything IOB-side. 1.0 = essentially nothing
 *    absorbed yet, 0.0 = fully absorbed (no further dose warranted on carb grounds alone).
 * The two apply in sequence: iobDelta reduces the raw gap first, then cobFraction scales what's
 * left, then the live S30 seated/moving multiplier.
 */
class DelayedBolusWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var iobCobCalculator: IobCobCalculator

    // Release the SMB block set at scheduling time (terminal outcomes only)
    private fun unblockSmb(why: String) {
        preferences.put(LongKey.DelayedBolusBlockSmbUntil, 0L)
        aapsLogger.info(LTag.CORE, "Delayed bolus: SMBs unblocked ($why)")
    }

    // Careportal marker for each delayed-bolus check: Db10/Db20/Db30 (attempt × 10 min)
    private fun addCheckNote(text: String) {
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = dateUtil.now(),
                type = TE.Type.NOTE,
                glucoseUnit = profileFunction.getUnits()
            ).also {
                it.note = text
                it.duration = T.mins(5).msecs()
            },
            action = Action.CAREPORTAL,
            source = Sources.WizardDialog,
            note = "Delayed bolus check",
            listValues = listOf()
        ).blockingGet()
    }

    // A delivered delayed dose is a new calculation made well after the original Wizard result.
    // Persist it as a real BolusCalculatorResult so Treatments shows a separate "Calc" row whose
    // details explain the live IOB/COB reduction and explicitly close the one-shot delayed sequence.
    private fun addDeliveredCalcTreatment(
        gs: GlucoseStatus,
        delayedDose: Double,
        fullRequired: Double,
        originalDose: Double,
        iobDelta: Double,
        currentCob: Double,
        originalCarbs: Double,
        cobFraction: Double,
        gapAfterIob: Double,
        rawDose: Double,
        multiplier: Double,
        dbLabel: String
    ) {
        val profile = profileFunction.getProfile()
        val multiplierPct = (multiplier * 100).toInt()
        val note = "$dbLabel delayed bolus: ${Round.roundTo(delayedDose, 0.01)}U delivered\n" +
            "Gate BG ${Round.roundTo(gs.glucose / 18.0182, 0.01)}, D ${Round.roundTo(gs.delta / 18.0182, 0.01)}, " +
            "SD ${Round.roundTo(gs.shortAvgDelta / 18.0182, 0.01)}, LD ${Round.roundTo(gs.longAvgDelta / 18.0182, 0.01)} mmol/L\n" +
            "Full required ${Round.roundTo(fullRequired, 0.01)}U - initial ${Round.roundTo(originalDose, 0.01)}U " +
            "- IOB cover ${Round.roundTo(iobDelta, 0.01)}U = ${Round.roundTo(gapAfterIob, 0.01)}U\n" +
            "COB ${Round.roundTo(currentCob, 0.1)}/${Round.roundTo(originalCarbs, 0.1)}g " +
            "(x${Round.roundTo(cobFraction, 0.01)}) -> ${Round.roundTo(rawDose, 0.01)}U; " +
            "${if (multiplier < 1.0) "still moving" else "seated"} $multiplierPct%\n" +
            "Delayed sequence complete; no residual pending"
        persistenceLayer.insertOrUpdateBolusCalculatorResult(
            BCR(
                timestamp = dateUtil.now(),
                targetBGLow = profile?.getTargetLowMgdl() ?: 0.0,
                targetBGHigh = profile?.getTargetHighMgdl() ?: 0.0,
                isf = profile?.getIsfMgdl("DelayedBolusWorker") ?: 0.0,
                ic = profile?.getIc() ?: 0.0,
                bolusIOB = iobDelta,
                wasBolusIOBUsed = iobDelta > 0.0,
                basalIOB = 0.0,
                wasBasalIOBUsed = false,
                glucoseValue = gs.glucose,
                wasGlucoseUsed = true,
                glucoseDifference = gs.delta,
                glucoseInsulin = 0.0,
                glucoseTrend = 0.0,
                wasTrendUsed = false,
                trendInsulin = 0.0,
                cob = currentCob,
                wasCOBUsed = originalCarbs > 0.0,
                cobInsulin = 0.0,
                carbs = originalCarbs,
                wereCarbsUsed = originalCarbs > 0.0,
                carbsInsulin = 0.0,
                otherCorrection = delayedDose,
                wasSuperbolusUsed = false,
                superbolusInsulin = 0.0,
                wasTempTargetUsed = false,
                totalInsulin = delayedDose,
                percentageCorrection = multiplierPct,
                profileName = profileFunction.getProfileName(),
                note = note
            )
        ).blockingGet()
    }

    companion object {
        const val WORK_NAME = "DelayedBolusWork"
        const val KEY_ORIGINAL_DOSE = "originalDose"
        const val KEY_FULL_REQUIRED = "fullRequired"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_ORIGINAL_TIME = "originalTime"
        const val KEY_ORIGINAL_CARBS = "originalCarbs"
        const val KEY_ORIGINAL_IOB = "originalIob"

        private val DELAYED_BGL_MGDL   = 4.5  * 18.0182
        private val DELAYED_DELTA_MGDL = 0.1  * 18.0182
        private val DELAYED_SD_MGDL    = 0.15 * 18.0182
        private val DELAYED_SD_BG_BYPASS_MGDL = 5.5 * 18.0182
        private val DELAYED_LD_MGDL    = 0.05 * 18.0182
        private val DELAYED_BGL_AGE_MS = T.mins(5).msecs()

        // Flat 10-minute poll: each call (first attempt or retry) waits 10 min from whenever it's made,
        // for up to 8 attempts total (10 through 80 min), then gives up. originalTime is just carried along for logging
        // (total elapsed time since the original bolus), it doesn't affect the delay.
        // originalCarbs/originalIob (added 2026-08-26): the wizard's own inputs at the moment of the
        // original bolus, carried through every retry so the coverage checks in doWorkAndLog() always
        // compare against the true original state, not whatever attempt just ran.
        fun enqueue(
            context: Context,
            originalDose: Double,
            fullRequired: Double,
            attempt: Int,
            originalTime: Long = System.currentTimeMillis(),
            originalCarbs: Double = 0.0,
            originalIob: Double = 0.0
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(DelayedBolusWorker::class.java)
                        .setInitialDelay(10L, TimeUnit.MINUTES)
                        .setInputData(workDataOf(
                            KEY_ORIGINAL_DOSE to originalDose,
                            KEY_FULL_REQUIRED to fullRequired,
                            KEY_ATTEMPT to attempt,
                            KEY_ORIGINAL_TIME to originalTime,
                            KEY_ORIGINAL_CARBS to originalCarbs,
                            KEY_ORIGINAL_IOB to originalIob
                        ))
                        .build()
                )
        }
    }

    override suspend fun doWorkAndLog(): Result {
        val originalDose = inputData.getDouble(KEY_ORIGINAL_DOSE, 0.0)
        val fullRequired = inputData.getDouble(KEY_FULL_REQUIRED, 0.0)
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val originalTime = inputData.getLong(KEY_ORIGINAL_TIME, dateUtil.now())
        val originalCarbs = inputData.getDouble(KEY_ORIGINAL_CARBS, 0.0)
        val originalIob = inputData.getDouble(KEY_ORIGINAL_IOB, 0.0)

        if (BolusProgressData.followUpBolusCancelled) {
            aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt: cancelled by user")
            unblockSmb("cancelled")
            return Result.success()
        }

        val gs = glucoseStatusProvider.glucoseStatusData
        val now = dateUtil.now()
        val bglFresh = gs != null && (now - gs.date) <= DELAYED_BGL_AGE_MS
        val criteriaOk = gs != null &&
            gs.glucose > DELAYED_BGL_MGDL &&
            gs.delta > DELAYED_DELTA_MGDL &&
            (gs.shortAvgDelta >= DELAYED_SD_MGDL || gs.glucose > DELAYED_SD_BG_BYPASS_MGDL) &&
            gs.longAvgDelta > DELAYED_LD_MGDL
        val bglStr = gs?.let { String.format("%.1f", it.glucose / 18.0182) } ?: "n/a"

        val dbLabel = "Db${attempt * 10}"

        if (criteriaOk && bglFresh) {
            // Added 2026-08-26 (see class doc): don't double-count coverage that's already arrived
            // since the original bolus, from ANY source -- more insulin than the original dose's own
            // natural decline explains (iobDelta), or carbs that have already been absorbed
            // (cobFraction).
            val currentIob = iobCobCalculator.calculateIobFromBolus().iob +
                iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob
            val iobDelta = (currentIob - originalIob).coerceAtLeast(0.0)
            val currentCob = iobCobCalculator.getCobInfo("DelayedBolusWorker").displayCob ?: 0.0
            val cobFraction = if (originalCarbs > 0) (currentCob / originalCarbs).coerceIn(0.0, 1.0) else 1.0

            val gapAfterIob = (fullRequired - originalDose - iobDelta).coerceAtLeast(0.0)
            val rawDose = gapAfterIob * cobFraction
            val elapsedMin = attempt * 10
            val movingNow = WizardActivitySteps.stillMovingNow(persistenceLayer, now)
            // Seated: full remaining gap (already sized to standing wiz%). S30 still moving: 70%.
            val multiplier = if (movingNow) WizardActivitySteps.MOVING_PERCENT / 100.0 else 1.0
            val delayedDose = Round.roundTo(max(0.0, rawDose * multiplier), activePlugin.activePump.pumpDescription.bolusStep)
            if (delayedDose <= 0.0) {
                aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt (${elapsedMin}min): criteria met BGL=$bglStr but already covered — iobDelta=${Round.roundTo(iobDelta, 0.01)}U cobFraction=${Round.roundTo(cobFraction, 0.01)} (fullRequired=${fullRequired}U given=${originalDose}U) — no delayed dose needed")
                addCheckNote("$dbLabel covered")
                unblockSmb("covered by IOB/COB check")
                return Result.success()
            }
            aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt (${elapsedMin}min): criteria met BGL=$bglStr — delivering ${delayedDose}U (fullRequired=${fullRequired}U given=${originalDose}U iobDelta=${Round.roundTo(iobDelta, 0.01)}U cobFraction=${Round.roundTo(cobFraction, 0.01)} gap=${Round.roundTo(rawDose, 0.01)}U × ${(multiplier*100).toInt()}% ${if (movingNow) "still moving" else "seated"})")
            addCheckNote("$dbLabel ${delayedDose}U")
            unblockSmb("delivering")
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = delayedDose
                notes = "Delayed bolus attempt $attempt (full required ${fullRequired}U − given ${originalDose}U − iobDelta ${Round.roundTo(iobDelta, 0.01)}U, × cobFraction ${Round.roundTo(cobFraction, 0.01)} × ${(multiplier*100).toInt()}%)"
                uel.log(
                    action = Action.BOLUS,
                    source = Sources.WizardDialog,
                    note = notes,
                    listValues = listOf(ValueWithUnit.Insulin(delayedDose))
                )
                commandQueue.bolus(this, object : Callback() {
                    override fun run() {
                        if (!result.success)
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                        else
                            addDeliveredCalcTreatment(
                                gs = gs,
                                delayedDose = delayedDose,
                                fullRequired = fullRequired,
                                originalDose = originalDose,
                                iobDelta = iobDelta,
                                currentCob = currentCob,
                                originalCarbs = originalCarbs,
                                cobFraction = cobFraction,
                                gapAfterIob = gapAfterIob,
                                rawDose = rawDose,
                                multiplier = multiplier,
                                dbLabel = dbLabel
                            )
                    }
                })
            }
        } else {
            val reason = when {
                gs == null   -> "no BGL data"
                !bglFresh    -> "BGL stale (${(now - gs.date) / 60000} min old)"
                !criteriaOk  -> "glucose criteria not met (BGL=$bglStr)"
                else         -> "unknown"
            }
            if (attempt < 8) {
                aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt: $reason — scheduling attempt ${attempt + 1} in 10 min")
                addCheckNote("$dbLabel wait")
                enqueue(applicationContext, originalDose, fullRequired, attempt + 1, originalTime, originalCarbs, originalIob)
            } else {
                aapsLogger.info(LTag.CORE, "Delayed bolus: $reason at attempt $attempt — no delayed dose delivered")
                addCheckNote("$dbLabel end")
                unblockSmb("gave up after attempt $attempt")
            }
        }
        return Result.success()
    }
}
