package app.aaps.ui.dialogs

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.databinding.DialogAutoisfHistoryBinding
import dagger.android.support.DaggerDialogFragment
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AutoISFHistoryDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var fileListProvider: FileListProvider
    @Inject lateinit var aapsLogger: AAPSLogger

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.closeButton.setOnClickListener { dismiss() }

        val now = System.currentTimeMillis()
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2)
        val records = persistenceLayer.getAutoIsfValuesFromTimeToTime(twoHoursAgo, now)
            .sortedByDescending { it.timestamp }

        populateTable(records)
        exportToCsv(records, now)
    }

    private fun populateTable(records: List<AIV>) {
        val table = binding.historyTable

        if (records.isEmpty()) {
            addRow(table, cells = listOf(Cell("No AutoISF data in last 2 hours", colorTime)))
            return
        }

        // Section header row
        addRow(
            table, cells = listOf(
                Cell("", colorTime),
                Cell("BG", colorGlucose, span = 4, bold = true),
                Cell("Final Ratio", colorFinalRatio, span = 1, bold = true),
                Cell("Adjustments", colorAcceIsf, span = 4, bold = true),
                Cell("Insulin", colorInsulin, span = 3, bold = true),
                Cell("iobTH", colorHeader, bold = true)
            )
        )

        // Column header row
        addRow(
            table, cells = listOf(
                Cell("Time",   colorHeader, bold = true),
                Cell("BGL",    colorGlucose, bold = true),
                Cell("Δ",      colorGlucose, bold = true),
                Cell("SΔ",     colorGlucose, bold = true),
                Cell("acce",   colorGlucose, bold = true),
                Cell("Final",  colorFinalRatio, bold = true),
                Cell("acce",   colorAcceIsf, bold = true),
                Cell("bg",     colorBgIsf,   bold = true),
                Cell("pp",     colorPpIsf,   bold = true),
                Cell("dura",   colorDuraIsf, bold = true),
                Cell("Req",    colorInsulin, bold = true),
                Cell("TBR",    colorInsulin, bold = true),
                Cell("SMB",    colorInsulin, bold = true),
                Cell("iobTH",  colorHeader, bold = true)
            )
        )

        for (r in records) {
            addRow(
                table, cells = listOf(
                    Cell(dateUtil.timeString(r.timestamp),          colorTime),
                    Cell(df1.format(r.glucose / MGDL_TO_MMOL),     colorGlucose),
                    Cell(df1.format(r.delta / MGDL_TO_MMOL),       colorGlucose),
                    Cell(df1.format(r.shortAvgDelta / MGDL_TO_MMOL), colorGlucose),
                    Cell(df2.format(r.bgAcceleration),              colorGlucose),
                    Cell(df2.format(r.finalIsf),                    colorFinalRatio),
                    Cell(adjStr(r.acceIsf),                         colorAcceIsf),
                    Cell(adjStr(r.bgIsf),                           colorBgIsf),
                    Cell(adjStr(r.ppIsf),                           colorPpIsf),
                    Cell(adjStr(r.duraIsf),                         colorDuraIsf),
                    Cell(insulinStr(r.insulinReq),                  colorInsulin),
                    Cell(insulinStr(r.tbrRate),                     colorInsulin),
                    Cell(insulinStr(r.smbDelivered),                smbIsfColor(r)),
                    Cell(df2.format(r.iobThEffective),              colorHeader)
                )
            )
        }
    }

    /** Show "--" for neutral (1.0) adjustment values, matching Trio display. */
    private fun adjStr(v: Double): String = if (v == 1.0) "--" else df2.format(v)

    /** Show "--" for zero insulin values. */
    private fun insulinStr(v: Double): String = if (v == 0.0) "--" else df2.format(v)

    /** Color SMB cell by dominant AutoISF adaptation type, or colorInsulin if no dominant factor. */
    private fun smbIsfColor(r: AIV): Int {
        if (r.smbDelivered == 0.0) return colorInsulin
        val acce = kotlin.math.abs(r.acceIsf - 1.0)
        val bg   = kotlin.math.abs(r.bgIsf   - 1.0)
        val pp   = kotlin.math.abs(r.ppIsf   - 1.0)
        val dura = kotlin.math.abs(r.duraIsf - 1.0)
        val maxDev = maxOf(acce, bg, pp, dura)
        if (maxDev <= 0.01) return colorInsulin
        val ctx = context
        return when {
            acce >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.acceIsfColor)
            bg   >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.bgIsfColor)
            pp   >= maxDev -> rh.gac(ctx, app.aaps.core.ui.R.attr.ppIsfColor)
            else           -> rh.gac(ctx, app.aaps.core.ui.R.attr.duraIsfColor)
        }
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

    private fun exportToCsv(records: List<AIV>, now: Long) {
        val ctx = context ?: return
        val act = activity
        Executors.newSingleThreadExecutor().execute {
            try {
                fileListProvider.ensureAapsLogsDirExists()
                val dir = fileListProvider.aapsLogsPath
                val fileName = "AutoISF_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".csv"
                val file = File(dir, fileName)
                file.bufferedWriter().use { writer ->
                    writer.write("Time,BGL,Delta,SDelta,acceBG,Final,acce,bg,pp,dura,Req,TBR,SMB,iobTH\n")
                    for (r in records) {
                        writer.write(
                            "${dateUtil.timeString(r.timestamp)}," +
                                "${df1.format(r.glucose / MGDL_TO_MMOL)}," +
                                "${df1.format(r.delta / MGDL_TO_MMOL)},${df1.format(r.shortAvgDelta / MGDL_TO_MMOL)}," +
                                "${df2.format(r.bgAcceleration)},${df2.format(r.finalIsf)}," +
                                "${df2.format(r.acceIsf)},${df2.format(r.bgIsf)}," +
                                "${df2.format(r.ppIsf)},${df2.format(r.duraIsf)}," +
                                "${df2.format(r.insulinReq)},${df2.format(r.tbrRate)},${df2.format(r.smbDelivered)}," +
                                "${df2.format(r.iobThEffective)}\n"
                        )
                    }
                }
                aapsLogger.debug(LTag.UI, "AutoISF history exported to ${file.absolutePath}")
                act?.runOnUiThread {
                    Toast.makeText(ctx, "Exported: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "AutoISF CSV export failed", e)
            }
        }
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
