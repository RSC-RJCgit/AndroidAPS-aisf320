package app.aaps.plugins.automation.triggers

import android.widget.LinearLayout
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.core.utils.JsonHelper.safeGetString
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputDuration
import app.aaps.plugins.automation.elements.InputInsulin
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional

/**
 * Fires on the change in IOB over the last [minutesAgo] minutes (default 5): IOB now minus IOB
 * that many minutes ago, compared against [insulin]. A positive value means IOB rose (e.g. after
 * an SMB/bolus), negative means it fell. Mirrors TriggerDelta but for insulin-on-board instead of BG.
 */
class TriggerIobDelta(injector: HasAndroidInjector) : Trigger(injector) {

    var insulin = InputInsulin()
    var minutesAgo: InputDuration = InputDuration(5, InputDuration.TimeUnit.MINUTES)
    var comparator: Comparator = Comparator(rh)

    private constructor(injector: HasAndroidInjector, other: TriggerIobDelta) : this(injector) {
        insulin = InputInsulin(other.insulin)
        minutesAgo = InputDuration(other.minutesAgo.value, InputDuration.TimeUnit.MINUTES)
        comparator = Comparator(rh, other.comparator.value)
    }

    fun setValue(value: Double): TriggerIobDelta {
        insulin.value = value
        return this
    }

    fun setMinutes(minutes: Int): TriggerIobDelta {
        minutesAgo.setMinutes(minutes)
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerIobDelta {
        this.comparator.value = comparator
        return this
    }

    override fun shouldRun(): Boolean {
        val profile = profileFunction.getProfile() ?: return false
        val now = dateUtil.now()
        val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile).iob
        val iobThen = iobCobCalculator.calculateFromTreatmentsAndTemps(now - minutesAgo.getMinutes() * 60_000L, profile).iob
        val diff = iobNow - iobThen
        if (comparator.value.check(diff, insulin.value, 0.001)) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription() + " (Δ=$diff)")
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription() + " (Δ=$diff)")
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("insulin", insulin.value)
            .put("minutesAgo", minutesAgo.value)
            .put("comparator", comparator.value.toString())

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        insulin.value = JsonHelper.safeGetDouble(d, "insulin")
        minutesAgo.setMinutes(JsonHelper.safeGetInt(d, "minutesAgo"))
        comparator.setValue(Comparator.Compare.valueOf(safeGetString(d, "comparator")!!))
        return this
    }

    override fun friendlyName(): Int = R.string.iob_delta_label

    override fun friendlyDescription(): String =
        rh.gs(R.string.iob_delta_compared, comparator.value.shortSymbol, insulin.value, minutesAgo.getMinutes())

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_keyboard_capslock)

    override fun duplicate(): Trigger = TriggerIobDelta(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.iob_delta_label, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.iob_u), "", insulin))
            .add(LabelWithElement(rh, rh.gs(R.string.iob_delta_over) + ": ", rh.gs(app.aaps.core.interfaces.R.string.unit_minutes), minutesAgo))
            .build(root)
    }
}
