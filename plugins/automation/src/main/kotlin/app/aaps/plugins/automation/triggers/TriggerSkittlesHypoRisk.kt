package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional
import javax.inject.Inject

/**
 * Combined hypo-risk trigger replacing 3 separate Skittles automations:
 *   Skittles3ok2BG9.0, SkittlesA3ok8.0,5.0,6.0, SkittlesTT3CurrP02
 *
 * Returns true when ANY of 7 clinical AND blocks indicates impending hypoglycaemia.
 * All thresholds are hardcoded in mg/dL (original automations used mmol/L).
 * No user-configurable parameters — this trigger has no dialog inputs.
 *
 * Block C corrects the original SkittlesTT3 Block 3 (was OR(<=2.5,<=3.0); now <=3.5 AND delta<=0).
 */
class TriggerSkittlesHypoRisk(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var automationStateService: AutomationStateInterface

    private constructor(injector: HasAndroidInjector, @Suppress("UNUSED_PARAMETER") other: TriggerSkittlesHypoRisk) : this(injector)

    companion object {
        // Glucose thresholds in mg/dL — original values in mmol/L shown in comments
        private const val G_63  = 63.1   // 3.5 mmol/L
        private const val G_81  = 81.1   // 4.5 mmol/L
        private const val G_90  = 90.1   // 5.0 mmol/L
        private const val G_108 = 108.1  // 6.0 mmol/L
        private const val G_117 = 117.1  // 6.5 mmol/L
        private const val G_162 = 162.1  // 9.0 mmol/L
        private const val G_171 = 171.2  // 9.5 mmol/L

        // Delta/SDelta/LDelta thresholds in mg/dL (5-min equivalent, as stored in GlucoseStatus)
        private const val D_005  = -0.90  // -0.05 mmol/L
        private const val D_020  = -3.60  // -0.20 mmol/L
        private const val D_285  = -5.13  // -0.285 mmol/L
        private const val D_040  = -7.21  // -0.40 mmol/L
        private const val D_050  = -9.01  // -0.50 mmol/L
        private const val D_080  = -14.41 // -0.80 mmol/L
        private const val D_090  = -16.21 // -0.90 mmol/L
        private const val D_110  = -19.82 // -1.10 mmol/L
    }

    override fun shouldRun(): Boolean {
        val gs = glucoseStatusProvider.glucoseStatusData ?: run {
            aapsLogger.debug(LTag.AUTOMATION, "NOT ready: glucose data unavailable — ${friendlyDescription()}")
            return false
        }
        val profile = profileFunction.getProfile() ?: run {
            aapsLogger.debug(LTag.AUTOMATION, "NOT ready: no profile — ${friendlyDescription()}")
            return false
        }

        val glucose = gs.glucose
        val delta   = gs.delta
        val sdelta  = gs.shortAvgDelta
        val ldelta  = gs.longAvgDelta
        val iob     = iobCobCalculator.calculateFromTreatmentsAndTemps(dateUtil.now(), profile).iob
        val cob     = iobCobCalculator.getCobInfo("TriggerSkittlesHypoRisk").displayCob ?: 0.0
        val pct     = profile.percentage

        val lastBolus = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)
        val lastBolusMinAgo = if (lastBolus != null) (dateUtil.now() - lastBolus.timestamp) / 60_000.0 else Double.MAX_VALUE

        // Gate shared by most blocks: profile >= 65% and last bolus >= 5 min ago
        val stdGate = pct >= 65 && lastBolusMinAgo >= 5.0

        // Block A — SkittlesTT3 Block 1: fallback at very low glucose (BGL/data issues tolerated)
        val blockA = glucose <= G_81 && delta <= D_005 && iob >= -0.2 &&
            sdelta <= D_005 && ldelta <= D_005 && cob <= 15.0 && stdGate

        // Block B — SkittlesTT3 Block 2: moderate fall with active insulin
        val blockB = glucose <= G_90 && delta <= D_285 && iob >= 0.3 &&
            sdelta <= D_020 && ldelta <= D_020 && stdGate &&
            (cob <= 15.0 || lastBolusMinAgo >= 60.0)

        // Block C — SkittlesTT3 Block 3 (FIXED): emergency floor — any non-rising BGL below 3.5
        val blockC = glucose <= G_63 && delta <= 0.0

        // Block D — SkittlesA3ok Block 1: rapid sustained fall at target glucose with high IOB
        val blockD = glucose <= G_108 && delta <= D_090 && sdelta <= D_090 &&
            iob > 2.8 && stdGate

        // Block E — SkittlesA3ok Block 2: moderate multi-timeframe fall
        val blockE = glucose <= G_117 && delta <= D_050 && sdelta <= D_040 &&
            ldelta <= D_020 && iob >= 1.5 && cob <= 15.0 && stdGate

        // Block F — SkittlesA3ok Block 3: very high IOB at higher glucose, only when steroids off
        val blockF = glucose <= G_162 && delta <= D_050 && sdelta <= D_040 &&
            ldelta <= D_040 && iob >= 2.9 && cob <= 15.0 && stdGate &&
            automationStateService.inState("Steroids", "Steroids Off")

        // Block G — Skittles3ok2BG9.0: tight multi-delta confirmation at higher glucose
        val blockG = glucose <= G_171 && delta <= D_110 && sdelta <= D_080 &&
            ldelta <= D_080 && iob >= 1.4 && cob <= 15.0 && stdGate

        val result = blockA || blockB || blockC || blockD || blockE || blockF || blockG

        val whichBlock = when {
            blockA -> "A"
            blockB -> "B"
            blockC -> "C"
            blockD -> "D"
            blockE -> "E"
            blockF -> "F"
            blockG -> "G"
            else   -> "—"
        }

        aapsLogger.debug(
            LTag.AUTOMATION,
            if (result) "Ready [block $whichBlock] gluc=${String.format("%.1f", glucose / 18.016)}mmol d=${String.format("%.2f", delta / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} pct=$pct: ${friendlyDescription()}"
            else "NOT ready gluc=${String.format("%.1f", glucose / 18.016)}mmol d=${String.format("%.2f", delta / 18.016)} iob=${String.format("%.2f", iob)} cob=${cob.toInt()} pct=$pct: ${friendlyDescription()}"
        )
        return result
    }

    override fun dataJSON(): JSONObject = JSONObject()

    override fun fromJSON(data: String): Trigger = this

    override fun friendlyName(): Int = R.string.triggerSkittlesHypoRiskLabel

    override fun friendlyDescription(): String = "Skittles hypo risk (7-block combined)"

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_cp_bgcheck)

    override fun duplicate(): Trigger = TriggerSkittlesHypoRisk(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.triggerSkittlesHypoRiskLabel, this))
            .build(root)
    }
}
