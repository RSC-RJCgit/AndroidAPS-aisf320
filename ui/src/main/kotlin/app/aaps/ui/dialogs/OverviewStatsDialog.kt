package app.aaps.ui.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.R
import app.aaps.ui.activities.StatsActivity
import dagger.android.support.DaggerDialogFragment
import java.util.Locale
import javax.inject.Inject

/**
 * The summary behind the overview's yellow statistics dot (2026-11-01, per explicit request), as in v4: 7 or 30 days,
 * a ring of time below, in and above range, those three percentages, then average, GMI and CV. A tap on the ring opens
 * the full statistics screen. Display only: it reads this phone's glucose readings and stores nothing.
 */
class OverviewStatsDialog : DaggerDialogFragment() {

    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger

    private var days = 7
    private lateinit var content: LinearLayout
    private lateinit var range: TextView
    private val dayButtons = mutableMapOf<Int, Button>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
        days = savedInstanceState?.getInt(KEY_DAYS) ?: 7
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = rh.gs(R.string.statistics)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(7, 30).forEach { period ->
            val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = rh.gs(R.string.overview_stats_days, period)
                setOnClickListener { load(period) }
            }
            dayButtons[period] = button
            header.addView(button)
        }
        header.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = rh.gs(app.aaps.core.ui.R.string.close)
            setOnClickListener { dismiss() }
        })
        root.addView(header)
        range = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        root.addView(range)
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        load(days)
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DAYS, days)
    }

    private fun load(period: Int) {
        days = period
        dayButtons.forEach { (d, button) -> button.isEnabled = d != period }
        val end = dateUtil.now()
        val start = end - T.days(period.toLong()).msecs()
        range.text = "${dateUtil.dateAndTimeString(start)} - ${dateUtil.dateAndTimeString(end)}"
        content.removeAllViews()
        content.addView(ProgressBar(requireContext()))
        // Off the main thread: 30 days of one-minute readings is about 43,000 rows
        Thread {
            val stats = try {
                calculateOverviewStats(persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true).filter { it.isValid }.map { it.value })
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Overview statistics failed", e)
                null
            }
            content.post { if (isAdded && days == period) show(stats) }
        }.start()
    }

    private fun show(stats: OverviewStatsData?) {
        val context = requireContext()
        content.removeAllViews()
        if (stats == null) {
            content.addView(TextView(context).apply { text = rh.gs(R.string.overview_stats_no_data) })
            return
        }
        val lowColor = rh.gac(context, app.aaps.core.ui.R.attr.lowColor)
        val inRangeColor = rh.gac(context, app.aaps.core.ui.R.attr.bgInRange)
        val highColor = rh.gac(context, app.aaps.core.ui.R.attr.highColor)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        row.addView(RingView(context, listOf(stats.lowPercent to lowColor, stats.inRangePercent to inRangeColor, stats.highPercent to highColor)).apply {
            contentDescription = rh.gs(R.string.statistics)
            setOnClickListener {
                dismiss()
                startActivity(Intent(context, StatsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) })
        val values = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        values.addView(valueRow(
            listOf(
                Triple(rh.gs(R.string.overview_stats_low), percent(stats.lowPercent), lowColor),
                Triple(rh.gs(R.string.overview_stats_in_range), percent(stats.inRangePercent), inRangeColor),
                Triple(rh.gs(R.string.overview_stats_high), percent(stats.highPercent), highColor)
            )
        ))
        values.addView(valueRow(
            listOf(
                Triple(rh.gs(app.aaps.core.ui.R.string.average), profileUtil.fromMgdlToStringInUnits(stats.averageMgdl), null),
                Triple(rh.gs(R.string.overview_stats_gmi), percent(stats.gmiPercent), null),
                Triple(rh.gs(R.string.overview_stats_cv), percent(stats.cvPercent), null)
            )
        ))
        row.addView(values, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
        content.addView(TextView(context).apply {
            text = "${rh.gs(R.string.stats_ea1c)}: ${percent(stats.eA1cPercent)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        content.addView(TextView(context).apply {
            text = rh.gs(
                R.string.overview_stats_range,
                profileUtil.fromMgdlToStringInUnits(OVERVIEW_STATS_LOW_MGDL),
                profileUtil.fromMgdlToStringInUnits(OVERVIEW_STATS_HIGH_MGDL),
                profileUtil.units.asText
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    /** Three labelled values side by side, as v4's StatsValue */
    private fun valueRow(items: List<Triple<String, String, Int?>>): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            items.forEach { (label, value, color) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(TextView(context).apply {
                        text = label
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    })
                    addView(TextView(context).apply {
                        text = value
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        color?.let { setTextColor(it) }
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun percent(value: Double) = String.format(Locale.getDefault(), "%.1f%%", value)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** A filled pie of the three shares, starting at the top, as v4's ring */
    private class RingView(context: Context, private val shares: List<Pair<Double, Int>>) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            var start = -90f
            shares.forEach { (percentage, color) ->
                val sweep = (percentage.coerceIn(0.0, 100.0) * 3.6).toFloat()
                paint.color = color
                canvas.drawArc(bounds, start, sweep, true, paint)
                start += sweep
            }
        }
    }

    private companion object {

        const val KEY_DAYS = "days"
    }
}
