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

    private val df2 = DecimalFormat("0.00")

    // Colors matching Trio TAI history screen
    private val colorFinalRatio = Color.parseColor("#FF6060")  // red
    private val colorAdjustments = Color.parseColor("#FFA040")  // orange
    private val colorTime       = Color.WHITE
    private val colorHeader     = Color.LTGRAY

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

        // Section header row: "Final Ratio" | "Adjustments"
        addRow(
            table, cells = listOf(
                Cell("", colorTime),                            // Time
                Cell("Final Ratio", colorFinalRatio, span = 1, bold = true),  // Final
                Cell("Adjustments", colorAdjustments, span = 5, bold = true), // acce bg pp dura drift
                Cell("iobTH", colorHeader, bold = true)
            )
        )

        // Column header row
        addRow(
            table, cells = listOf(
                Cell("Time", colorHeader, bold = true),
                Cell("Final", colorFinalRatio, bold = true),
                Cell("acce",  colorAdjustments, bold = true),
                Cell("bg",    colorAdjustments, bold = true),
                Cell("pp",    colorAdjustments, bold = true),
                Cell("dura",  colorAdjustments, bold = true),
                Cell("drift", colorAdjustments, bold = true),
                Cell("iobTH", colorHeader, bold = true)
            )
        )

        for (r in records) {
            addRow(
                table, cells = listOf(
                    Cell(dateUtil.timeString(r.timestamp), colorTime),
                    Cell(df2.format(r.finalIsf),  colorFinalRatio),
                    Cell(adjStr(r.acceIsf),        colorAdjustments),
                    Cell(adjStr(r.bgIsf),          colorAdjustments),
                    Cell(adjStr(r.ppIsf),          colorAdjustments),
                    Cell(adjStr(r.duraIsf),        colorAdjustments),
                    Cell(adjStr(r.driftIsf),       colorAdjustments),
                    Cell(df2.format(r.iobThEffective), colorHeader)
                )
            )
        }
    }

    /** Show "--" for neutral (1.0) adjustment values, matching Trio display. */
    private fun adjStr(v: Double): String = if (v == 1.0) "--" else df2.format(v)

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
        Executors.newSingleThreadExecutor().execute {
            try {
                val dir = fileListProvider.aapsLogsPath
                if (!dir.exists()) dir.mkdirs()
                val fileName = "AutoISF_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".csv"
                val file = File(dir, fileName)
                file.bufferedWriter().use { writer ->
                    writer.write("Time,Timestamp,Final,acce,bg,pp,dura,drift,iobTH\n")
                    for (r in records) {
                        writer.write(
                            "${dateUtil.timeString(r.timestamp)},${r.timestamp}," +
                                "${df2.format(r.finalIsf)},${df2.format(r.acceIsf)}," +
                                "${df2.format(r.bgIsf)},${df2.format(r.ppIsf)}," +
                                "${df2.format(r.duraIsf)},${df2.format(r.driftIsf)}," +
                                "${df2.format(r.iobThEffective)}\n"
                        )
                    }
                }
                aapsLogger.debug(LTag.UI, "AutoISF history exported to ${file.absolutePath}")
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Exported: $fileName", Toast.LENGTH_LONG).show()
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
