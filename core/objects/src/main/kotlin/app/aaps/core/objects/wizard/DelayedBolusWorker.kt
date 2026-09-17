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
import app.aaps.core.objects.utils.StepCountSource
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Delayed bolus (50%-profile wizard mechanism — NOT the equal-parts split bolus):
 * after a wizard bolus on the 50% profile, re-checks BG every 10 min from 10 through 80
 * minutes and delivers the remaining gap once glucose criteria confirm a rise.
 * Still moving now (S30>=200, S5 OR watch only): keep MOVING_PERCENT (70) of the
 * remaining gap. Seated: deliver the full remaining gap toward standing wiz%.
 * No elapsed-time 90/50 haircut.
 * Formerly named SplitBolusWorker; renamed to keep the two mechanisms unmistakable.
 *
 * Remainder is the wizard top-up that was promised, then scaled by leftover carbs:
 *   delayed = max(0, (fullRequired − originalDose) × cobFraction × seated/moving)
 * cobFraction = current COB ÷ the ORIGINAL carbs entered (from BolusWizard), clamped to [0,1].
 * 1.0 = nothing absorbed yet; 0.0 = fully absorbed (no further carb-side dose).
 *
 * 2026-09-15: do NOT subtract IOB growth since the original bolus. The 2026-08-26 iobDelta
 * term (current total IOB − IOB at the wizard) treated 30 min of SMBs/TBR as already covering
 * the delayed remainder. Real 14 Sep Db30: remainder collapsed to 0.1U while BGL 6.1, COB 8.5,
 * live Req 1.62, IOB had grown ~1.3U. That is the loop doing its job, not the delayed top-up
 * being finished. Last-5-min IOBd5 would still have crushed it. originalIob is still passed
 * through from the wizard for in-flight WorkManager jobs and is ignored in the dose math.
 *
 * 2026-09-15 (same day, follow-up): with no IOB term at all, a still-high cobFraction alone
 * could in principle hand back the full remainder even after the loop's own current view of
 * things has moved on from the ~80-min-old wizard-time fullRequired estimate. Capped rawDose
 * against liveInsulinReq (RT.insulinReq, mirrored every cycle by OpenAPSAutoISFPlugin into
 * LongKey.ApsAutoIsfLastCycleInsulinReqMilliU since this class can't see that plugin's own
 * private field) -- the loop's CURRENT assessment of how much more insulin is actually still
 * needed. This is the same 1.62U figure from the Db30 case above: a cap at that level would not
 * have crushed it (1.62 comfortably exceeds any correctly-scaled remainder), unlike the discarded
 * iobDelta/IOBd5 approaches. Only applied when the loop has run within the last 10 min
 * (APS.lastAPSRun); a stalled loop must not silently zero-cap every delayed dose.
 */
class DelayedBolusWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var stepCountSource: StepCountSource
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
    // details explain the remainder × COB scale and explicitly close the one-shot delayed sequence.
    // Shared trailer for both addDeliveredCalcTreatment and the "covered" note below: live InsReq (see
    // class doc comment) plus whatever BolusWizard's own carb-split and Warsaw-FPU series still have
    // outstanding, from LongKey.ApsAutoIsfPending{Split,Warsaw}RemainingMilliU. Shown unconditionally
    // (not just when InsReq actually caps the dose) -- these are ALL separate insulin sources DelayedBolusWorker's
    // own calc has no other visibility into, so surfacing them at every check, not only when one of them
    // happens to bind, is the point. Added 2026-09-16.
    private fun pendingSourcesLine(liveInsulinReqCap: Double?, pendingSplit: Double, pendingWarsaw: Double): String =
        "InsReq ${liveInsulinReqCap?.let { Round.roundTo(it, 0.01) } ?: "n/a"}U; " +
            "other pending: split ${Round.roundTo(pendingSplit, 0.01)}U, Warsaw ${Round.roundTo(pendingWarsaw, 0.01)}U"

    private fun addDeliveredCalcTreatment(
        gs: GlucoseStatus,
        delayedDose: Double,
        fullRequired: Double,
        originalDose: Double,
        currentCob: Double,
        originalCarbs: Double,
        cobFraction: Double,
        remainder: Double,
        rawDose: Double,
        liveInsulinReqCap: Double?,
        cappedRawDose: Double,
        pendingSplit: Double,
        pendingWarsaw: Double,
        multiplier: Double,
        dbLabel: String
    ) {
        val profile = profileFunction.getProfile()
        val multiplierPct = (multiplier * 100).toInt()
        val capNote = if (liveInsulinReqCap != null && cappedRawDose < rawDose)
            " (capped to InsReq -> ${Round.roundTo(cappedRawDose, 0.01)}U)"
        else ""
        val note = "$dbLabel delayed bolus: ${Round.roundTo(delayedDose, 0.01)}U delivered\n" +
            "Gate BG ${Round.roundTo(gs.glucose / 18.0182, 0.01)}, D ${Round.roundTo(gs.delta / 18.0182, 0.01)}, " +
            "SD ${Round.roundTo(gs.shortAvgDelta / 18.0182, 0.01)}, LD ${Round.roundTo(gs.longAvgDelta / 18.0182, 0.01)} mmol/L\n" +
            "Full required ${Round.roundTo(fullRequired, 0.01)}U - initial ${Round.roundTo(originalDose, 0.01)}U " +
            "= ${Round.roundTo(remainder, 0.01)}U\n" +
            "COB ${Round.roundTo(currentCob, 0.1)}/${Round.roundTo(originalCarbs, 0.1)}g " +
            "(x${Round.roundTo(cobFraction, 0.01)}) -> ${Round.roundTo(rawDose, 0.01)}U$capNote; " +
            "${if (multiplier < 1.0) "still moving" else "seated"} $multiplierPct%\n" +
            "${pendingSourcesLine(liveInsulinReqCap, pendingSplit, pendingWarsaw)}\n" +
            "Delayed sequence complete; no residual pending"
        persistenceLayer.insertOrUpdateBolusCalculatorResult(
            BCR(
                timestamp = dateUtil.now(),
                targetBGLow = profile?.getTargetLowMgdl() ?: 0.0,
                targetBGHigh = profile?.getTargetHighMgdl() ?: 0.0,
                isf = profile?.getIsfMgdl("DelayedBolusWorker") ?: 0.0,
                ic = profile?.getIc() ?: 0.0,
                bolusIOB = 0.0,
                wasBolusIOBUsed = false,
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

    // Covered-case counterpart to addDeliveredCalcTreatment above: a real BCR "Calc" row (0U) so the
    // same breakdown -- including InsReq and the other-mechanism pending amounts -- is visible in
    // Treatments even when nothing was delivered, not just a short 5-char careportal code. Added
    // 2026-09-16, matching the user's explicit ask to show this at every trigger OR cancellation.
    private fun addCoveredCalcTreatment(
        gs: GlucoseStatus,
        fullRequired: Double,
        originalDose: Double,
        currentCob: Double,
        originalCarbs: Double,
        cobFraction: Double,
        remainder: Double,
        rawDose: Double,
        liveInsulinReqCap: Double?,
        pendingSplit: Double,
        pendingWarsaw: Double,
        dbLabel: String
    ) {
        val profile = profileFunction.getProfile()
        val note = "$dbLabel delayed bolus: covered, nothing delivered\n" +
            "Gate BG ${Round.roundTo(gs.glucose / 18.0182, 0.01)}, D ${Round.roundTo(gs.delta / 18.0182, 0.01)}, " +
            "SD ${Round.roundTo(gs.shortAvgDelta / 18.0182, 0.01)}, LD ${Round.roundTo(gs.longAvgDelta / 18.0182, 0.01)} mmol/L\n" +
            "Full required ${Round.roundTo(fullRequired, 0.01)}U - initial ${Round.roundTo(originalDose, 0.01)}U " +
            "= ${Round.roundTo(remainder, 0.01)}U\n" +
            "COB ${Round.roundTo(currentCob, 0.1)}/${Round.roundTo(originalCarbs, 0.1)}g " +
            "(x${Round.roundTo(cobFraction, 0.01)}) -> ${Round.roundTo(rawDose, 0.01)}U -> 0.00U after rounding/cap\n" +
            pendingSourcesLine(liveInsulinReqCap, pendingSplit, pendingWarsaw)
        persistenceLayer.insertOrUpdateBolusCalculatorResult(
            BCR(
                timestamp = dateUtil.now(),
                targetBGLow = profile?.getTargetLowMgdl() ?: 0.0,
                targetBGHigh = profile?.getTargetHighMgdl() ?: 0.0,
                isf = profile?.getIsfMgdl("DelayedBolusWorker") ?: 0.0,
                ic = profile?.getIc() ?: 0.0,
                bolusIOB = 0.0,
                wasBolusIOBUsed = false,
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
                otherCorrection = 0.0,
                wasSuperbolusUsed = false,
                superbolusInsulin = 0.0,
                wasTempTargetUsed = false,
                totalInsulin = 0.0,
                percentageCorrection = 0,
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
        // originalCarbs (added 2026-08-26): wizard carb input, carried through every retry so cobFraction
        // always compares against the true original amount. originalIob is still accepted for in-flight
        // WorkManager jobs and ignored in the dose math (2026-09-15).
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
            // Remainder is the wizard top-up, then COB scale. IOB growth since the original bolus
            // is not subtracted (14 Sep Db30: that term collapsed the delayed dose to 0.1U).
            val currentCob = iobCobCalculator.getCobInfo("DelayedBolusWorker").displayCob ?: 0.0
            val cobFraction = if (originalCarbs > 0) (currentCob / originalCarbs).coerceIn(0.0, 1.0) else 1.0

            val remainder = (fullRequired - originalDose).coerceAtLeast(0.0)
            val rawDose = remainder * cobFraction

            // Live InsReq cap (see class doc comment): only trust it while the loop has actually run
            // recently -- a stalled loop's stale cached value must never silently zero-cap a dose.
            val liveInsulinReqAgeMs = now - activePlugin.activeAPS.lastAPSRun
            val liveInsulinReqCap: Double? =
                if (liveInsulinReqAgeMs in 0..T.mins(10).msecs())
                    preferences.get(LongKey.ApsAutoIsfLastCycleInsulinReqMilliU) / 1000.0
                else null
            val cappedRawDose = if (liveInsulinReqCap != null) min(rawDose, max(0.0, liveInsulinReqCap)) else rawDose

            // Other pending amounts from BolusWizard's own carb-split/Warsaw-FPU series -- see
            // LongKey.ApsAutoIsfPending{Split,Warsaw}RemainingMilliU's own doc comment. Shown, not
            // gated into the dose math itself (a separate change, if ever wanted).
            val pendingSplit = preferences.get(LongKey.ApsAutoIsfPendingSplitRemainingMilliU) / 1000.0
            val pendingWarsaw = preferences.get(LongKey.ApsAutoIsfPendingWarsawRemainingMilliU) / 1000.0

            val elapsedMin = attempt * 10
            val movingNow = WizardActivitySteps.stillMovingNow(stepCountSource, now)
            if (movingNow == null) {
                aapsLogger.info(LTag.CORE, "Delayed bolus: Live steps unavailable; no automatic dose")
                addCheckNote("$dbLabel Live steps unavailable")
                unblockSmb("Live steps unavailable")
                return Result.success()
            }
            // Seated: full remaining gap (already sized to standing wiz%). S30 still moving: 70%.
            val multiplier = if (movingNow) WizardActivitySteps.MOVING_PERCENT / 100.0 else 1.0
            val delayedDose = Round.roundTo(max(0.0, cappedRawDose * multiplier), activePlugin.activePump.pumpDescription.bolusStep)
            if (delayedDose <= 0.0) {
                aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt (${elapsedMin}min): criteria met BGL=$bglStr but already covered — cobFraction=${Round.roundTo(cobFraction, 0.01)} liveInsReqCap=${liveInsulinReqCap?.let { Round.roundTo(it, 0.01) } ?: "n/a"} pendingSplit=${Round.roundTo(pendingSplit, 0.01)}U pendingWarsaw=${Round.roundTo(pendingWarsaw, 0.01)}U (fullRequired=${fullRequired}U given=${originalDose}U) — no delayed dose needed")
                addCheckNote("$dbLabel covered")
                addCoveredCalcTreatment(
                    gs = gs,
                    fullRequired = fullRequired,
                    originalDose = originalDose,
                    currentCob = currentCob,
                    originalCarbs = originalCarbs,
                    cobFraction = cobFraction,
                    remainder = remainder,
                    rawDose = rawDose,
                    liveInsulinReqCap = liveInsulinReqCap,
                    pendingSplit = pendingSplit,
                    pendingWarsaw = pendingWarsaw,
                    dbLabel = dbLabel
                )
                unblockSmb("covered by COB check")
                return Result.success()
            }
            aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt (${elapsedMin}min): criteria met BGL=$bglStr — delivering ${delayedDose}U (fullRequired=${fullRequired}U given=${originalDose}U cobFraction=${Round.roundTo(cobFraction, 0.01)} remainder=${Round.roundTo(rawDose, 0.01)}U liveInsReqCap=${liveInsulinReqCap?.let { Round.roundTo(it, 0.01) } ?: "n/a"} capped=${Round.roundTo(cappedRawDose, 0.01)}U pendingSplit=${Round.roundTo(pendingSplit, 0.01)}U pendingWarsaw=${Round.roundTo(pendingWarsaw, 0.01)}U × ${(multiplier*100).toInt()}% ${if (movingNow) "still moving" else "seated"})")
            addCheckNote("$dbLabel ${delayedDose}U")
            unblockSmb("delivering")
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = delayedDose
                notes = "Delayed bolus attempt $attempt (full required ${fullRequired}U − given ${originalDose}U, × cobFraction ${Round.roundTo(cobFraction, 0.01)}" +
                    (liveInsulinReqCap?.let { ", capped to live InsReq ${Round.roundTo(it, 0.01)}U" } ?: "") +
                    ", × ${(multiplier*100).toInt()}%; ${pendingSourcesLine(liveInsulinReqCap, pendingSplit, pendingWarsaw)})"
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
                                currentCob = currentCob,
                                originalCarbs = originalCarbs,
                                cobFraction = cobFraction,
                                remainder = remainder,
                                rawDose = rawDose,
                                liveInsulinReqCap = liveInsulinReqCap,
                                cappedRawDose = cappedRawDose,
                                pendingSplit = pendingSplit,
                                pendingWarsaw = pendingWarsaw,
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
