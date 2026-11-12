package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import app.aaps.core.data.model.CarbsType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogEditQuickwizardBinding
import app.aaps.ui.events.EventQuickWizardChange
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.android.support.DaggerDialogFragment
import org.json.JSONException
import java.text.DecimalFormat
import javax.inject.Inject

class EditQuickWizardDialog : DaggerDialogFragment(), View.OnClickListener {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper

    var position = -1
    private var fromSeconds: Int = 0
    private var toSeconds: Int = 0
    // Meal type of this button (2026-11-05, per explicit request: v4's Bread / Cake / Pizza)
    private var carbsType = CarbsType.BREAD

    companion object {

        const val MIN_PERCENTAGE: Double = 10.0
        const val MAX_PERCENTAGE: Double = 200.0
        const val DEFAULT_PERCENTAGE: Double = 100.0
    }

    private var _binding: DialogEditQuickwizardBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)
        _binding = DialogEditQuickwizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (arguments ?: savedInstanceState)?.let { bundle ->
            position = bundle.getInt("position", -1)
        }
        val entry = if (position == -1) quickWizard.newEmptyItem() else quickWizard[position]
        // A new button starts IOB, COB, Trend and Split fat/protein from the bolus calculator's last saved ticks
        // (2026-11-12, per explicit request). COB needs IOB, as in the calculator. Never changed, those ticks equal
        // the old fixed defaults (IOB on, COB off, Trend off, Split on).
        if (position == -1) {
            val useIob = preferences.get(BooleanKey.WizardIncludeIob)
            val useCob = useIob && preferences.get(BooleanKey.WizardIncludeCob)
            entry.storage.put("useIOB", if (useIob) QuickWizardEntry.YES else QuickWizardEntry.NO)
            entry.storage.put("useCOB", if (useCob) QuickWizardEntry.YES else QuickWizardEntry.NO)
            entry.storage.put("useTrend", if (preferences.get(BooleanKey.WizardIncludeTrend)) QuickWizardEntry.YES else QuickWizardEntry.NO)
            entry.storage.put("splitFpuBolus", preferences.get(BooleanKey.WizardSplitFpuBolus))
        }
        if (preferences.get(BooleanKey.WearControl)) {
            binding.devicePhoneCheckbox.visibility = View.VISIBLE
            binding.deviceWatchCheckbox.visibility = View.VISIBLE
            binding.devicePhoneImage.visibility = View.VISIBLE
            binding.deviceWatchImage.visibility = View.VISIBLE
        } else {
            binding.devicePhoneCheckbox.visibility = View.GONE
            binding.deviceWatchCheckbox.visibility = View.GONE
            binding.devicePhoneImage.visibility = View.GONE
            binding.deviceWatchImage.visibility = View.GONE
        }

        if (preferences.get(BooleanKey.OverviewUseSuperBolus)) {
            binding.useSuperBolus.visibility = View.VISIBLE
        } else {
            binding.useSuperBolus.visibility = View.GONE
        }

        binding.okcancel.ok.setOnClickListener {
            val carbs = SafeParse.stringToInt(binding.carbsInput.text)
            val carbs2 = SafeParse.stringToInt(binding.carbs2.text)
            val useECarbs = binding.useEcarbs.isChecked && !binding.splitFpuBolusCheckbox.isChecked

            if (carbs > 0 || (useECarbs && carbs2 > 0) ||
                (binding.splitFpuBolusCheckbox.isChecked && (binding.proteinInput.value > 0 || binding.fatInput.value > 0))) {
                try {
                    entry.storage.put("buttonText", binding.buttonEdit.text.toString())
                    entry.storage.put("carbs", carbs)
                    entry.storage.put("carbTime", SafeParse.stringToInt(binding.carbTimeInput.text))
                    entry.storage.put("useAlarm", checkBoxToRadioNumbers(binding.alarm.isChecked))
                    entry.storage.put("validFrom", fromSeconds)
                    entry.storage.put("validTo", toSeconds)
                    entry.storage.put("useBG", checkBoxToRadioNumbers(binding.useBg.isChecked))
                    entry.storage.put("useCOB", checkBoxToRadioNumbers(binding.useCob.isChecked))
                    entry.storage.put("useIOB", checkBoxToRadioNumbers(binding.useIob.isChecked))
                    entry.storage.put("usePositiveIOBOnly", checkBoxToRadioNumbers(binding.usePositiveIobOnly.isChecked))
                    entry.storage.put("useTrend", useTrendToInt())
                    entry.storage.put("useSuperBolus", checkBoxToRadioNumbers(binding.useSuperBolus.isChecked))
                    entry.storage.put("useTempTarget", checkBoxToRadioNumbers(binding.useTempTarget.isChecked))
                    entry.storage.put("percentage", binding.correctionInput.value)
                    if (binding.devicePhoneCheckbox.isChecked && binding.deviceWatchCheckbox.isChecked) {
                        entry.storage.put("device", QuickWizardEntry.DEVICE_ALL)
                    } else if (binding.devicePhoneCheckbox.isChecked) {
                        entry.storage.put("device", QuickWizardEntry.DEVICE_PHONE)
                    } else if (binding.deviceWatchCheckbox.isChecked) {
                        entry.storage.put("device", QuickWizardEntry.DEVICE_WATCH)
                    }
                    entry.storage.put("useEcarbs", checkBoxToRadioNumbers(binding.useEcarbs.isChecked))
                    entry.storage.put("time", binding.time.value.toInt())
                    entry.storage.put("duration", SafeParse.stringToInt(binding.duration.text))
                    entry.storage.put("carbs2", carbs2)
                    entry.storage.put("useWalkingSoon", if (binding.walkingSoonAutoCheckbox.isChecked) QuickWizardEntry.AUTO else checkBoxToRadioNumbers(binding.walkingSoonCheckbox.isChecked))
                    entry.storage.put("splitFpuBolus", binding.splitFpuBolusCheckbox.isChecked)
                    entry.storage.put("retryFpuLater", binding.retryFpuLaterCheckbox.isChecked)
                    entry.storage.put("fpuIntervalMinutes", binding.fpuIntervalInput.value.toInt().coerceIn(30, 60))
                    entry.storage.put("useUnreliableSmb", checkBoxToRadioNumbers(binding.unreliableSmbsCheckbox.isChecked))
                    entry.storage.put("useSmallMeal", checkBoxToRadioNumbers(binding.smallMealCheckbox.isChecked))
                    entry.storage.put("useAutoFpu", checkBoxToRadioNumbers(binding.autoFpuCheckbox.isChecked))
                    entry.storage.put("useSplitBolus", checkBoxToRadioNumbers(binding.splitBolusCheckbox.isChecked))
                    entry.storage.put("splitBolusIntervalMins", binding.splitBolusIntervalInput.value.toInt())
                    entry.storage.put("protein", binding.proteinInput.value.toInt())
                    entry.storage.put("fat", binding.fatInput.value.toInt())
                    entry.storage.put("warsawDurationHours", binding.warsawDurationInput.value)
                    entry.storage.put("carbsType", carbsType.name)
                } catch (e: JSONException) {
                    aapsLogger.error("Unhandled exception", e)
                }

                quickWizard.addOrUpdate(entry)
                rxBus.send(EventQuickWizardChange())
                dismiss()
            } else {
                ToastUtils.warnToast(context, R.string.change_your_input)
            }
        }
        binding.okcancel.cancel.setOnClickListener { dismiss() }

        binding.from.setOnClickListener {
            val clockFormat = if (DateFormat.is24HourFormat(context)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(clockFormat)
                .setHour(T.secs(fromSeconds.toLong()).hours().toInt())
                .setMinute(T.secs((fromSeconds % 3600).toLong()).mins().toInt())
                .build()
            timePicker.addOnPositiveButtonClickListener {
                fromSeconds = (T.hours(timePicker.hour.toLong()).secs() + T.mins(timePicker.minute.toLong()).secs()).toInt()
                binding.from.text = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(fromSeconds))
            }
            timePicker.show(parentFragmentManager, "event_time_time_picker")
        }

        fromSeconds = entry.validFrom()
        binding.from.text = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(fromSeconds))

        binding.to.setOnClickListener {
            val clockFormat = if (DateFormat.is24HourFormat(context)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(clockFormat)
                .setHour(T.secs(toSeconds.toLong()).hours().toInt())
                .setMinute(T.secs((toSeconds % 3600).toLong()).mins().toInt())
                .build()
            timePicker.addOnPositiveButtonClickListener {
                toSeconds = (T.hours(timePicker.hour.toLong()).secs() + T.mins(timePicker.minute.toLong()).secs()).toInt()
                binding.to.text = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(toSeconds))
            }
            timePicker.show(parentFragmentManager, "event_time_time_picker")
        }

        fun useECarbs(yes: Boolean) {
            if (yes && !binding.splitFpuBolusCheckbox.isChecked) {
                binding.timeLabel.visibility = View.VISIBLE
                binding.time.visibility = View.VISIBLE
                binding.durationLabel.visibility = View.VISIBLE
                binding.duration.visibility = View.VISIBLE
                binding.carbs2Label.visibility = View.VISIBLE
                binding.carbs2.visibility = View.VISIBLE
                binding.minLabel.visibility = View.VISIBLE
                binding.hLabel.visibility = View.VISIBLE
                binding.gLabel.visibility = View.VISIBLE
            } else {
                binding.timeLabel.visibility = View.GONE
                binding.time.visibility = View.GONE
                binding.durationLabel.visibility = View.GONE
                binding.duration.visibility = View.GONE
                binding.carbs2Label.visibility = View.GONE
                binding.carbs2.visibility = View.GONE
                binding.minLabel.visibility = View.GONE
                binding.hLabel.visibility = View.GONE
                binding.gLabel.visibility = View.GONE
            }
        }

        binding.useEcarbs.setOnCheckedChangeListener { _, checkedId ->
            useECarbs(checkedId)
        }

        binding.useIob.setOnCheckedChangeListener { _, _ -> processIOB() }

        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()

        binding.correctionInput.setParams(
            savedInstanceState?.getDouble("percentage")
                ?: DEFAULT_PERCENTAGE, MIN_PERCENTAGE, MAX_PERCENTAGE, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.time.setParams(
            savedInstanceState?.getDouble("time")
                ?: 0.0, -7 * 24 * 60.0, 12 * 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.duration.setParams(
            savedInstanceState?.getDouble("duration")
                ?: 0.0, 0.0, 10.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.carbs2.setParams(
            savedInstanceState?.getDouble("carbs2")
                ?: 0.0, 0.0, maxCarbs, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.carbsInput.setParams(
            savedInstanceState?.getDouble("carbs2")
                ?: 0.0, 0.0, maxCarbs, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.carbTimeInput.setParams(
            savedInstanceState?.getDouble("carb_time_input")
                ?: 0.0, -60.0, 60.0, 5.0, DecimalFormat("0"), false, binding.okcancel.ok, timeTextWatcher
        )

        binding.splitBolusIntervalInput.setParams(
            savedInstanceState?.getDouble("split_bolus_interval_input")
                ?: 7.0, 1.0, 60.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.proteinInput.setParams(
            savedInstanceState?.getDouble("protein_input")
                ?: 0.0, 0.0, 200.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.fatInput.setParams(
            savedInstanceState?.getDouble("fat_input")
                ?: 0.0, 0.0, 200.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )

        binding.warsawDurationInput.setParams(
            savedInstanceState?.getDouble("warsaw_duration_input")
                ?: 5.0, 0.0, 24.0, 0.5, DecimalFormat("0.0"), false, binding.okcancel.ok, textWatcher
        )

        binding.correctionInput.value = entry.percentage().toDouble()

        toSeconds = entry.validTo()
        binding.to.text = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(toSeconds))

        binding.buttonEdit.setText(entry.buttonText())
        when (entry.device()) {
            QuickWizardEntry.DEVICE_ALL   -> {
                binding.devicePhoneCheckbox.isChecked = true
                binding.deviceWatchCheckbox.isChecked = true
            }

            QuickWizardEntry.DEVICE_PHONE -> {
                binding.devicePhoneCheckbox.isChecked = true
                binding.deviceWatchCheckbox.isChecked = false
            }

            QuickWizardEntry.DEVICE_WATCH -> {
                binding.devicePhoneCheckbox.isChecked = false
                binding.deviceWatchCheckbox.isChecked = true
            }
        }
        binding.carbsInput.value = SafeParse.stringToDouble(entry.carbs().toString())
        binding.carbTimeInput.value = SafeParse.stringToDouble(entry.carbTime().toString())
        binding.alarm.isChecked = radioNumbersToCheckBox(entry.useAlarm())

        binding.useBg.isChecked = radioNumbersToCheckBox(entry.useBG())
        binding.useCob.isChecked = radioNumbersToCheckBox(entry.useCOB())
        binding.useIob.isChecked = radioNumbersToCheckBox(entry.useIOB())
        binding.usePositiveIobOnly.isChecked = radioNumbersToCheckBox(entry.usePositiveIOBOnly())
        processIntToTrendSelection(entry.useTrend())
        binding.useSuperBolus.isChecked = radioNumbersToCheckBox(entry.useSuperBolus())
        binding.useTempTarget.isChecked = radioNumbersToCheckBox(entry.useTempTarget())

        binding.useEcarbs.isChecked = radioNumbersToCheckBox(entry.storedUseEcarbs())
        binding.carbs2.value = SafeParse.stringToDouble(entry.carbs2().toString())
        binding.time.value = SafeParse.stringToDouble(entry.time().toString())
        binding.duration.value = SafeParse.stringToDouble(entry.duration().toString())
        useECarbs(radioNumbersToCheckBox(entry.useEcarbs()))

        binding.walkingSoonAutoCheckbox.isChecked = entry.useWalkingSoon() == QuickWizardEntry.AUTO
        binding.walkingSoonCheckbox.isChecked = entry.useWalkingSoon() == QuickWizardEntry.YES
        binding.walkingSoonCheckbox.isEnabled = !binding.walkingSoonAutoCheckbox.isChecked
        binding.walkingSoonAutoCheckbox.setOnCheckedChangeListener { _, checked -> binding.walkingSoonCheckbox.isEnabled = !checked }
        binding.splitFpuBolusCheckbox.isChecked = entry.splitFpuBolus()
        binding.fpuIntervalInput.setParams(
            entry.fpuIntervalMinutes().toDouble(), 30.0, 60.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok
        )
        fun updateFpuMode() {
            val split = binding.splitFpuBolusCheckbox.isChecked
            binding.useEcarbs.visibility = if (split) View.GONE else View.VISIBLE
            binding.fpuIntervalRow.visibility = if (split) View.VISIBLE else View.GONE
            useECarbs(binding.useEcarbs.isChecked)
        }
        binding.splitFpuBolusCheckbox.setOnCheckedChangeListener { _, _ -> updateFpuMode(); updateCarbsTypeInfo() }
        updateFpuMode()
        binding.retryFpuLaterCheckbox.isChecked = entry.retryFpuLater()
        binding.unreliableSmbsCheckbox.isChecked = entry.unreliableSmb()
        binding.smallMealCheckbox.isChecked = entry.smallMeal()
        binding.autoFpuCheckbox.isChecked = entry.autoFpu()
        binding.splitBolusCheckbox.isChecked = radioNumbersToCheckBox(entry.useSplitBolus())
        binding.splitBolusIntervalInput.value = SafeParse.stringToDouble(entry.splitBolusIntervalMins().toString())
        processSplitBolus()
        binding.splitBolusCheckbox.setOnCheckedChangeListener { _, _ -> processSplitBolus() }

        binding.proteinInput.value = SafeParse.stringToDouble(entry.protein().toString())
        binding.fatInput.value = SafeParse.stringToDouble(entry.fat().toString())
        binding.warsawDurationInput.value = entry.warsawDurationHours()

        binding.useCob.setOnCheckedChangeListener { _, _ -> processCob() }

        binding.useTrendCheckbox.setOnCheckedChangeListener { _, _ -> processTrend() }

        processCob()
        processIOB()

        // Meal type: saved with the button; cake/pizza replace the button's own fixed eCarbs (see QuickWizardEntry.useEcarbs).
        carbsType = savedInstanceState?.getString("carbs_type")?.let { CarbsType.fromName(it) } ?: entry.carbsType()
        binding.carbsTypeGroup.check(
            when (carbsType) {
                CarbsType.BREAD -> binding.carbsTypeBread.id
                CarbsType.CAKE  -> binding.carbsTypeCake.id
                CarbsType.PIZZA -> binding.carbsTypePizza.id
            }
        )
        binding.carbsTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            carbsType = when (checkedId) {
                binding.carbsTypeCake.id  -> CarbsType.CAKE
                binding.carbsTypePizza.id -> CarbsType.PIZZA
                else                      -> CarbsType.BREAD
            }
            updateCarbsTypeInfo()
        }
        binding.autoFpuCheckbox.setOnCheckedChangeListener { _, _ -> updateCarbsTypeInfo() }
        // Simple screen first, every time (2026-11-05, per explicit request): name, carbs, meal type, Walking soon, Split FPU,
        // Auto fat/protein. The hidden settings keep the button's saved values.
        binding.detailedSettingsCheckbox.isChecked = savedInstanceState?.getBoolean("detailed_settings", false) ?: false
        binding.detailedSettingsCheckbox.setOnCheckedChangeListener { _, _ -> applySimpleMode() }
        applySimpleMode()
    }

    private fun applySimpleMode() {
        val visibility = if (binding.detailedSettingsCheckbox.isChecked) View.VISIBLE else View.GONE
        listOf(
            binding.timeRow, binding.retryFpuLaterCheckbox, binding.correctionRow, binding.carbTimeRow, binding.useForCalcLabel,
            binding.useForCalcBlock, binding.ecarbsTable
        ).forEach { it.visibility = visibility }
        updateCarbsTypeInfo()
    }

    private fun updateCarbsTypeInfo() {
        val binding = _binding ?: return
        val carbs = binding.carbsInput.value.toInt()
        val split = binding.splitFpuBolusCheckbox.isChecked
        binding.carbsTypeInfo.text = when (carbsType) {
            CarbsType.BREAD -> rh.gs(R.string.wizard_carbs_type_bread_desc)
            else            -> rh.gs(if (carbsType == CarbsType.CAKE) R.string.wizard_carbs_type_cake_desc else R.string.wizard_carbs_type_pizza_desc) + " " +
                when {
                    !split                            -> rh.gs(
                        R.string.wizard_carbs_type_split_ecarbs, carbsType.carbsNow(carbs), carbsType.eCarbs(carbs), carbsType.eCarbsDelayMinutes,
                        carbsType.eCarbsDurationHours
                    )
                    binding.autoFpuCheckbox.isChecked -> rh.gs(R.string.wizard_carbs_type_split_fpu, carbsType.carbsNow(carbs))
                    else                              -> rh.gs(R.string.wizard_carbs_type_split_fpu_no_auto, carbsType.carbsNow(carbs))
                }
        }
    }

    private fun processSplitBolus() {
        val visibility = if (binding.splitBolusCheckbox.isChecked) View.VISIBLE else View.GONE
        binding.splitBolusIntervalInput.visibility = visibility
        binding.splitBolusIntervalUnit.visibility = visibility
    }

    override fun onClick(v: View?) {
        //
    }

    override fun onResume() {
        super.onResume()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("position", position)
        _binding?.let {
            outState.putString("carbs_type", carbsType.name)
            outState.putBoolean("detailed_settings", it.detailedSettingsCheckbox.isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun processCob() {
        if (binding.useCob.isChecked) {
            binding.useIob.isChecked = true
        }
    }

    private fun processIOB() {
        if (!binding.useIob.isChecked) {
            binding.useCob.isChecked = false
            binding.usePositiveIobOnly.isChecked = false
            binding.usePositiveIobOnly.visibility = View.GONE
        } else {
            binding.usePositiveIobOnly.visibility = View.VISIBLE
        }
    }

    private fun processTrend() {
        if (binding.useTrendCheckbox.isChecked) {
            binding.useTrend.setSelection(0)
            binding.useTrend.visibility = View.VISIBLE
            binding.useTrendSubline.visibility = View.VISIBLE
        } else {
            binding.useTrend.visibility = View.GONE
            binding.useTrendSubline.visibility = View.GONE
        }
    }

    private fun validateInputs() {
        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        val time = binding.time.value.toInt()
        if (time > 12 * 60 || time < -7 * 24 * 60) {
            binding.time.value = 0.0
            ToastUtils.warnToast(ctx, app.aaps.core.ui.R.string.constraint_applied)
        }
        if (binding.duration.value > 10) {
            binding.duration.value = 0.0
            ToastUtils.warnToast(ctx, app.aaps.core.ui.R.string.constraint_applied)
        }
        if (binding.carbs2.value.toInt() > maxCarbs) {
            binding.carbs2.value = 0.0
            ToastUtils.warnToast(ctx, R.string.carbs_constraint_applied)
        }

        //validate input for normal carbs
        if (binding.carbsInput.value.toInt() > maxCarbs) {
            binding.carbsInput.value = 0.0
            ToastUtils.warnToast(ctx, R.string.carbs_constraint_applied)
        }

        //validate input for percentage
        if (binding.correctionInput.value.toInt() > MAX_PERCENTAGE) {
            binding.carbsInput.value = MAX_PERCENTAGE
            ToastUtils.warnToast(ctx, R.string.overview_edit_quickwizard_percentage_too_high)
        }

    }

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let {
                validateInputs()
                updateCarbsTypeInfo()
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    private val timeTextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let { binding ->
                binding.alarm.isChecked = binding.carbTimeInput.value > 0
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

        }
    }

    //Radio Group to CheckBox transition - backward JSON compatibility
    private fun checkBoxToRadioNumbers(bool: Boolean): Int {
        return if (bool) 0 else 1
    }

    //Radio Group to CheckBox transition - backward JSON compatibility
    private fun radioNumbersToCheckBox(theInt: Int): Boolean {
        return theInt == 0
    }

    private fun useTrendToInt(): Int {
        if (binding.useTrendCheckbox.isChecked) {
            when (binding.useTrend.selectedItemPosition) {
                0 -> return QuickWizardEntry.YES
                1 -> return QuickWizardEntry.POSITIVE_ONLY
                2 -> return QuickWizardEntry.NEGATIVE_ONLY
            }
        } else {
            return QuickWizardEntry.NO
        }
        return QuickWizardEntry.NO
    }

    private fun processIntToTrendSelection(theInt: Int) {
        when (theInt) {
            QuickWizardEntry.YES           -> {
                binding.useTrendCheckbox.isChecked = true
                processTrend()
            }

            QuickWizardEntry.NO            -> {
                binding.useTrendCheckbox.isChecked = false
                processTrend()
            }

            QuickWizardEntry.POSITIVE_ONLY -> {
                binding.useTrendCheckbox.isChecked = true
                processTrend()
                binding.useTrend.setSelection(1)
            }

            QuickWizardEntry.NEGATIVE_ONLY -> {
                binding.useTrendCheckbox.isChecked = true
                processTrend()
                binding.useTrend.setSelection(2)
            }
        }
    }

}
