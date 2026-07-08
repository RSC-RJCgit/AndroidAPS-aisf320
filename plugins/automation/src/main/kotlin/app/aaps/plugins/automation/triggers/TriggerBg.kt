package app.aaps.plugins.automation.triggers

import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.elements.InputBg
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Optional
import kotlin.math.roundToInt

class TriggerBg(injector: HasAndroidInjector) : Trigger(injector) {

    enum class GlucoseSource { AAPS, RAW_CGM }

    var bg = InputBg(profileFunction)
    var comparator = Comparator(rh)
    var glucoseSource = GlucoseSource.AAPS

    constructor(injector: HasAndroidInjector, value: Double, units: GlucoseUnit, compare: Comparator.Compare) : this(injector) {
        bg = InputBg(profileFunction, value, units)
        comparator = Comparator(rh, compare)
    }

    constructor(injector: HasAndroidInjector, triggerBg: TriggerBg) : this(injector) {
        bg = InputBg(profileFunction, triggerBg.bg.value, triggerBg.bg.units)
        comparator = Comparator(rh, triggerBg.comparator.value)
        glucoseSource = triggerBg.glucoseSource
    }

    fun setUnits(units: GlucoseUnit): TriggerBg {
        bg.units = units
        return this
    }

    fun setValue(value: Double): TriggerBg {
        bg.value = value
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerBg {
        this.comparator.value = comparator
        return this
    }

    // Uses gv.noise (Libre raw native signal) — same field as the red raw-BG line and "L=" graph annotation.
    private fun rawGlucoseMgdl(): Double? {
        val now = System.currentTimeMillis()
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 10 * 60 * 1000L, now, ascending = false)
        return readings.firstOrNull()?.noise
    }

    override fun shouldRun(): Boolean {
        val glucoseMgdl: Double? = when (glucoseSource) {
            GlucoseSource.AAPS    -> glucoseStatusProvider.glucoseStatusData?.glucose
            GlucoseSource.RAW_CGM -> rawGlucoseMgdl()
        }
        if (glucoseMgdl == null && comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
            return true
        }
        if (glucoseMgdl == null) {
            aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
            return false
        }
        if (comparator.value.check(glucoseMgdl, profileUtil.convertToMgdl(bg.value, bg.units), 0.001)) {
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: ${glucoseSource.name} glucose=${glucoseMgdl} " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("bg", bg.value)
            .put("comparator", comparator.value.toString())
            .put("units", bg.units.asText)
            .put("glucoseSource", glucoseSource.name)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        bg.setUnits(GlucoseUnit.fromText(JsonHelper.safeGetString(d, "units", GlucoseUnit.MGDL.asText)))
        bg.value = JsonHelper.safeGetDouble(d, "bg")
        comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!))
        glucoseSource = GlucoseSource.valueOf(JsonHelper.safeGetString(d, "glucoseSource", GlucoseSource.AAPS.name))
        return this
    }

    override fun friendlyName(): Int = app.aaps.core.ui.R.string.glucose

    override fun friendlyDescription(): String {
        return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE)
            rh.gs(R.string.glucoseisnotavailable)
        else {
            val srcLabel = if (glucoseSource == GlucoseSource.RAW_CGM) " [Raw]" else ""
            rh.gs(if (bg.units == GlucoseUnit.MGDL) R.string.glucosecomparedmgdl else R.string.glucosecomparedmmol, comparator.value.shortSymbol, bg.value, bg.units) + srcLabel
        }
    }

    override fun icon(): Optional<Int> = Optional.of(app.aaps.core.objects.R.drawable.ic_cp_bgcheck)

    override fun duplicate(): Trigger = TriggerBg(injector, this)

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, app.aaps.core.ui.R.string.glucose, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.glucose_u, bg.units), "", bg))
            .build(root)
        // Source dropdown: AAPS (smoothed) vs Raw CGM
        root.addView(Spinner(root.context).apply {
            adapter = ArrayAdapter(root.context, android.R.layout.simple_spinner_item, listOf("AAPS (smoothed)", "Raw CGM")).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(glucoseSource.ordinal)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
            gravity = Gravity.CENTER_HORIZONTAL
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    glucoseSource = GlucoseSource.entries[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
    }
}