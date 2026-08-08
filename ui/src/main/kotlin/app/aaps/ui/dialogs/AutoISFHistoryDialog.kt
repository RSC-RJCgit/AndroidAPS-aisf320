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
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
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
        private const val KEY_SMB_ONLY = "smbOnly"
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
    private var allSmbBoluses: List<BS> = emptyList()
    private var allMjNotes: List<TE> = emptyList()
    private var allRawReadings: List<GV> = emptyList()
    private var smbOnly = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Restore the SMB-only filter across rotation so it isn't reset to "All" on config change.
        smbOnly = savedInstanceState?.getBoolean(KEY_SMB_ONLY) ?: false
        binding.smbOnlyButton.text = if (smbOnly) "All" else "SMB only"
        binding.closeButton.setOnClickListener { dismiss() }
        binding.smbOnlyButton.setOnClickListener {
            smbOnly = !smbOnly
            binding.smbOnlyButton.text = if (smbOnly) "All" else "SMB only"
            rebuildTable()
        }

        // Keep the frozen header's horizontal scroll in lock-step with the body's, so the column
        // titles stay above the right data columns as you scroll sideways. The != guard stops the
        // two listeners from ping-ponging each other into a feedback loop.
        binding.headerHscroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (binding.bodyHscroll.scrollX != scrollX) binding.bodyHscroll.scrollTo(scrollX, 0)
        }
        binding.bodyHscroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (binding.headerHscroll.scrollX != scrollX) binding.headerHscroll.scrollTo(scrollX, 0)
        }

        val now = System.currentTimeMillis()
        val sixHoursAgo = now - TimeUnit.HOURS.toMillis(6)
        allRecords = persistenceLayer.getAutoIsfValuesFromTimeToTime(sixHoursAgo, now)
            .sortedByDescending { it.timestamp }
        allApsResults = persistenceLayer.getApsResults(sixHoursAgo, now)
        allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(sixHoursAgo, now)
        allSmbBoluses = persistenceLayer.getBolusesFromTimeToTime(sixHoursAgo, now, ascending = false).filter { it.type == BS.Type.SMB }
        allMjNotes = autoIsfHistoryExporter.mjNotesFrom(sixHoursAgo)
        // Raw BG readings (noise = raw Libre) for the rΔ columns; 20-min lead-in for the oldest rows
        allRawReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(sixHoursAgo - 20 * 60_000L, now, ascending = false)

        rebuildTable()
        // Opening the dialog also writes the export files (CSV / text / settings), off the UI thread —
        // but only on first creation, not on every rotation (savedInstanceState != null on re-create),
        // so rotating doesn't spam duplicate export files. The automatic 6-hourly export runs from
        // KeepAliveWorker via the same AutoIsfHistoryExporter. buildCombinedExport() runs right after,
        // on the same background thread, so its rebuild always sees this dialog's own fresh write as
        // "the last file" without any extra coordination.
        if (savedInstanceState == null) {
            Executors.newSingleThreadExecutor().execute {
                autoIsfHistoryExporter.writeExport(allRecords, allApsResults, allStepsCounts, allSmbBoluses, allMjNotes, allRawReadings, now)
                autoIsfHistoryExporter.buildCombinedExport()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SMB_ONLY, smbOnly)
    }

    // Clears and repopulates the table honouring the SMB-only filter. The IOB-5-min-change column
    // always looks back in the full record set, so filtering rows never distorts that value.
    private fun rebuildTable() {
        binding.historyCorner.removeAllViews()
        binding.historyHeader.removeAllViews()
        binding.historyTime.removeAllViews()
        binding.historyTable.removeAllViews()
        val shown = if (smbOnly) allRecords.filter { it.smbDelivered > 0.0 } else allRecords
        populateTable(shown, allApsResults, allStepsCounts)
    }

    private fun populateTable(records: List<AIV>, apsResults: List<APSResult>, stepsCountList: List<SC>) {
        val table = binding.historyTable

        addHeaderRows()

        if (records.isEmpty()) {
            addRow(table, cells = listOf(Cell("No AutoISF data in last 6 hours", colorTime)))
            return
        }

        val cobTByTimestamp = autoIsfHistoryExporter.calculatedCobT(allRecords)
        for (r in records) {
            val sc = autoIsfHistoryExporter.stepsAt(r.timestamp, stepsCountList)
            // Cell 0 (Time) goes into the frozen left column; cells 1.. into the scrolling body.
            addSplitRow(
                binding.historyTime, table, cells = listOf(
                    Cell(dateUtil.timeString(r.timestamp),          colorTime),
                    Cell(df1.format(r.glucose / MGDL_TO_MMOL),      colorGlucose),
                    Cell(df2.format(r.finalIsf),                    dominantIsfColor(r, colorFinalRatio)),
                    Cell(adjStr(r.acceIsf),                         colorAcceIsf),
                    Cell(adjStr(r.bgIsf),                           colorBgIsf),
                    Cell(adjStr(r.ppIsf),                           colorPpIsf),
                    Cell(adjStr(r.duraIsf),                         colorDuraIsf),
                    Cell(df2.format(r.uamCarbImpact),               colorHeader),
                    Cell(insulinStr(r.smbDelivered),                smbIsfColor(r)),
                    Cell(autoIsfHistoryExporter.exactFastRiseStr(r.timestamp, apsResults), colorInsulin),
                    Cell(df2.format(r.smbDeliveryRatio),            colorInsulin),
                    Cell(autoIsfHistoryExporter.smbInterval5SecStr(r.timestamp, allSmbBoluses), colorInsulin),
                    Cell(df2.format(r.iobThEffective),              colorHeader),
                    Cell(df2.format(r.acceIsfWeight),               colorHeader),
                    Cell(autoIsfHistoryExporter.ppWeightStr(r.timestamp, apsResults), colorHeader),
                    Cell(df2.format(r.fslCalSlope),                 colorHeader),
                    Cell(df2.format(r.bgAcceleration),              colorGlucose),
                    Cell(df2.format(r.delta / MGDL_TO_MMOL),        colorGlucose),
                    Cell(df2.format(r.shortAvgDelta / MGDL_TO_MMOL), colorGlucose),
                    Cell(autoIsfHistoryExporter.rawBglStr(r.timestamp, allRawReadings),       colorGlucose),
                    Cell(autoIsfHistoryExporter.rawDeltaStr(r.timestamp, allRawReadings, 1),  colorGlucose),
                    Cell(autoIsfHistoryExporter.rawDeltaStr(r.timestamp, allRawReadings, 5),  colorGlucose),
                    Cell(autoIsfHistoryExporter.rawDeltaStr(r.timestamp, allRawReadings, 15), colorGlucose),
                    Cell(if (r.ukfRawBgl > 0.0) df1.format(r.ukfRawBgl / MGDL_TO_MMOL) else "--", colorGlucose),
                    Cell(autoIsfHistoryExporter.ukfDeltaStr(r, allRecords, 5),  colorGlucose),
                    Cell(autoIsfHistoryExporter.ukfDeltaStr(r, allRecords, 15), colorGlucose),
                    Cell(autoIsfHistoryExporter.readingIntervalStr(r.timestamp, allRawReadings), colorGlucose),
                    Cell(insulinStr(r.insulinReq),                  colorInsulin),
                    Cell(insulinStr(r.tbrRate),                     colorInsulin),
                    Cell(insulinStr(r.iob),                         colorInsulin),
                    Cell(autoIsfHistoryExporter.iob5MinChangeStr(r, allRecords), colorInsulin),
                    Cell(autoIsfHistoryExporter.basalStr(r),        colorInsulin),
                    Cell(autoIsfHistoryExporter.cobStr(r),          colorInsulin),
                    Cell(autoIsfHistoryExporter.cobTStr(r, cobTByTimestamp), colorInsulin),
                    Cell(autoIsfHistoryExporter.carbAbsStr(r),      colorInsulin),
                    Cell(autoIsfHistoryExporter.hpStr(r, allRawReadings), colorInsulin),
                    Cell(autoIsfHistoryExporter.hp2Str(r, allRecords, cobTByTimestamp), colorInsulin),
                    Cell(autoIsfHistoryExporter.lowBgRecentStr(r.timestamp, apsResults), colorInsulin),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 5)?.toString()   ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 15)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 30)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 60)?.toString()  ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.stepsValue(sc, r.timestamp, apsResults, 180)?.toString() ?: "--", colorHeader),
                    Cell(autoIsfHistoryExporter.mjStateStr(r.timestamp, allMjNotes), colorTime)
                )
            )
        }

        // The four TableLayouts (corner/header/time/body) compute column widths independently. Once
        // laid out, align the Time column (corner vs time) and the data columns (header vs body) so
        // the frozen header and frozen Time column line up with the scrolling body.
        table.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                table.viewTreeObserver.removeOnGlobalLayoutListener(this)
                syncColumnWidths()
            }
        })
    }

    /** Adds one row split across two tables: the first cell into [leftTable] (frozen Time column),
     *  the remaining cells into [rightTable] (the horizontally-scrolling body). Section-header spans
     *  live entirely in cells 1.. so they stay intact after dropping the Time cell. */
    private fun addSplitRow(leftTable: android.widget.TableLayout, rightTable: android.widget.TableLayout, cells: List<Cell>) {
        addRow(leftTable, listOf(cells.first()))
        addRow(rightTable, cells.drop(1))
    }

    // Aligns two frozen/scrolling table pairs so their columns line up: the Time column (corner vs
    // time) and the data columns (header vs body). One-shot — the listener that calls it removes
    // itself first.
    private fun syncColumnWidths() {
        syncTwoTables(binding.historyCorner, binding.historyTime)
        syncTwoTables(binding.historyHeader, binding.historyTable)
    }

    // Reads the final laid-out per-column widths of a header table and its body table (all rows in a
    // TableLayout share per-column widths), then pins both to the per-column max via minimumWidth.
    private fun syncTwoTables(headerTbl: android.widget.TableLayout, bodyTbl: android.widget.TableLayout) {
        val headerRow = headerTbl.getChildAt(headerTbl.childCount - 1) as? TableRow ?: return
        val bodyRow = bodyTbl.getChildAt(0) as? TableRow ?: return
        val n = minOf(headerRow.childCount, bodyRow.childCount)
        if (n == 0) return
        for (i in 0 until n) {
            val hv = headerRow.getChildAt(i)
            val bv = bodyRow.getChildAt(i)
            val w = maxOf(hv.width, bv.width)
            hv.minimumWidth = w
            bv.minimumWidth = w
        }
        headerTbl.requestLayout()
        bodyTbl.requestLayout()
    }

    private fun addHeaderRows() {
        // Section header row — Time cell (col 0) to the corner, the spanned groups to the rest.
        addSplitRow(
            binding.historyCorner, binding.historyHeader, cells = listOf(
                Cell("", colorTime),
                Cell("BG", colorGlucose, bold = true),
                Cell("Final Ratio", colorFinalRatio, bold = true),
                Cell("Adjustments", colorAcceIsf, span = 4, bold = true),
                Cell("UAM", colorHeader, bold = true),
                Cell("SMB", colorInsulin, span = 4, bold = true),
                Cell("iobTH", colorHeader, bold = true),
                Cell("Settings", colorHeader, span = 3, bold = true),
                Cell("BG", colorGlucose, span = 10, bold = true),
                // COBt is included in this group with the other calculated insulin/carb context fields.
                // Group is Req/TBR/IOB/IOBd5/Basal/COB/COBt/carbAbs/HP/HP2/LowBG.
                Cell("Insulin", colorInsulin, span = 11, bold = true),
                Cell("Steps", colorHeader, span = 5, bold = true),
                Cell("MJ", colorTime, bold = true)
            )
        )

        // Column header row — "Time" to the corner, the rest to the scrolling header.
        addSplitRow(
            binding.historyCorner, binding.historyHeader, cells = listOf(
                Cell("Time",   colorHeader, bold = true),
                Cell("BGL",    colorGlucose, bold = true),
                Cell("Final",  colorFinalRatio, bold = true),
                Cell("acce",   colorAcceIsf, bold = true),
                Cell("bg",     colorBgIsf,   bold = true),
                Cell("pp",     colorPpIsf,   bold = true),
                Cell("dura",   colorDuraIsf, bold = true),
                Cell("UAMci",  colorHeader, bold = true),
                Cell("SMB",    colorInsulin, bold = true),
                Cell("FR",     colorInsulin, bold = true),
                Cell("Ratio",  colorInsulin, bold = true),
                Cell("SMBi5",  colorInsulin, bold = true),
                Cell("iobTH",  colorHeader, bold = true),
                Cell("acWt",   colorHeader, bold = true),
                Cell("ppWt",   colorHeader, bold = true),
                Cell("Lslope", colorHeader, bold = true),
                Cell("acce",   colorGlucose, bold = true),
                Cell("Δ",      colorGlucose, bold = true),
                Cell("SΔ",     colorGlucose, bold = true),
                Cell("rBGL",   colorGlucose, bold = true),
                Cell("rΔ1",    colorGlucose, bold = true),
                Cell("rΔ5",    colorGlucose, bold = true),
                Cell("rΔ15",   colorGlucose, bold = true),
                Cell("ukfRBGL", colorGlucose, bold = true),
                Cell("RawUKF5",  colorGlucose, bold = true),
                Cell("RawUKF15", colorGlucose, bold = true),
                Cell("Int5",   colorGlucose, bold = true),
                Cell("Req",    colorInsulin, bold = true),
                Cell("TBR",    colorInsulin, bold = true),
                Cell("IOB",    colorInsulin, bold = true),
                Cell("IOBΔ5",  colorInsulin, bold = true),
                Cell("Basal",  colorInsulin, bold = true),
                Cell("COB",    colorInsulin, bold = true),
                Cell("COBt",   colorInsulin, bold = true),
                Cell("carbAbs", colorInsulin, bold = true),
                Cell("HP",     colorInsulin, bold = true),
                Cell("HP2",    colorInsulin, bold = true),
                Cell("LowBG",  colorInsulin, bold = true),
                Cell("S5",     colorHeader, bold = true),
                Cell("S15",    colorHeader, bold = true),
                Cell("S30",    colorHeader, bold = true),
                Cell("S60",    colorHeader, bold = true),
                Cell("S180",   colorHeader, bold = true),
                Cell("MJ",     colorTime, bold = true)
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
        // Full height (not WRAP_CONTENT) so the weighted scroll region has a bounded space to flex
        // within — otherwise in landscape the fixed content grows past the screen and the button row
        // at the bottom is pushed off. The scroll area shrinks to keep the buttons visible.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
