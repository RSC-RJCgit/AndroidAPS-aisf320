package app.aaps.core.objects.wizard

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
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
 * after a wizard bolus on the 50% profile, re-checks BG at 10/20/30 minutes and
 * delivers the remaining gap × 90% once glucose criteria confirm a rise.
 * Formerly named SplitBolusWorker; renamed to keep the two mechanisms unmistakable.
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

    companion object {
        const val WORK_NAME = "DelayedBolusWork"
        const val KEY_ORIGINAL_DOSE = "originalDose"
        const val KEY_FULL_REQUIRED = "fullRequired"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_ORIGINAL_TIME = "originalTime"

        private val DELAYED_BGL_MGDL   = 4.5  * 18.0182
        private val DELAYED_DELTA_MGDL = 0.1  * 18.0182
        private val DELAYED_SD_MGDL    = 0.2  * 18.0182
        private val DELAYED_LD_MGDL    = 0.05 * 18.0182
        private val DELAYED_BGL_AGE_MS = T.mins(5).msecs()

        // Flat 10-minute poll: each call (first attempt or retry) waits 10 min from whenever it's made,
        // for up to 3 attempts total, then gives up. originalTime is just carried along for logging
        // (total elapsed time since the original bolus), it doesn't affect the delay.
        fun enqueue(context: Context, originalDose: Double, fullRequired: Double, attempt: Int, originalTime: Long = System.currentTimeMillis()) {
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
                            KEY_ORIGINAL_TIME to originalTime
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
            gs.shortAvgDelta > DELAYED_SD_MGDL &&
            gs.longAvgDelta > DELAYED_LD_MGDL
        val bglStr = gs?.let { String.format("%.1f", it.glucose / 18.0182) } ?: "n/a"

        val dbLabel = "Db${attempt * 10}"

        if (criteriaOk && bglFresh) {
            val rawDose = fullRequired - originalDose
            val delayedDose = Round.roundTo(max(0.0, rawDose * 0.90), activePlugin.activePump.pumpDescription.bolusStep)
            aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt: criteria met BGL=$bglStr — delivering ${delayedDose}U (fullRequired=${fullRequired}U given=${originalDose}U gap=${rawDose}U × 90%)")
            addCheckNote("$dbLabel ${delayedDose}U")
            unblockSmb("delivering")
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = delayedDose
                notes = "Delayed bolus attempt $attempt (full required ${fullRequired}U − given ${originalDose}U × 90%)"
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
            if (attempt < 3) {
                aapsLogger.info(LTag.CORE, "Delayed bolus attempt $attempt: $reason — scheduling attempt ${attempt + 1} in 10 min")
                addCheckNote("$dbLabel wait")
                enqueue(applicationContext, originalDose, fullRequired, attempt + 1, originalTime)
            } else {
                aapsLogger.info(LTag.CORE, "Delayed bolus: $reason at attempt $attempt — no delayed dose delivered")
                addCheckNote("$dbLabel end")
                unblockSmb("gave up after attempt $attempt")
            }
        }
        return Result.success()
    }
}
