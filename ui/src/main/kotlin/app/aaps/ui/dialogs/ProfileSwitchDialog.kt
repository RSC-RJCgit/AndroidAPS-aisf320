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
import app.aaps.core.data.model.TE
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

        context?.let { context ->
            binding.roleAssignSpinner.adapter =
                ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, roleOptions.map { it.label })
            binding.roleAssignSpinner.setSelection(0)
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

    // Role selector (replaces the old binary "set as Low" checkbox, 2026-08-31). Index 0 = no change;
    // indexes 1..7 map 1:1 to setRoleKeysInOrder AND to coded ProfileSwitch durations 51..57 min (see
    // the two receiver blocks in OpenAPSAutoISFPlugin.invoke()). Keep this order in lockstep with that
    // list -- the duration code is (index + 50).
    private data class RoleOption(val label: String, val key: StringKey?)
    private val roleOptions = listOf(
        RoleOption("(no role change)", null),
        RoleOption("Standard", StringKey.ApsAutoIsfStandardProfileName),
        RoleOption("Standard 105 tier", StringKey.ApsAutoIsfStandard105ProfileName),
        RoleOption("Standard 110 tier", StringKey.ApsAutoIsfStandard110ProfileName),
        RoleOption("Low", StringKey.ApsAutoIsfLowProfileName),
        RoleOption("Low 70 tier", StringKey.ApsAutoIsfLow70ProfileName),
        RoleOption("Low 80 tier", StringKey.ApsAutoIsfLow80ProfileName),
        RoleOption("Low 90 tier", StringKey.ApsAutoIsfLow90ProfileName)
    )

    // Backup channel to the loop phone: a "SetRole <prefKey>=<profile>" careportal Note, picked up via
    // the secondary-NS allowlist (LoadSecondaryBolusCarbsWorker) and applied by OpenAPSAutoISFPlugin.
    // Slow (~40-70 min) but independent of the fast coded-duration path; both are idempotent.
    private fun emitSetRoleNote(roleKey: StringKey, profileName: String) {
        val te = TE(
            timestamp = dateUtil.now(),
            type = TE.Type.NOTE,
            glucoseUnit = profileFunction.getUnits()
        ).apply {
            note = "SetRole ${roleKey.key}=$profileName"
        }
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = te,
            action = Action.CAREPORTAL,
            source = Sources.ProfileSwitchDialog,
            note = null,
            listValues = listOf(ValueWithUnit.SimpleString("SetRole ${roleKey.key}"))
        ).subscribe({}, { e -> aapsLogger.error(LTag.APS, "SetRole note insert failed", e) })
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val profileStore = activePlugin.activeProfileSource.profile
            ?: return false

        val actions: LinkedList<String> = LinkedList()
        val profileName = binding.profileList.text.toString()
        val percent = binding.percentage.value.toInt()
        val timeShift = binding.timeshift.value.toInt()
        val typedDuration = binding.duration.value.toInt()

        // Role selector: index 0 = no change; 1..7 -> roleOptions / setRoleKeysInOrder. Captured now --
        // submit() dismisses this dialog (clearing its binding) before the confirmation callback runs.
        val roleIndex = binding.roleAssignSpinner.selectedItemPosition
        val roleOption = roleOptions.getOrElse(roleIndex) { roleOptions[0] }
        // Fast relay to the loop phone: when a Standard/Low base or tier is chosen AND the user left an
        // indefinite, 100% switch, carry the assignment on the ProfileSwitch itself as a coded duration
        // (51..57 min). OpenAPSAutoISFPlugin.invoke() there applies it and immediately re-issues the
        // switch as indefinite. If the user set their own duration or percent<100, that field is theirs
        // -- only the slower SetRole Note carries the role then. Other direction: a stray 51..57 with no
        // role selected is bumped to 60 so the receiver can't misread it as a code.
        val roleDurationCode = if (roleOption.key != null && typedDuration == 0 && percent == 100) 50 + roleIndex else null
        val duration = roleDurationCode ?: if (roleOption.key == null && typedDuration in 51..57) 60 else typedDuration

        if (duration > 0L)
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, duration))
        actions.add(rh.gs(app.aaps.core.ui.R.string.profile) + ": " + profileName)
        if (percent != 100)
            actions.add(rh.gs(app.aaps.core.ui.R.string.percent) + ": " + percent + "%")
        if (timeShift != 0)
            actions.add(rh.gs(R.string.timeshift_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_hours, timeShift.toDouble()))
        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
        if (eventTimeChanged)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))
        if (roleOption.key != null)
            actions.add("Assign role: ${roleOption.label}" + (roleDurationCode?.let { " (coded ${it}m relay)" } ?: " (Note relay)"))

        val isTT = binding.duration.value > 0 && binding.percentage.value < 100 && binding.tt.isChecked
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
                        // Coded-role assignment (2026-08-24, reworked 2026-08-31 checkbox -> selector).
                        // A Steroid-marked name always re-assigns that Steroid role and the selector is
                        // moot. Otherwise the selector decides. Local preferences.put is Live/Virtual
                        // only (2026-09-02): Client must not keep a shadow copy of role prefs — it
                        // confused Re-pick into showing Client-local Profile70 while Live still had
                        // Profile90. Client still emits the SetRole Note and the coded 51-57 min
                        // duration (fast follower->loop path).
                        steroidRoleKeyForProfileName(profileName)?.let { steroidKey ->
                            if (!config.AAPSCLIENT) preferences.put(steroidKey, profileName)
                        } ?: roleOption.key?.let { roleKey ->
                            if (!config.AAPSCLIENT) {
                                preferences.put(roleKey, profileName)
                                if (roleKey == StringKey.ApsAutoIsfStandardProfileName)
                                    preferences.put(StringKey.ApsAutoIsfStandard100ProfileName, profileName)
                            }
                            emitSetRoleNote(roleKey, profileName)
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
