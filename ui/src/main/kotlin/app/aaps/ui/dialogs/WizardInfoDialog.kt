package app.aaps.ui.dialogs

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.aaps.core.data.model.BCR
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.databinding.DialogWizardinfoBinding
import app.aaps.ui.R
import com.google.gson.Gson
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject
import kotlin.math.abs

class WizardInfoDialog : DaggerDialogFragment() {

    companion object {

        /**
         * The dialog for one saved calculation, packed the way onCreateView reads it back. [givenNow] is the bolus actually
         * given with it, when known (Treatments has it); then a dose over MaxBolus shows what was given and what was left to split.
         */
        fun newInstance(bolusCalculatorResult: BCR, givenNow: Double? = null): WizardInfoDialog = WizardInfoDialog().also { dialog ->
            dialog.arguments = Bundle().also {
                it.putString("data", Gson().toJson(bolusCalculatorResult).toString())
                if (givenNow != null) it.putDouble("givenNow", givenNow)
            }
        }
    }

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil

    private lateinit var data: BCR
    private var givenNow: Double? = null

    private var _binding: DialogWizardinfoBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (savedInstanceState ?: arguments)?.let {
            it.getString("data")?.let { str ->
                data = Gson().fromJson(str, BCR::class.java)
            }
            if (it.containsKey("givenNow")) givenNow = it.getDouble("givenNow")
        }
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)
        _binding = DialogWizardinfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("data", Gson().toJson(data).toString())
        givenNow?.let { outState.putDouble("givenNow", it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.close.setOnClickListener { dismiss() }
        val bgString = profileUtil.fromMgdlToStringInUnits(data.glucoseValue)
        val isf = profileUtil.fromMgdlToUnits(data.isf)
        val trend = profileUtil.fromMgdlToStringInUnits(data.glucoseTrend * 3)
        fun units(value: Double) = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, value)
        // 2026-10-21, per explicit request: when it was calculated
        addCalcRow(rh.gs(R.string.calc_detail_time, dateUtil.dateAndTimeAndSecondsString(data.timestamp)), "")
        if (data.wasGlucoseUsed) {
            addCalcRow(rh.gs(R.string.format_bg_isf, bgString, isf), units(data.glucoseInsulin))
            if (data.wasTempTargetUsed) addCalcRow(rh.gs(app.aaps.core.ui.R.string.tt_label), "")
        }
        if (data.wasTrendUsed) addCalcRow(rh.gs(R.string.calc_detail_trend, trend), units(data.trendInsulin))
        if (data.wasCOBUsed) addCalcRow(rh.gs(R.string.format_cob_ic, data.cob, data.ic), units(data.cobInsulin))
        if (data.wereCarbsUsed) addCalcRow(rh.gs(R.string.format_carbs_ic, data.carbs, data.ic), units(data.carbsInsulin))
        // IOB and corrections come before the percentage (2026-10-21): the wizard applies the % to the whole sum, IOB included,
        // so with IOB after it the rows did not add up to the total.
        if (data.wasBolusIOBUsed) addCalcRow(rh.gs(app.aaps.core.ui.R.string.bolus_iob_label), units(-data.bolusIOB))
        if (data.wasBasalIOBUsed) addCalcRow(rh.gs(app.aaps.core.ui.R.string.treatments_wizard_basaliob_label), units(-data.basalIOB))
        if (abs(data.otherCorrection) > 0.005) addCalcRow(rh.gs(R.string.calc_detail_correction), units(data.otherCorrection))
        if (data.wasSuperbolusUsed) addCalcRow(rh.gs(app.aaps.core.ui.R.string.superbolus), units(data.superbolusInsulin))
        if (data.percentageCorrection != 100) {
            addCalcDivider()
            val subtotal = data.glucoseInsulin + data.trendInsulin + data.cobInsulin + data.carbsInsulin +
                (if (data.wasBolusIOBUsed) -data.bolusIOB else 0.0) + (if (data.wasBasalIOBUsed) -data.basalIOB else 0.0) +
                data.otherCorrection + (if (data.wasSuperbolusUsed) data.superbolusInsulin else 0.0)
            addCalcRow(rh.gs(R.string.calc_detail_subtotal), units(subtotal))
            addCalcRow(rh.gs(app.aaps.core.ui.R.string.format_percent, data.percentageCorrection), units(subtotal * data.percentageCorrection / 100.0))
        }
        addCalcDivider()
        addCalcRow(rh.gs(R.string.calc_detail_total), units(data.totalInsulin), bold = true)
        // A dose over MaxBolus (2026-10-21, per explicit request): what was given now and what was left to split
        givenNow?.let { given ->
            if (data.totalInsulin > given + 0.005) {
                addCalcRow(rh.gs(R.string.calc_detail_given_now), units(given))
                addCalcRow(rh.gs(R.string.calc_detail_to_split), units(data.totalInsulin - given))
            }
        }
        addCalcDivider()
        addCalcFooter(rh.gs(R.string.calc_detail_profile, data.profileName))
        if (data.note.isNotEmpty()) addCalcFooter(rh.gs(R.string.calc_detail_notes, data.note))
    }

    private fun addCalcRow(label: String, amount: String, bold: Boolean = false) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(requireContext()).apply {
            text = amount
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        binding.calcRows.addView(row)
    }

    private fun addCalcDivider() {
        val color = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, color, true)
        val divider = View(requireContext()).apply {
            if (color.resourceId != 0) setBackgroundColor(requireContext().getColor(color.resourceId))
            else setBackgroundColor(color.data)
            alpha = 0.25f
        }
        binding.calcRows.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
    }

    private fun addCalcFooter(value: String) {
        binding.calcRows.addView(TextView(requireContext()).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}