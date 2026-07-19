package app.aaps.ui.dialogs

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TableRow
import android.widget.TextView
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.databinding.DialogAutoisfHistoryBinding
import dagger.android.support.DaggerDialogFragment
import java.text.DecimalFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AutoISFHistoryDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var autoIsfHistoryExporter: AutoIsfHistoryExporter

    private var _binding: DialogAutoisfHistoryBinding? = null
    private val binding get() = _binding!!

    private val df1 = DecimalFormat("0.0")
    private val df2 = DecimalFormat("0.00")

    companion object {
        private const val MGDL_TO_MMOL = 18.0182
    }

    // Colors matching Trio TAI history screen
    private val colorFinalRatio = Color.parseColor("#FF6060")  // red
    private val colorGlucose    = Color.parseColor("#60C060")  // green
    private val colorInsulin    = Color.parseColor("#4A9EFF")  // blue
    private val colorTime       = Color.WHITE
    private val colorHeader     = Color.LTGRAY

    // ISF type colors from theme (resolved lazily after view is attached)
    private val colorAcceIsf  get() = rh.gac(context, app.aaps.core.ui.R.attr.acceIsfColor)
    private val colorBgIsf    get() = rh.gac(context, app.aaps.core.ui.R.attr.bgIsfColor)
    private val colorPpIsf    get() = rh.gac(context, app.aaps.core.ui.R.attr.ppIsfColor)
    private val colorDuraIsf  get() = rh.gac(context, app.aaps.core.ui.R.attr.duraIsfColor)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        _binding = DialogAutoisfHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Full data (descending) plus the current SMB-only filter state, so the table can be rebuilt.
    private var allRecords: List<AIV> = emptyList()
    private var allApsResults: List<APSResult> = emptyList()
    private var allStepsCounts: List<SC> = emptyList()
    private var smbOnly = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setOnClickListener { dismiss() }
        binding.smbOnlyButton.setOnClickListener {
            smbOnly = !smbOnly
            binding.smbOnlyButton.text = if (smbOnly) "All" else "SMB only"
            rebuildTable()
        }

        val now = System.currentTimeMillis()
        val sixHoursAgo = now - TimeUnit.HOURS.toMillis(6)
        allRecords = persistenceLayer.getAutoIsfValuesFromTimeToTime(sixHoursAgo, now)
            .sortedByDescending { it.timestamp }
        allApsResults = persistenceLayer.getApsResults(sixHoursAgo, now)
        allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(sixHoursAgo, now)

        rebuildTable()
        // Opening the dialog also writes the export files (CSV / text / settings), off the UI thread.
        // The automatic 6-hourly export runs from KeepAliveWorker via the same AutoIsfHistoryExporter.
        Executors.newSingleThreadExecutor().execute {
            autoIsfHistoryExporter.writeExport(allRecords, allApsResults, allStepsCounts, now)
        }
    }

    // Clears and repopulates the table honouring the SMB-only filter. The IOB-5-min-change column
    // always looks back in the full record set, so filtering rows never distorts that value.
    private fun rebuildTable() {
        binding.historyHeader.removeAllViews()
        binding.historyTable.removeAllViews()
        val shown = if (smbOnly) allRecords.filter { it.smbDelivered > 0.0 } else allRecords
        populateTable(shown, allApsResults, allStepsCounts)
    }

    private fun populateTable(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>) {
        val table = binding.historyTable
        val header = binding.historyHeader

        addHeaderRows(header)

        if (records.isEmpty()) {
            addRow(table, cells = listOf(Cell("No AutoISF data in last 6 hours", colorTime)))
            return
        }

        for (r in records) {
            val sc = autoIsfHistoryExporter.stepsAt(r.timestamp, stepsCountList)
            addRow(
                table, cells = listOf(
                    Cell(dateUtil.timeString(r.timestamp),          colorTime),
                    Cell(df1.format(r.glucose / MGDL_TO_MMOL),      colorGlucose),
                    Cell(df2.format(r.finalIsf),                    dominantIsfColor(r, colorFinalRatio)),
                    Cell(adjStr(r.acceIsf),                         colorAcceIsf),
                    Cell(adjStr(r.bgIsf),                           colorBgIsf),
                    Cell(adjStr(r.ppIsf),                           colorPpIsf),
                    Cell(adjStr(r.duraIsf),                         colorDuraIsf),
                    Cell(insulinStr(r.smbDelivered),                smbIsfColor(r)),
                    Cell(autoIsfHistoryExporter.exactFastRiseStr(r.timestamp, apsResults), colorInsulin),
                    Cell(df2.format(r.smbDeliveryRatio),            colorInsulin),
                    Cell(df2.format(r.iobThEffective),              colorHeader),
                    Cell(df2.format(r.bgAcceleration),              colorGlucose),
                    Cell(df2.format(r.delta / MGDL_TO_MMOL),        colorGlucose),
                    Cell(df2.format(r.shortAvgDelta / MGDL_TO_MMOL), colorGlucose),
                    Cell(insulinStr(r.insulinReq),                  colorInsulin),
                    Cell(insulinStr(r.tbrRate),                     colorInsulin),
                    Cell(insulinStr(r.iob),                         colorInsulin),
                    Cell(autoIsfHistoryExporter.iob5MinChangeStr(r, allRecords), colorInsulin),
                    Cell(autoIsfHistoryExporter.basalStr(r),        colorInsulin),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 5)?.toString()   ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 15)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 30)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 60)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 180)?.toString() ?: "--", colorHeader)
                )
            )
        }

        // Header and body are separate TableLayouts (so the header can stay frozen while the body
        // scrolls), which means they compute column widths independently. Once both are laid out,
        // widen each column to the max of its header/body width in both tables so they stay aligned.
        table.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                table.viewTreeObserver.removeOnGlobalLayoutListener(this)
                syncColumnWidths()
            }
        })
    }

    // Aligns the frozen header's columns to the scrolling body's columns. Reads the final laid-out
    // column widths (all rows in a TableLayout share per-column widths), then pins both tables to the
    // per-column max via minimumWidth. One-shot — the listener that calls it removes itself first.
    private fun syncColumnWidths() {
        val header = binding.historyHeader
        val table = binding.historyTable
        // Column-header row is the last (2nd) header row — its cells map 1:1 to body columns.
        val headerRow = header.getChildAt(header.childCount - 1) as? TableRow ?: return
        val bodyRow = table.getChildAt(0) as? TableRow ?: return
        val n = minOf(headerRow.childCount, bodyRow.childCount)
        if (n == 0) return
        for (i in 0 until n) {
            val hv = headerRow.getChildAt(i)
            val bv = bodyRow.getChildAt(i)
            val w = maxOf(hv.width, bv.width)
            hv.minimumWidth = w
            bv.minimumWidth = w
        }
        header.requestLayout()
        table.requestLayout()
    }

    private fun addHeaderRows(table: android.widget.TableLayout) {
        // Section header row
        addRow(
            table, cells = listOf(
                Cell("", colorTime),
                Cell("BG", colorGlucose, bold = true),
                Cell("Final Ratio", colorFinalRatio, bold = true),
                Cell("Adjustments", colorAcceIsf, span = 4, bold = true),
                Cell("SMB", colorInsulin, span = 3, bold = true),
                Cell("iobTH", colorHeader, bold = true),
                Cell("BG", colorGlucose, span = 3, bold = true),
                Cell("Insulin", colorInsulin, span = 5, bold = true),
                Cell("Steps", colorHeader, span = 5, bold = true)
            )
        )

        // Column header row
        addRow(
            table, cells = listOf(
                Cell("Time",   colorHeader, bold = true),
                Cell("BGL",    colorGlucose, bold = true),
                Cell("Final",  colorFinalRatio, bold = true),
                Cell("acce",   colorAcceIsf, bold = true),
                Cell("bg",     colorBgIsf,   bold = true),
                Cell("pp",     colorPpIsf,   bold = true),
                Cell("dura",   colorDuraIsf, bold = true),
                Cell("SMB",    colorInsulin, bold = true),
                Cell("FR",     colorInsulin, bold = true),
                Cell("Ratio",  colorInsulin, bold = true),
                Cell("iobTH",  colorHeader, bold = true),
                Cell("acce",   colorGlucose, bold = true),
                Cell("Δ",      colorGlucose, bold = true),
                Cell("SΔ",     colorGlucose, bold = true),
                Cell("Req",    colorInsulin, bold = true),
                Cell("TBR",    colorInsulin, bold = true),
                Cell("IOB",    colorInsulin, bold = true),
                Cell("IOBΔ5",  colorInsulin, bold = true),
                Cell("Basal",  colorInsulin, bold = true),
                Cell("S5",     colorHeader, bold = true),
                Cell("S15",    colorHeader, bold = true),
                Cell("S30",    colorHeader, bold = true),
                Cell("S60",    colorHeader, bold = true),
                Cell("S180",   colorHeader, bold = true)
            )
        )
    }

    /** Show "--" for neutral (1.0) adjustment values, matching Trio display. */
    private fun adjStr(v: Double): String = if (v == 1.0) "--" else df2.format(v)

    /** Show "--" for zero insulin values. */
    private fun insulinStr(v: Double): String = if (v == 0.0) "--" else df2.format(v)

    /** Color by dominant AutoISF adaptation type (acce/bg/pp/dura), or `fallback` if no factor dominates. */
    private fun dominantIsfColor(r: AIV, fallback: Int): Int {
        val acce = kotlin.math.abs(r.acceIsf - 1.0)
        val bg   = kotlin.math.abs(r.bgIsf   - 1.0)
        val pp   = kotlin.math.abs(r.ppIsf   - 1.0)
        val dura = kotlin.math.abs(r.duraIsf - 1.0)
        val maxDev = maxOf(acce, bg, pp, dura)
        if (maxDev <= 0.01) return fallback
        val ctx = context
        return when {
            acce >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.acceIsfColor)
            bg   >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.bgIsfColor)
            pp   >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.ppIsfColor)
            else           -> rh.gac(ctx, app.aaps.core.ui.R.attr.duraIsfColor)
        }
    }

    /** Color SMB cell by dominant AutoISF adaptation type, or colorInsulin if no SMB or no dominant factor. */
    private fun smbIsfColor(r: AIV): Int {
        if (r.smbDelivered == 0.0) return colorInsulin
        return dominantIsfColor(r, colorInsulin)
    }

    private data class Cell(val text: String, val color: Int, val span: Int = 1, val bold: Boolean = false)

    private fun addRow(table: android.widget.TableLayout, cells: List<Cell>) {
        val row = TableRow(requireContext())
        row.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
        for (cell in cells) {
            val tv = TextView(requireContext())
            tv.text = cell.text
            tv.setPadding(14, 6, 14, 6)
            tv.gravity = Gravity.CENTER
            tv.setTextColor(cell.color)
            tv.textSize = 12f
            if (cell.bold) tv.setTypeface(null, Typeface.BOLD)
            val lp = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
            lp.span = cell.span
            tv.layoutParams = lp
            row.addView(tv)
        }
        table.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
