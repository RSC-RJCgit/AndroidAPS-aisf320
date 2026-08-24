package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogProfileswitchBinding
import com.google.common.base.Joiner
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ProfileSwitchDialog : DialogFragmentWithDate() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var config: Config
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck

    private var queryingProtection = false
    private var profileName: String? = null
    private val disposable = CompositeDisposable()
    private var _binding: DialogProfileswitchBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let { binding ->
                val isDuration = binding.duration.value > 0
                val isLowerPercentage = binding.percentage.value < 100
                binding.ttLayout.visibility = (isDuration && isLowerPercentage).toVisibility()
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("duration", binding.duration.value)
        savedInstanceState.putDouble("percentage", binding.percentage.value)
        savedInstanceState.putDouble("timeshift", binding.timeshift.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        arguments?.let { bundle ->
            profileName = bundle.getString("profileName", null)
        }
        _binding = DialogProfileswitchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.duration.setParams(
            savedInstanceState?.getDouble("duration")
                ?: 0.0, 0.0, Constants.MAX_PROFILE_SWITCH_DURATION, 10.0, DecimalFormat("0"), false, binding.okcancel.ok,
            textWatcher
        )
        binding.percentage.setParams(
            savedInstanceState?.getDouble("percentage")
                ?: 100.0, Constants.CPP_MIN_PERCENTAGE.toDouble(), Constants.CPP_MAX_PERCENTAGE.toDouble(), 5.0,
            DecimalFormat("0"), false, binding.okcancel.ok, textWatcher
        )
        binding.timeshift.setParams(
            savedInstanceState?.getDouble("timeshift")
                ?: 0.0, Constants.CPP_MIN_TIMESHIFT.toDouble(), Constants.CPP_MAX_TIMESHIFT.toDouble(), 1.0, DecimalFormat("0"), false, binding.okcancel.ok
        )

        // profile
        context?.let { context ->
            val profileStore = activePlugin.activeProfileSource.profile ?: return
            val profileListToCheck = profileStore.getProfileList()
            val profileList = ArrayList<CharSequence>()
            for (profileName in profileListToCheck) {
                val profileToCheck = activePlugin.activeProfileSource.profile?.getSpecificProfile(profileName.toString())
                if (profileToCheck != null && ProfileSealed.Pure(profileToCheck, activePlugin).isValid("ProfileSwitch", activePlugin.activePump, config, rh, rxBus, hardLimits, false).isValid)
                    profileList.add(profileName)
            }
            if (profileList.isEmpty()) {
                dismiss()
                return
            }
            binding.profileList.setAdapter(ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, profileList))
            // set selected to actual profile
            if (profileName != null)
                binding.profileList.setText(profileName, false)
            else {
                binding.profileList.setText(profileList[0], false)
                for (p in profileList.indices)
                    if (profileList[p] == profileFunction.getOriginalProfileName())
                        binding.profileList.setText(profileList[p], false)
            }
        }

        profileFunction.getProfile()?.let { profile ->
            if (profile is ProfileSealed.EPS)
                if (profile.value.originalPercentage != 100 || profile.value.originalTimeshift != 0L) {
                    binding.reuselayout.visibility = View.VISIBLE
                    binding.reusebutton.text = rh.gs(R.string.reuse_profile_pct_hours, profile.value.originalPercentage, T.msecs(profile.value.originalTimeshift).hours().toInt())
                    binding.reusebutton.setOnClickListener {
                        binding.percentage.value = profile.value.originalPercentage.toDouble()
                        binding.timeshift.value = T.msecs(profile.value.originalTimeshift).hours().toDouble()
                    }
                }
        }
        binding.ttLayout.visibility = View.GONE
        binding.durationLabel.labelFor = binding.duration.editTextId
        binding.percentageLabel.labelFor = binding.percentage.editTextId
        binding.timeshiftLabel.labelFor = binding.timeshift.editTextId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }

    // Added 2026-08-24. Longest-first order matters: "150" must be checked before "50" would ever be
    // (there's no "50" tier here, but the principle holds generally) and, more concretely, guards against
    // a shorter number being a substring of a longer one that also appears in these names ("190" vs "90",
    // "250" vs "50" -- neither collides today, but checking specific-to-general is the safe habit).
    //
    // Fixed 2026-08-24 (first pass): was a bare String.contains(), which also fired on the percent
    // appearing as part of a LONGER digit run unrelated to a steroid tier. Tightened to require the
    // percent as its own standalone digit run.
    //
    // Fixed 2026-08-24 (second pass): standalone-digit matching still isn't enough -- a real, ordinary,
    // non-steroid profile can legitimately be named with a standalone number that coincides with a tier
    // ("Current Profile110" was reported misrouted to Steroid110 purely because "110" appeared standalone
    // in an otherwise unrelated numbering series alongside this user's actual Standard/Low profiles,
    // "Current Profile100"/"Current Profile70"). A standalone number alone can never disambiguate "this is
    // a steroid-tier profile" from "this user just numbers profiles that way". Now ALSO requires an
    // unambiguous marker in the name -- "steroid" (case-insensitive) or a literal "%" -- so a switch is
    // only ever auto-classified as a steroid pick when the name itself says so. This means the profile
    // assigned to a StringKey.ApsAutoIsfSteroidNNNProfileName role must actually contain one of those
    // markers for this dialog's auto-detection to recognize it on a future switch; a profile lacking the
    // marker (even if it's the number-only default for Steroid190/250 below) simply falls through to the
    // Standard/Low checkbox instead -- assign it explicitly via "Re-pick coded profiles" (List 1) or the
    // Steroid escalation buttons if you want it recognized here too.
    private fun steroidRoleKeyForProfileName(name: String): StringKey? {
        val hasMarker = name.contains("steroid", ignoreCase = true) || name.contains("%")
        if (!hasMarker) return null
        fun hasStandaloneNumber(n: Int) = Regex("(?<!\\d)${n}(?!\\d)").containsMatchIn(name)
        return when {
            hasStandaloneNumber(250) -> StringKey.ApsAutoIsfSteroid250ProfileName
            hasStandaloneNumber(190) -> StringKey.ApsAutoIsfSteroid190ProfileName
            hasStandaloneNumber(150) -> StringKey.ApsAutoIsfSteroid150ProfileName
            hasStandaloneNumber(130) -> StringKey.ApsAutoIsfSteroid130ProfileName
            hasStandaloneNumber(110) -> StringKey.ApsAutoIsfSteroid110ProfileName
            else -> null
        }
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val profileStore = activePlugin.activeProfileSource.profile
            ?: return false

        val actions: LinkedList<String> = LinkedList()
        val duration = binding.duration.value.toInt()
        if (duration > 0L)
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, duration))
        val profileName = binding.profileList.text.toString()
        actions.add(rh.gs(app.aaps.core.ui.R.string.profile) + ": " + profileName)
        val percent = binding.percentage.value.toInt()
        if (percent != 100)
            actions.add(rh.gs(app.aaps.core.ui.R.string.percent) + ": " + percent + "%")
        val timeShift = binding.timeshift.value.toInt()
        if (timeShift != 0)
            actions.add(rh.gs(R.string.timeshift_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_hours, timeShift.toDouble()))
        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
        if (eventTimeChanged)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))

        val isTT = binding.duration.value > 0 && binding.percentage.value < 100 && binding.tt.isChecked
        // submit() opens a second confirmation dialog and then returns true, which dismisses this
        // ProfileSwitchDialog and clears its view binding before the confirmation callback runs.
        // Capture the checkbox now; reading binding.setAsLowRole inside that callback would access a
        // destroyed view and prevent either the Standard or Low preference from being updated.
        val setAsLowRole = binding.setAsLowRole.isChecked
        val target = preferences.get(UnitDoubleKey.OverviewActivityTarget)
        val units = profileFunction.getUnits()
        if (isTT)
            actions.add(rh.gs(app.aaps.core.ui.R.string.temporary_target) + ": " + rh.gs(app.aaps.core.ui.R.string.activity))

        activity?.let { activity ->
            val ps = profileFunction.buildProfileSwitch(profileStore, profileName, duration, percent, timeShift, eventTime) ?: return@let
            val validity = ProfileSealed.PS(ps, activePlugin).isValid(rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), activePlugin.activePump, config, rh, rxBus, hardLimits, false)
            if (validity.isValid)
                OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    if (profileFunction.createProfileSwitch(
                            profileStore = profileStore,
                            profileName = profileName,
                            durationInMinutes = duration,
                            percentage = percent,
                            timeShiftInHours = timeShift,
                            timestamp = eventTime,
                            action = Action.PROFILE_SWITCH,
                            source = Sources.ProfileSwitchDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                ValueWithUnit.SimpleString(profileName),
                                ValueWithUnit.Percent(percent),
                                ValueWithUnit.Hour(timeShift).takeIf { timeShift != 0 },
                                ValueWithUnit.Minute(duration).takeIf { duration != 0 }
                            ).filterNotNull()
                        )
                    ) {
                        if (percent == 90 && duration == 10) preferences.put(BooleanNonKey.ObjectivesProfileSwitchUsed, true)
                        // Added 2026-08-24: every manual switch through this dialog re-assigns a coded
                        // role to whatever was just picked -- the picked profile is never left un-
                        // attached to any role. If the picked NAME itself carries one of the six Steroid
                        // tier percentages (110/130/150/190/250 -- checked longest string first so a
                        // coincidental short match can't shadow a real longer one), that specific Steroid
                        // role is re-assigned and the checkbox is moot for this switch. Otherwise:
                        // unticked (default) assigns Standard, ticked assigns Low -- exactly one of the
                        // two, always, never neither. The checkbox value was captured before opening this
                        // confirmation dialog because the originating dialog's binding is cleared while
                        // this callback is waiting for the user.
                        steroidRoleKeyForProfileName(profileName)?.let { steroidKey ->
                            preferences.put(steroidKey, profileName)
                        } ?: run {
                            if (setAsLowRole)
                                preferences.put(StringKey.ApsAutoIsfLowProfileName, profileName)
                            else
                                preferences.put(StringKey.ApsAutoIsfStandardProfileName, profileName)
                        }
                        if (isTT) {
                            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                                TT(
                                    timestamp = eventTime + 10000, // Add ten secs for proper NSCv1 sync
                                    duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                                    reason = TT.Reason.ACTIVITY,
                                    lowTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits()),
                                    highTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits())
                                ),
                                action = Action.TT,
                                source = Sources.TTDialog,
                                note = null,
                                listValues = listOf(
                                    ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                    ValueWithUnit.TETTReason(TT.Reason.ACTIVITY),
                                    ValueWithUnit.fromGlucoseUnit(target, units),
                                    ValueWithUnit.Minute(duration)
                                ).filterNotNull()
                            ).subscribe()
                        }
                    }
                })
            else {
                OKDialog.show(
                    activity,
                    rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch),
                    HtmlHelper.fromHtml(Joiner.on("<br/>").join(validity.reasons))
                )
                return false
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}
