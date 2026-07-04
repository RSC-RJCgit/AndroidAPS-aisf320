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
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max

class SplitBolusWorker(
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

    companion object {
        const val WORK_NAME = "SplitBolusWork"
        const val KEY_ORIGINAL_DOSE = "originalDose"
        const val KEY_FULL_REQUIRED = "fullRequired"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_ORIGINAL_TIME = "originalTime"

        private val SPLIT_BGL_MGDL   = 4.5  * 18.0182
        private val SPLIT_DELTA_MGDL = 0.1  * 18.0182
        private val SPLIT_SD_MGDL    = 0.2  * 18.0182
        private val SPLIT_LD_MGDL    = 0.05 * 18.0182
        private val SPLIT_BGL_AGE_MS = T.mins(5).msecs()

        // Flat 10-minute poll: each call (first attempt or retry) waits 10 min from whenever it's made,
        // for up to 3 attempts total, then gives up. originalTime is just carried along for logging
        // (total elapsed time since the original bolus), it doesn't affect the delay.
        fun enqueue(context: Context, originalDose: Double, fullRequired: Double, attempt: Int, originalTime: Long = System.currentTimeMillis()) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(SplitBolusWorker::class.java)
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

        if (BolusProgressData.splitBolusCancelled) {
            aapsLogger.info(LTag.CORE, "Split bolus attempt $attempt: cancelled by user")
            return Result.success()
        }

        val gs = glucoseStatusProvider.glucoseStatusData
        val now = dateUtil.now()
        val bglFresh = gs != null && (now - gs.date) <= SPLIT_BGL_AGE_MS
        val criteriaOk = gs != null &&
            gs.glucose > SPLIT_BGL_MGDL &&
            gs.delta > SPLIT_DELTA_MGDL &&
            gs.shortAvgDelta > SPLIT_SD_MGDL &&
            gs.longAvgDelta > SPLIT_LD_MGDL
        val bglStr = gs?.let { String.format("%.1f", it.glucose / 18.0182) } ?: "n/a"

        if (criteriaOk && bglFresh) {
            val rawDose = fullRequired - originalDose
            val splitDose = Round.roundTo(max(0.0, rawDose * 0.90), activePlugin.activePump.pumpDescription.bolusStep)
            aapsLogger.info(LTag.CORE, "Split bolus attempt $attempt: criteria met BGL=$bglStr — delivering ${splitDose}U (fullRequired=${fullRequired}U given=${originalDose}U gap=${rawDose}U × 90%)")
            DetailedBolusInfo().apply {
                eventType = TE.Type.CORRECTION_BOLUS
                insulin = splitDose
                notes = "Split bolus attempt $attempt (full required ${fullRequired}U − given ${originalDose}U × 90%)"
                uel.log(
                    action = Action.BOLUS,
                    source = Sources.WizardDialog,
                    note = notes,
                    listValues = listOf(ValueWithUnit.Insulin(splitDose))
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
                aapsLogger.info(LTag.CORE, "Split bolus attempt $attempt: $reason — scheduling attempt ${attempt + 1} in 10 min")
                enqueue(applicationContext, originalDose, fullRequired, attempt + 1, originalTime)
            } else {
                aapsLogger.info(LTag.CORE, "Split bolus: $reason at attempt $attempt — no split dose delivered")
            }
        }
        return Result.success()
    }
}
