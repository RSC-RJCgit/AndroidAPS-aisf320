package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.utils.JsonHelper.safeGetDouble
import app.aaps.core.utils.JsonHelper.safeGetString
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDouble
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.Optional
import javax.inject.Inject

class TriggerTddRatio(injector: HasAndroidInjector) : Trigger(injector) {

    @Inject lateinit var tddCalculator: TddCalculator

    // Ratio is displayed and stored as a percentage (e.g. 110.0 = 1.10 ratio)
    var ratio: InputDouble = InputDouble(100.0, 70.0, 150.0, 1.0, DecimalFormat("0"))
    var comparator: Comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, trigger: TriggerTddRatio) : this(injector) {
        ratio = InputDouble(trigger.ratio)
        comparator = Comparator(rh, trigger.comparator.value)
    }

    fun setValue(value: Double): TriggerTddRatio {
        ratio.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerTddRatio {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val tddRatio = calculateTddRatio()
        if (tddRatio == null) {
            return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
                aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
                true
            } else {
                aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
                false
            }
        }
        // ratio input is in percent (e.g. 110), compare against actual ratio (e.g. 1.10)
        if (comparator.value.check(tddRatio, ratio.value / 100.0)) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    // Mirrors the blended TDD ratio calculation used in OpenAPSAutoISFPlugin.
    // blendedTDD = weighted mix of 8H-projected, 7D average, and 1D average.
    // ratio = blendedTDD / tdd7D
    private fun calculateTddRatio(): Double? {
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
        val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount
        val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        if (tdd7D == null || tdd7D <= 0.0 || tdd1D == null || tddLast4H == null || tddLast8to4H == null) return null

        val w8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
        val blendedTDD = if (w8H < 0.75 * tdd7D) {
            val adj7D = w8H + ((w8H / tdd7D) * (tdd7D - w8H))
            (adj7D * 0.34) + (tdd1D * 0.33) + (w8H * 0.33)
        } else {
            (w8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
        }
        return blendedTDD / tdd7D
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("ratio", ratio.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        ratio.setValue(safeGetDouble(d, "ratio"))
        comparator.setValue(Comparator.Compare.valueOf(safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.triggerTddRatioLabel

    override fun friendlyDescription(): String =
        rh.gs(R.string.triggerTddRatioDescription, rh.gs(comparator.value.stringRes), ratio.value)

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_cp_bolus_insulin)

    override fun duplicate(): Trigger = TriggerTddRatio(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.triggerTddRatioLabel, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.triggerTddRatioLabel) + ": ", "%", ratio))
            .build(root)
    }
}
