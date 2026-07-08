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
import app.aaps.plugins.automation.elements.InputDelta
import app.aaps.plugins.automation.elements.InputDelta.DeltaType
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.elements.StaticLabel
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.Optional

class TriggerDelta(injector: HasAndroidInjector) : Trigger(injector) {

    // AAPS: uses smoothed GlucoseStatus delta/shortAvgDelta/longAvgDelta (DeltaType dropdown active)
    // RAW_1MIN: gv.noise delta between last 2 readings, scaled to 5-min equivalent (DeltaType ignored)
    // RAW_5MIN: gv.noise delta between latest reading and reading ~5 min ago (DeltaType ignored)
    enum class DeltaSource {
        AAPS, RAW_1MIN, RAW_5MIN;
        val label get() = when (this) {
            AAPS     -> "AAPS (smoothed)"
            RAW_1MIN -> "Raw – 1 min ×5"
            RAW_5MIN -> "Raw – 5 min"
        }
        companion object { fun labels() = entries.map { it.label } }
    }

    var units: GlucoseUnit = GlucoseUnit.MGDL
    var delta: InputDelta = InputDelta(rh)
    var comparator: Comparator = Comparator(rh)
    var deltaSource = DeltaSource.AAPS

    companion object {
        private const val MMOL_MAX = 4.0
        private const val MGDL_MAX = 72.0
    }

    init {
        units = profileFunction.getUnits()
        delta = if (units == GlucoseUnit.MMOL) InputDelta(rh, 0.0, (-MMOL_MAX), MMOL_MAX, 0.1, DecimalFormat("0.1"), DeltaType.DELTA)
        else InputDelta(rh, 0.0, (-MGDL_MAX), MGDL_MAX, 1.0, DecimalFormat("1"), DeltaType.DELTA)
    }

    constructor(injector: HasAndroidInjector, inputDelta: InputDelta, units: GlucoseUnit, comparator: Comparator.Compare) : this(injector) {
        this.units = units
        this.delta = inputDelta
        this.comparator.value = comparator
    }

    private constructor(injector: HasAndroidInjector, triggerDelta: TriggerDelta) : this(injector) {
        units = triggerDelta.units
        delta = InputDelta(rh, triggerDelta.delta)
        comparator = Comparator(rh, triggerDelta.comparator.value)
        deltaSource = triggerDelta.deltaSource
    }

    fun units(units: GlucoseUnit): TriggerDelta {
        this.units = units
        return this
    }

    fun setValue(value: Double, type: DeltaType): TriggerDelta {
        this.delta.value = value
        this.delta.deltaType = type
        return this
    }

    fun comparator(comparator: Comparator.Compare): TriggerDelta {
        this.comparator.value = comparator
        return this
    }

    // Uses gv.noise (Libre raw native signal) — same field as "L1=" graph annotation in PrepareBgDataWorker.
    private fun rawDeltaMgdl(): Double? {
        val now = System.currentTimeMillis()
        return when (deltaSource) {
            DeltaSource.RAW_1MIN -> {
                val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 3 * 60 * 1000L, now, ascending = false)
                if (readings.size < 2) return null
                val newest = readings[0].noise ?: return null
                val previous = readings[1].noise ?: return null
                val minutes = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
                if (minutes <= 0) return null
                (newest - previous) / minutes * 5.0
            }
            DeltaSource.RAW_5MIN -> {
                val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 7 * 60 * 1000L, now, ascending = false)
                if (readings.size < 2) return null
                val newest = readings[0].noise ?: return null
                val fiveMinAgo = now - 5 * 60 * 1000L
                val ref = readings.minByOrNull { kotlin.math.abs(it.timestamp - fiveMinAgo) } ?: return null
                if (ref.timestamp == readings[0].timestamp) return null
                newest - (ref.noise ?: return null)
            }
            DeltaSource.AAPS -> null
        }
    }

    override fun shouldRun(): Boolean {
        val calculatedDeltaMgdl: Double? = when (deltaSource) {
            DeltaSource.AAPS     -> {
                val glucoseStatus = glucoseStatusProvider.glucoseStatusData
                    ?: return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
                        aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
                        true
                    } else {
                        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
                        false
                    }
                when (delta.deltaType) {
                    DeltaType.SHORT_AVERAGE -> glucoseStatus.shortAvgDelta
                    DeltaType.LONG_AVERAGE  -> glucoseStatus.longAvgDelta
                    else                    -> glucoseStatus.delta
                }
            }
            DeltaSource.RAW_1MIN,
            DeltaSource.RAW_5MIN -> rawDeltaMgdl()
        }

        if (calculatedDeltaMgdl == null) {
            return if (comparator.value == Comparator.Compare.IS_NOT_AVAILABLE) {
                aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: " + friendlyDescription())
                true
            } else {
                aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
                false
            }
        }

        val thresholdMgdl = profileUtil.convertToMgdl(delta.value, units)
        if (comparator.value.check(calculatedDeltaMgdl, thresholdMgdl, 0.001)) {
            val displayDelta = profileUtil.fromMgdlToUnits(calculatedDeltaMgdl)
            aapsLogger.debug(LTag.AUTOMATION, "Ready for execution: ${deltaSource.label} delta=${String.format("%.2f", displayDelta)}${units.asText} " + friendlyDescription())
            return true
        }
        aapsLogger.debug(LTag.AUTOMATION, "NOT ready for execution: " + friendlyDescription())
        return false
    }

    override fun dataJSON(): JSONObject =
        JSONObject()
            .put("value", delta.value)
            .put("units", units.asText)
            .put("deltaType", delta.deltaType)
            .put("comparator", comparator.value.toString())
            .put("deltaSource", deltaSource.name)

    override fun fromJSON(data: String): Trigger {
        val d = JSONObject(data)
        units = GlucoseUnit.fromText(JsonHelper.safeGetString(d, "units", GlucoseUnit.MGDL.asText))
        val type = DeltaType.valueOf(JsonHelper.safeGetString(d, "deltaType", ""))
        val value = JsonHelper.safeGetDouble(d, "value")
        delta =
            if (units == GlucoseUnit.MMOL) InputDelta(rh, value, (-MMOL_MAX), MMOL_MAX, 0.1, DecimalFormat("0.1"), type)
            else InputDelta(rh, value, (-MGDL_MAX), MGDL_MAX, 1.0, DecimalFormat("1"), type)
        comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")!!))
        deltaSource = DeltaSource.valueOf(JsonHelper.safeGetString(d, "deltaSource", DeltaSource.AAPS.name))
        return this
    }

    override fun friendlyName(): Int = R.string.deltalabel

    override fun friendlyDescription(): String {
        val srcLabel = if (deltaSource != DeltaSource.AAPS) " [${deltaSource.label}]" else ""
        return rh.gs(R.string.deltacompared, comparator.value.shortSymbol, delta.value, rh.gs(delta.deltaType.stringRes)) + srcLabel
    }

    override fun icon(): Optional<Int> = Optional.of(R.drawable.ic_auto_delta)

    override fun duplicate(): Trigger = TriggerDelta(injector, this)

    private fun makeSpinner(context: android.content.Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner =
        Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selected)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
            gravity = Gravity.CENTER_HORIZONTAL
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { onSelect(position) }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(StaticLabel(rh, R.string.deltalabel, this))
            .add(comparator)
            .add(LabelWithElement(rh, rh.gs(R.string.deltalabel_u, units) + ": ", "", delta))
            .build(root)
        // Source + window combined: AAPS (smoothed) / Raw – 1 min ×5 / Raw – 5 min
        // When AAPS selected, DeltaType spinner above is active. When Raw selected, DeltaType is ignored.
        root.addView(makeSpinner(root.context, DeltaSource.labels(), deltaSource.ordinal) {
            deltaSource = DeltaSource.entries[it]
        })
    }
}
