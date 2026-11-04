package app.aaps.ui.stats

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.DexcomTirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.R
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.SerialDisposable
import java.util.Locale

/**
 * The Statistics tabs ported from v4 (2026-11-01, per explicit request): Glucose (the ambulatory glucose profile), Cycle
 * (28-day insulin cycles), Sensor (glucose over each sensor) and Pods (insulin per pod). Each fills [container] when its
 * tab is chosen, reading on the io thread. Therapy settings and treatment records are not changed.
 */
class StatsTabs(
    private val context: Context,
    private val container: LinearLayout,
    private val persistenceLayer: PersistenceLayer,
    private val tddCalculator: TddCalculator,
    private val dexcomTirCalculator: DexcomTirCalculator,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val aapsSchedulers: AapsSchedulers,
    private val aapsLogger: AAPSLogger,
    private val podInsulinCache: PodInsulinCache
) {

    enum class Tab { GLUCOSE, CYCLE, SENSOR, PODS }

    private var shown: Tab? = null
    private var agpRange = AgpStatsRange.DAYS_7
    private var cycleOffset = 0
    private var cycleBands = true
    private var cycleWeeks = true
    private var cycleTdds: List<TDD>? = null
    private var sensorView = SensorCycleView.AMBULATORY
    private var sensorOffset = 0
    private var sensorQuarters = true
    // Raw (the default) or BG, remembered across visits (2026-11-04, per explicit request)
    private var sensorRaw = preferences.get(BooleanNonKey.StatsSensorRaw)
    private val request = SerialDisposable()
    private var requestVersion = 0L

    private val lowColor get() = rh.gac(context, app.aaps.core.ui.R.attr.lowColor)
    private val highColor get() = rh.gac(context, app.aaps.core.ui.R.attr.highColor)
    private val inRangeColor get() = rh.gac(context, app.aaps.core.ui.R.attr.bgInRange)
    private val lineColor = Color.parseColor("#4A9EFF")
    private val averageColor = Color.parseColor("#FFB74D")
    private val quarterColor = Color.parseColor("#66BB6A")
    private val wideBand = Color.argb(56, 0x4A, 0x9E, 0xFF)
    private val narrowBand = Color.argb(107, 0x4A, 0x9E, 0xFF)
    private val lowMgdl get() = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewLowMark))
    private val highMgdl get() = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewHighMark))

    fun show(tab: Tab) {
        hide()
        shown = tab
        when (tab) {
            Tab.GLUCOSE -> loadGlucose()
            Tab.CYCLE   -> loadCycle()
            Tab.SENSOR  -> loadSensor()
            Tab.PODS    -> loadPods()
        }
    }

    fun hide() {
        shown = null
        requestVersion++
        request.set(null)
    }

    fun dispose() = request.dispose()

    fun saveState(state: Bundle) {
        state.putString("stats.agpRange", agpRange.name)
        state.putInt("stats.cycleOffset", cycleOffset)
        state.putBoolean("stats.cycleBands", cycleBands)
        state.putBoolean("stats.cycleWeeks", cycleWeeks)
        state.putString("stats.sensorView", sensorView.name)
        state.putInt("stats.sensorOffset", sensorOffset)
        state.putBoolean("stats.sensorQuarters", sensorQuarters)
        state.putBoolean("stats.sensorRaw", sensorRaw)
    }

    fun restoreState(state: Bundle?) {
        if (state == null) return
        agpRange = AgpStatsRange.entries.firstOrNull { it.name == state.getString("stats.agpRange") } ?: AgpStatsRange.DAYS_7
        cycleOffset = state.getInt("stats.cycleOffset").coerceIn(0, CYCLE_LENGTH - 1)
        cycleBands = state.getBoolean("stats.cycleBands", true)
        cycleWeeks = state.getBoolean("stats.cycleWeeks", true)
        sensorView = SensorCycleView.entries.firstOrNull { it.name == state.getString("stats.sensorView") } ?: SensorCycleView.AMBULATORY
        sensorOffset = state.getInt("stats.sensorOffset").coerceAtLeast(0)
        sensorQuarters = state.getBoolean("stats.sensorQuarters", true)
        sensorRaw = state.getBoolean("stats.sensorRaw", sensorRaw)
    }

    // ---------- Glucose ----------

    private fun loadGlucose() {
        loading(Tab.GLUCOSE)
        val range = agpRange
        val low = lowMgdl
        val high = highMgdl
        load(Tab.GLUCOSE, {
            val now = dateUtil.now()
            val agp = calculateAgpStatsData(persistenceLayer.getBgReadingsDataFromTimeToTime(range.startTime(now), now, true), low, high)
            val tir = dexcomTirCalculator.calculate()
            GlucoseResult(agp, if (tir.count() > 0) computeCgpData(tir) else null)
        }) { result -> showGlucose(result) }
    }

    private class GlucoseResult(val agp: AgpStatsData, val pentagon: CgpData?)

    private fun showGlucose(result: GlucoseResult) {
        val data = result.agp
        container.removeAllViews()
        container.addView(choices(
            listOf(
                AgpStatsRange.TODAY to rh.gs(R.string.stats_range_today),
                AgpStatsRange.HOURS_24 to rh.gs(R.string.stats_range_24h),
                AgpStatsRange.DAYS_7 to rh.gs(R.string.stats_range_days, 7),
                AgpStatsRange.DAYS_30 to rh.gs(R.string.stats_range_days, 30),
                AgpStatsRange.DAYS_90 to rh.gs(R.string.stats_range_days, 90)
            ), agpRange
        ) { agpRange = it; loadGlucose() })
        if (data.readingCount == 0) {
            container.addView(note(rh.gs(R.string.stats_no_glucose)))
            showPentagon(result.pentagon)
            return
        }
        container.addView(title(rh.gs(R.string.stats_agp_title)))
        container.addView(note(rh.gs(R.string.stats_reading_count, data.readingCount)))
        val axis = calculateAgpYAxis(maxOf(data.highMgdl, data.hourlyPercentiles.maxOfOrNull { it.p90Mgdl } ?: data.highMgdl), profileUtil.units)
        container.addView(chart(axis).apply {
            xMax = 24f
            verticalLines = listOf(6f, 12f, 18f)
            xLabels = listOf(0, 6, 12, 18, 24).map { it.toFloat() to String.format(Locale.getDefault(), "%02d", it % 24) }
            thresholds = listOf(data.lowMgdl to lowColor, 140.0 to inRangeColor, data.highMgdl to highColor)
            val points = data.hourlyPercentiles
            bands = listOf(
                StatsChartView.Band(points.map { Triple(it.hour + 0.5f, it.p10Mgdl, it.p90Mgdl) }, wideBand),
                StatsChartView.Band(points.map { Triple(it.hour + 0.5f, it.p25Mgdl, it.p75Mgdl) }, narrowBand)
            )
            val byHour = points.associate { it.hour to it.medianMgdl }
            lines = listOf(StatsChartView.Line((0 until 24).map { it + 0.5f to byHour[it] }, lineColor, 2f))
            contentDescription = rh.gs(R.string.stats_agp_title)
        })
        container.addView(legend(listOf(rh.gs(R.string.stats_p10_90) to wideBand, rh.gs(R.string.stats_p25_75) to narrowBand, rh.gs(R.string.stats_median) to lineColor)))
        val tir = data.tir
        container.addView(figures(
            listOf(
                rh.gs(R.string.stats_very_low) to percent(tir.veryLow) to lowColor,
                rh.gs(R.string.overview_stats_low) to percent(tir.low) to lowColor,
                rh.gs(R.string.overview_stats_in_range) to percent(tir.inRange) to inRangeColor,
                rh.gs(R.string.overview_stats_high) to percent(tir.high) to highColor,
                rh.gs(R.string.stats_very_high) to percent(tir.veryHigh) to highColor
            )
        ))
        container.addView(figures(
            listOf(
                rh.gs(app.aaps.core.ui.R.string.average) to profileUtil.fromMgdlToStringInUnits(data.averageMgdl) to null,
                rh.gs(R.string.stats_median) to profileUtil.fromMgdlToStringInUnits(data.medianMgdl) to null,
                rh.gs(R.string.stats_sd) to profileUtil.fromMgdlToStringInUnits(data.standardDeviationMgdl) to null,
                rh.gs(R.string.overview_stats_cv) to percent(data.coefficientOfVariation) to null
            )
        ))
        container.addView(figures(
            listOf(
                rh.gs(R.string.overview_stats_gmi) to percent(data.gmiPercent) to null,
                rh.gs(R.string.stats_ea1c) to percent(data.eA1cPercent) to null,
                rh.gs(R.string.stats_tight_range) to percent(data.tightRangePercent) to null,
                rh.gs(R.string.stats_days_of_data) to String.format(Locale.getDefault(), "%.1f", data.availableDays) to null
            )
        ))
        container.addView(note(rh.gs(R.string.overview_stats_range, profileUtil.fromMgdlToStringInUnits(data.lowMgdl), profileUtil.fromMgdlToStringInUnits(data.highMgdl), profileUtil.units.asText)))
        showPentagon(result.pentagon)
    }

    private fun showPentagon(data: CgpData?) {
        container.addView(title(rh.gs(R.string.stats_pentagon_title)))
        container.addView(note(rh.gs(R.string.stats_pentagon_period)))
        if (data == null) {
            container.addView(note(rh.gs(R.string.stats_no_glucose)))
            return
        }
        val labels = listOf(rh.gs(R.string.stats_time_out_of_range), rh.gs(R.string.overview_stats_cv), rh.gs(R.string.overview_stats_low), rh.gs(R.string.overview_stats_high), rh.gs(app.aaps.core.ui.R.string.average))
        val values = listOf(percent(data.torPct), percent(data.cvPct), percent(data.hypoPct), percent(data.hyperPct), profileUtil.fromMgdlToStringInUnits(data.meanGlucose) + " " + profileUtil.units.asText)
        container.addView(GlucosePentagonView(context, data, labels, values, lineColor, inRangeColor).apply {
            contentDescription = rh.gs(R.string.stats_pentagon_title) + ": " + labels.zip(values).joinToString(", ") { (label, value) -> "$label $value" }
        })
        container.addView(legend(listOf(rh.gs(R.string.stats_pentagon_reference) to inRangeColor, rh.gs(R.string.stats_pentagon_readings) to lineColor)))
        container.addView(note(rh.gs(R.string.stats_pentagon_pgr, String.format(Locale.getDefault(), "%.1f", data.pgr))))
    }

    // ---------- Cycle ----------

    private fun loadCycle() {
        cycleTdds?.let { showCycle(it); return }
        loading(Tab.CYCLE)
        load(Tab.CYCLE, {
            // Read each local day independently: a missing old day must not hide later cached TDDs.
            // Using local midnights also keeps 23/25-hour DST days separate.
            val list = mutableListOf<TDD>()
            val now = dateUtil.now()
            for (day in 1L..168L) {
                val from = MidnightTime.calcDaysBack(now, day)
                val to = MidnightTime.calc(from + T.hours(27).msecs())
                val cached = if (to - from == T.hours(24).msecs()) persistenceLayer.getCalculatedTotalDailyDose(from)?.takeIf { it.isValid } else null
                val tdd = cached ?: tddCalculator.calculateInterval(from, to, allowMissingData = false)
                tdd?.takeIf { it.isValid }?.let { list.add(it) }
            }
            list.sortedByDescending { it.timestamp }
        }) { tdds -> cycleTdds = tdds; showCycle(tdds) }
    }

    private fun showCycle(tdds: List<TDD>) {
        container.removeAllViews()
        container.addView(choices(listOf(false to rh.gs(R.string.stats_view_lines), true to rh.gs(R.string.stats_view_ambulatory)), cycleBands) { cycleBands = it; showCycle(tdds) })
        container.addView(toggle(rh.gs(R.string.stats_quarter_averages), cycleWeeks) { cycleWeeks = it; showCycle(tdds) })
        val data = computeCycleData(tdds, cycleOffset) { dateUtil.dateString(it) }
        if (data == null) {
            container.addView(note(rh.gs(R.string.stats_cycle_not_enough, tdds.size)))
            return
        }
        container.addView(note(rh.gs(R.string.stats_cycle_found, data.cycleCount, data.totalDaysAvailable)))
        container.addView(note(rh.gs(R.string.stats_cycle_local_totals)))
        val highest = data.cycles.flatMap { it.dayValues }.maxOrNull() ?: 1.0
        val step = niceStep(highest)
        container.addView(StatsChartView(context).apply {
            xMax = CYCLE_LENGTH.toFloat()
            yMin = 0.0
            yMax = step * 4
            yTicks = (0..4).map { step * it to String.format(Locale.getDefault(), "%.0f", step * it) }
            verticalLines = listOf(7f, 14f, 21f)
            xLabels = listOf(1, 7, 14, 21, 28).map { it - 0.5f to it.toString() }
            val weeks = cycleWeekAverages(data.cycles)
            val weekLines = if (cycleWeeks) weeks.mapIndexedNotNull { week, average ->
                average?.let { StatsChartView.Line(listOf(week * 7f to it, (week + 1) * 7f to it), averageColor, 1f, dashed = true) }
            } else emptyList()
            if (cycleBands) {
                val dayBands = cycleDayBands(data.cycles)
                bands = listOf(
                    StatsChartView.Band(dayBands.map { Triple(it.day + 0.5f, it.p10, it.p90) }, wideBand),
                    StatsChartView.Band(dayBands.map { Triple(it.day + 0.5f, it.p25, it.p75) }, narrowBand)
                )
                lines = weekLines + StatsChartView.Line(dayBands.map { it.day + 0.5f to it.median }, lineColor, 2f)
            } else {
                // Newest most visible, oldest faintest
                lines = data.cycles.mapIndexed { index, cycle ->
                    val alpha = (150 - index * 100 / data.cycles.size).coerceAtLeast(40)
                    StatsChartView.Line(cycle.dayValues.mapIndexed { day, value -> day + 0.5f to value }, Color.argb(alpha, 0x4A, 0x9E, 0xFF), 1.5f)
                } + weekLines + StatsChartView.Line(data.average.mapIndexed { day, value -> day + 0.5f to value }, averageColor, 2.5f, dashed = true)
            }
        })
        container.addView(legend(if (cycleBands) listOf(rh.gs(R.string.stats_p10_90) to wideBand, rh.gs(R.string.stats_p25_75) to narrowBand, rh.gs(R.string.stats_median) to lineColor)
                                 else listOf(rh.gs(R.string.stats_cycles) to lineColor, rh.gs(app.aaps.core.ui.R.string.average) to averageColor)))
        container.addView(note(rh.gs(R.string.stats_insulin_axis)))
        container.addView(note(data.cycles.joinToString(" · ") { rh.gs(R.string.stats_cycle_start, it.cycleIndex + 1, it.startDate) }))
        container.addView(stepper(rh.gs(R.string.stats_days_skipped), cycleOffset, CYCLE_LENGTH - 1) { cycleOffset = it; showCycle(tdds) })
    }

    // ---------- Sensor ----------

    private fun loadSensor() {
        loading(Tab.SENSOR)
        val offset = sensorOffset
        val low = lowMgdl
        val high = highMgdl
        load(Tab.SENSOR, {
            val now = dateUtil.now()
            val starts = persistenceLayer.getTherapyEventDataFromTime(now - T.days(SENSOR_HISTORY_DAYS).msecs(), TE.Type.SENSOR_CHANGE, true)
                .filter { it.isValid && it.timestamp <= now }.map { it.timestamp }
            val available = distinctSensorStarts(starts).size
            val window = sensorWindowHours(persistenceLayer.getLastGlucoseValue()?.sourceSensor)
            val span = sensorCycleReadingSpan(starts, now, window, offset, sensorCount = SENSOR_TAB_COUNT)
            val readings = span?.let { persistenceLayer.getBgReadingsDataFromTimeToTime(it.first, it.last, true) }.orEmpty()
            SensorResult(
                calculateSensorCycleData(readings, starts, now, window, offset, low, high, sensorCount = SENSOR_TAB_COUNT), available,
                // The same views from the raw signal; null when the shown sensors have none
                calculateSensorCycleData(sensorRawReadings(readings), starts, now, window, offset, low, high, sensorCount = SENSOR_TAB_COUNT)
                    ?.takeIf { data -> data.sensors.any { sensor -> sensor.binMeansMgdl.any { it != null } } }
            )
        }) { result -> showSensor(result) }
    }

    private class SensorResult(val data: SensorCycleData?, val available: Int, val raw: SensorCycleData?)

    private fun showSensor(result: SensorResult) {
        container.removeAllViews()
        container.addView(choices(listOf(true to rh.gs(R.string.sensor_signal_raw), false to rh.gs(R.string.sensor_signal_bg)), sensorRaw) {
            sensorRaw = it
            preferences.put(BooleanNonKey.StatsSensorRaw, it)
            showSensor(result)
        })
        container.addView(choices(
            listOf(
                SensorCycleView.WHOLE_SENSOR to rh.gs(R.string.stats_view_lines),
                SensorCycleView.AMBULATORY to rh.gs(R.string.stats_view_ambulatory),
                SensorCycleView.CHANGEOVER to rh.gs(R.string.stats_view_changeover)
            ), sensorView
        ) { sensorView = it; showSensor(result) })
        container.addView(toggle(rh.gs(R.string.stats_quarter_averages), sensorQuarters) { sensorQuarters = it; showSensor(result) })
        // Raw: the same views from the sensor's raw signal; BG: from the glucose values, as before
        val data = if (sensorRaw) result.raw else result.data
        when {
            sensorRaw && data == null && result.data != null -> container.addView(note(rh.gs(R.string.sensor_raw_none)))
            data == null                                  -> container.addView(note(rh.gs(R.string.stats_sensor_no_data)))
            sensorView == SensorCycleView.CHANGEOVER      -> data.changeover?.let { showChangeover(data, it) } ?: container.addView(note(rh.gs(R.string.stats_changeover_no_data)))
            else                                          -> showWholeSensor(data, ambulatory = sensorView == SensorCycleView.AMBULATORY)
        }
        container.addView(stepper(rh.gs(R.string.stats_sensors_skipped), sensorOffset, (result.available - 1).coerceAtLeast(0)) { sensorOffset = it; loadSensor() })
    }

    private fun showWholeSensor(data: SensorCycleData, ambulatory: Boolean) {
        val days = (data.windowHours + 23) / 24
        val binCount = data.averageMgdl.size
        container.addView(note(rh.gs(R.string.stats_sensor_found, data.sensors.size, data.sensorsAvailable, String.format(Locale.getDefault(), "%.1f", data.windowHours / 24.0))))
        container.addView(note(rh.gs(R.string.stats_sensor_newest, dateUtil.dateAndTimeString(data.sensors.first().startTimestamp))))
        if (data.averageMgdl.all { it == null }) {
            container.addView(note(rh.gs(R.string.stats_sensor_no_glucose)))
            return
        }
        val highest = if (ambulatory) data.bands.maxOfOrNull { it.p90Mgdl } else data.sensors.flatMap { it.binMeansMgdl }.filterNotNull().maxOrNull()
        val quarterHighest = if (sensorQuarters) data.quarterAveragesMgdl.filterNotNull().maxOrNull() ?: 0.0 else 0.0
        val axis = calculateAgpYAxis(maxOf(data.highMgdl, highest ?: data.highMgdl, quarterHighest), profileUtil.units)
        val binsPerDay = 24f / data.binHours
        container.addView(chart(axis).apply {
            xMax = binCount.toFloat()
            verticalLines = (1 until days).map { it * binsPerDay }
            val endDay = data.windowHours / 24f
            val labelledDays = (0..days step 2).map { it.toFloat() }.filter { it < endDay - 1.5f } + endDay
            xLabels = labelledDays.map { day -> day * binsPerDay to if (day % 1f == 0f) day.toInt().toString() else String.format(Locale.getDefault(), "%.1f", day) }
            thresholds = listOf(data.lowMgdl to lowColor, data.highMgdl to highColor)
            if (ambulatory) {
                bands = listOf(
                    StatsChartView.Band(data.bands.map { Triple(it.bin + 0.5f, it.p10Mgdl, it.p90Mgdl) }, wideBand),
                    StatsChartView.Band(data.bands.map { Triple(it.bin + 0.5f, it.p25Mgdl, it.p75Mgdl) }, narrowBand)
                )
                val byBin = data.bands.associate { it.bin to it.medianMgdl }
                lines = listOf(StatsChartView.Line((0 until binCount).map { it + 0.5f to byBin[it] }, lineColor, 2f))
            } else {
                lines = data.sensors.mapIndexed { index, sensor ->
                    val alpha = (150 - index * 100 / data.sensors.size).coerceAtLeast(40)
                    StatsChartView.Line(sensor.binMeansMgdl.mapIndexed { bin, value -> bin + 0.5f to value }, Color.argb(alpha, 0x4A, 0x9E, 0xFF), 1.5f)
                } + StatsChartView.Line(data.averageMgdl.mapIndexed { bin, value -> bin + 0.5f to value }, averageColor, 2.5f, dashed = true)
            }
            if (sensorQuarters) lines = lines + quarterLines(data.quarterAveragesMgdl, data.windowHours.toFloat() / data.binHours)
        })
        if (sensorQuarters) showQuarterValues(data.quarterAveragesMgdl)
        container.addView(legend(
            if (ambulatory) listOf(rh.gs(R.string.stats_p10_90) to wideBand, rh.gs(R.string.stats_p25_75) to narrowBand, rh.gs(R.string.stats_median) to lineColor)
            else listOf(rh.gs(R.string.stats_sensors) to lineColor, rh.gs(app.aaps.core.ui.R.string.average) to averageColor)
        ))
        container.addView(note(rh.gs(R.string.stats_glucose_axis, profileUtil.units.asText)))
    }

    private fun showChangeover(data: SensorCycleData, changeover: SensorChangeoverData) {
        val hours = changeover.hoursEachSide
        val daysEachSide = hours / 24
        container.addView(note(rh.gs(R.string.stats_changeover_explanation, daysEachSide, changeover.changeTimestamps.size)))
        container.addView(note(rh.gs(R.string.stats_changeover_dates, changeover.changeTimestamps.joinToString(", ") { dateUtil.dateAndTimeString(it) })))
        val quarterHighest = if (sensorQuarters) changeover.quarterAveragesMgdl.filterNotNull().maxOrNull() ?: 0.0 else 0.0
        val axis = calculateAgpYAxis(maxOf(data.highMgdl, changeover.percentiles.maxOfOrNull { it.p90Mgdl } ?: data.highMgdl, quarterHighest), profileUtil.units)
        val points = changeover.percentiles
        container.addView(chart(axis).apply {
            xMax = 2f * hours
            verticalLines = (1 until 2 * daysEachSide).map { it * 24f }
            xLabels = (-daysEachSide..daysEachSide).map { (it + daysEachSide) * 24f to if (it > 0) "+$it" else it.toString() }
            thresholds = listOf(data.lowMgdl to lowColor, data.highMgdl to highColor)
            bands = listOf(
                StatsChartView.Band(points.map { Triple(it.hourFromChange + hours + 0.5f, it.p10Mgdl, it.p90Mgdl) }, wideBand),
                StatsChartView.Band(points.map { Triple(it.hourFromChange + hours + 0.5f, it.p25Mgdl, it.p75Mgdl) }, narrowBand)
            )
            val byHour = points.associate { it.hourFromChange to it.medianMgdl }
            lines = listOf(StatsChartView.Line((-hours until hours).map { it + hours + 0.5f to byHour[it] }, lineColor, 2f))
            markers = listOf(hours.toFloat())
            if (sensorQuarters) lines = lines + quarterLines(changeover.quarterAveragesMgdl, 2f * hours)
        })
        if (sensorQuarters) showQuarterValues(changeover.quarterAveragesMgdl)
        container.addView(legend(listOf(rh.gs(R.string.stats_p10_90) to wideBand, rh.gs(R.string.stats_p25_75) to narrowBand, rh.gs(R.string.stats_median) to lineColor)))
        container.addView(note(rh.gs(R.string.stats_glucose_axis, profileUtil.units.asText)))
        container.addView(figures(
            listOf(
                rh.gs(R.string.stats_avg_before) to (changeover.averageBeforeMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "-") to null,
                rh.gs(R.string.stats_avg_after) to (changeover.averageAfterMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "-") to null,
                rh.gs(R.string.stats_tir_before) to (changeover.tirBeforePercent?.let { percent(it) } ?: "-") to null,
                rh.gs(R.string.stats_tir_after) to (changeover.tirAfterPercent?.let { percent(it) } ?: "-") to null
            )
        ))
    }

    // ---------- Pods ----------

    private fun loadPods() {
        loading(Tab.PODS)
        load(Tab.PODS, {
            val now = dateUtil.now()
            val starts = persistenceLayer.getTherapyEventDataFromTime(now - T.days(POD_HISTORY_DAYS).msecs(), TE.Type.CANNULA_CHANGE, true)
                .filter { it.isValid && it.timestamp <= now }.map { it.timestamp }
            val available = distinctSensorStarts(starts).size
            val all = calculatePodInsulinData(
                starts = starts,
                now = now,
                offset = 0,
                podCount = available,
                dayStartOf = { MidnightTime.calc(it) },
                // Found as Android finds them, so a DST day is one day
                nextDayStart = { MidnightTime.calc(MidnightTime.calc(it) + T.hours(27).msecs()) },
                sumUnits = { from, to ->
                    podInsulinCache.getOrCalculate(from, to, to <= MidnightTime.calc(now)) {
                    val midnight = MidnightTime.calc(from)
                    val next = MidnightTime.calc(midnight + T.hours(27).msecs())
                    val wholeDay = from == midnight && to == next && next - midnight == T.hours(24).msecs()
                    val cached = if (wholeDay) persistenceLayer.getCalculatedTotalDailyDose(midnight)?.takeIf { it.isValid } else null
                    cached?.totalAmount ?: tddCalculator.calculateInterval(from, to, allowMissingData = true)?.totalAmount
                    }
                }
            )
            val highest = all?.pods.orEmpty()
                .filter { pod -> pod.days.any { it.units != null } }
                .sortedWith(compareByDescending<PodInsulin> { it.totalUnits }.thenByDescending { it.startTimestamp })
                .take(POD_COUNT)
            val data = all?.copy(pods = highest)?.takeIf { it.pods.isNotEmpty() }
            PodResult(data, all?.pods?.takeLast(33).orEmpty())
        }) { result -> showPods(result) }
    }

    private class PodResult(val data: PodInsulinData?, val summary: List<PodInsulin>)

    private fun showPods(result: PodResult) {
        container.removeAllViews()
        container.addView(title(rh.gs(R.string.stats_pods_title)))
        // The previous pod (the last finished one) and the one before it first (2026-11-04, per explicit request)
        val finished = result.summary.filter { !it.inUse }
        fun line(pod: PodInsulin): String {
            val units = if (pod.days.any { it.units != null }) String.format(Locale.getDefault(), "%.1f U", pod.totalUnits) else "—"
            return "${dateUtil.dateStringShort(pod.startTimestamp)}  $units  ·  ${String.format(Locale.getDefault(), "%.1f d", pod.hours / 24)}"
        }
        finished.lastOrNull()?.let { container.addView(note(rh.gs(R.string.stats_pods_previous, line(it)))) }
        finished.getOrNull(finished.size - 2)?.let { container.addView(note(rh.gs(R.string.stats_pods_second_last, line(it)))) }
        val data = result.data
        if (data == null) {
            container.addView(note(rh.gs(R.string.stats_pods_no_data)))
        } else {
            container.addView(note(rh.gs(R.string.stats_pods_found, data.pods.size, data.podsAvailable)))
            container.addView(note(data.pods.joinToString("  ·  ") { pod ->
                "${dateUtil.dateStringShort(pod.startTimestamp)} ${String.format(Locale.getDefault(), "%.1f U", pod.totalUnits)}"
            }).apply { setSingleLine(false) })
        }
        result.summary.asReversed().forEach { pod ->
            val units = if (pod.days.any { it.units != null }) String.format(Locale.getDefault(), "%.1f U", pod.totalUnits) else "—"
            container.addView(note("${dateUtil.dateStringShort(pod.startTimestamp)}   $units"))
        }
    }

    // ---------- Shared parts ----------

    private fun <T : Any> load(tab: Tab, work: () -> T, done: (T) -> Unit) {
        val version = ++requestVersion
        request.set(Single.fromCallable(work)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ if (shown == tab && version == requestVersion) done(it) }, { error ->
                aapsLogger.error(LTag.UI, "Statistics tab $tab failed", error)
                if (shown == tab && version == requestVersion) {
                    container.removeAllViews()
                    container.addView(note(rh.gs(R.string.stats_failed)))
                }
            }))
    }

    private fun loading(tab: Tab) {
        if (shown != tab) return
        container.removeAllViews()
        container.addView(note(rh.gs(R.string.calculation_in_progress)))
    }

    private fun chart(axis: AgpYAxis) = StatsChartView(context).apply {
        yMin = axis.minimumMgdl
        yMax = axis.maximumMgdl
        yTicks = axis.ticksMgdl.map { it to profileUtil.fromMgdlToStringInUnits(it) }
    }

    private fun quarterLines(averages: List<Double?>, span: Float): List<StatsChartView.Line> =
        averages.mapIndexedNotNull { quarter, average ->
            average?.let { StatsChartView.Line(listOf(quarter * span / 4 to it, (quarter + 1) * span / 4 to it), quarterColor, 2f) }
        }

    private fun showQuarterValues(averages: List<Double?>) {
        container.addView(figures(averages.mapIndexed { quarter, value ->
            (rh.gs(R.string.stats_quarter_label, quarter + 1) to (value?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "-")) to quarterColor
        }))
    }

    private fun toggle(label: String, selected: Boolean, onChange: (Boolean) -> Unit) = CheckBox(context).apply {
        text = label
        isChecked = selected
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun niceStep(highest: Double): Double {
        val raw = highest / 4
        return listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 25.0, 50.0, 100.0).firstOrNull { it >= raw } ?: (raw + 1)
    }

    private fun <T> choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            options.forEach { (value, label) ->
                addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = label
                    isSelected = value == selected
                    if (value == selected) setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { if (value != selected) onSelect(value) }
                })
            }
        })
    }

    /** − value + with whole-finger buttons and no keyboard, as v4's Pods and Sensor tabs */
    private fun stepper(label: String, value: Int, max: Int, onChange: (Int) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(context).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(context).apply {
            text = "−"
            isEnabled = value > 0
            setOnClickListener { onChange((value - 1).coerceAtLeast(0)) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        addView(TextView(context).apply {
            text = value.toString()
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = "+"
            isEnabled = value < max
            setOnClickListener { onChange((value + 1).coerceAtMost(max)) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
    }

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun legend(entries: List<Pair<String, Int>>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        entries.forEach { (label, color) ->
            addView(TextView(context).apply {
                text = "■ $label"
                setTextColor(color or 0xFF000000.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, dp(12), 0)
            })
        }
    }

    /** Labelled values side by side */
    private fun figures(items: List<Pair<Pair<String, String>, Int?>>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(2))
        items.forEach { (pair, color) ->
            val (label, value) = pair
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) })
                addView(TextView(context).apply {
                    text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    color?.let { setTextColor(it) }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun percent(value: Double) = String.format(Locale.getDefault(), "%.1f%%", value)

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {

        const val SENSOR_HISTORY_DAYS = 180L
        const val POD_HISTORY_DAYS = 180L
    }
}
