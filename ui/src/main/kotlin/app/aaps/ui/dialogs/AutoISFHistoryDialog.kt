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
import java.util.concurrent.Executors
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(android.view.Window.FEATURE_NO_TITLE)
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
        if (records.isEmpty()) {
            addRow(binding.historyTable, isHeader = false, cells = listOf("No AutoISF data in last 2 hours"))
            return
        }
        addRow(binding.historyTable, isHeader = true, cells = listOf("Time", "Final", "acce", "bg", "pp", "dura", "drift", "iobTH"))
        for (record in records) {
            addRow(
                binding.historyTable, isHeader = false,
                cells = listOf(
                    dateUtil.timeString(record.timestamp),
                    df2.format(record.finalIsf),
                    df2.format(record.acceIsf),
                    df2.format(record.bgIsf),
                    df2.format(record.ppIsf),
                    df2.format(record.duraIsf),
                    df2.format(record.driftIsf),
                    df2.format(record.iobThEffective)
                )
            )
        }
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

    private fun addRow(table: android.widget.TableLayout, isHeader: Boolean, cells: List<String>) {
        val row = TableRow(requireContext())
        row.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT)
        for (text in cells) {
            val tv = TextView(requireContext())
            tv.text = text
            tv.setPadding(12, 6, 12, 6)
            tv.gravity = Gravity.CENTER
            if (isHeader) {
                tv.setTypeface(null, Typeface.BOLD)
                tv.setTextColor(Color.WHITE)
            }
            tv.textSize = 12f
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
