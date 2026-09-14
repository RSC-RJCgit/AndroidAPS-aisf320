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
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
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
            binding.roleStateSummary.text = roleStateSummaryText()
            binding.roleAssignSpinner.adapter =
                ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, roleSelectorLabels())
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

    // Role selector. durationCode 51–57 is the fast ProfileSwitch path (setRoleKeysInOrder on the
    // loop phone). StandardTierA and SteroidTier A–F have no 51–57 slot — they travel the SetRole
    // Note only. Spinner order matches List1; durationCode is on the option, not the index.
    private data class RoleOption(val label: String, val key: StringKey?, val durationCode: Int? = null)
    private val roleOptions = listOf(
        RoleOption("(no role change — use dropdown to assign a role)", null),
        RoleOption("StandardCurrent", StringKey.ApsAutoIsfStandardProfileName, 51),
        RoleOption("LowCurrent", StringKey.ApsAutoIsfLowProfileName, 54),
        RoleOption("StandardTierA (Note)", StringKey.ApsAutoIsfStandard100ProfileName),
        RoleOption("StandardTierB", StringKey.ApsAutoIsfStandard105ProfileName, 52),
        RoleOption("StandardTierC", StringKey.ApsAutoIsfStandard110ProfileName, 53),
        RoleOption("LowTierA", StringKey.ApsAutoIsfLow70ProfileName, 55),
        RoleOption("LowTierB", StringKey.ApsAutoIsfLow80ProfileName, 56),
        RoleOption("LowTierC", StringKey.ApsAutoIsfLow90ProfileName, 57),
        RoleOption("SteroidTierA (Note)", StringKey.ApsAutoIsfSteroid100ProfileName),
        RoleOption("SteroidTierB (Note)", StringKey.ApsAutoIsfSteroid110ProfileName),
        RoleOption("SteroidTierC (Note)", StringKey.ApsAutoIsfSteroid130ProfileName),
        RoleOption("SteroidTierD (Note)", StringKey.ApsAutoIsfSteroid150ProfileName),
        RoleOption("SteroidTierE (Note)", StringKey.ApsAutoIsfSteroid190ProfileName),
        RoleOption("SteroidTierF (Note)", StringKey.ApsAutoIsfSteroid250ProfileName)
    )

    private val standardTierKeys = setOf(
        StringKey.ApsAutoIsfStandard100ProfileName,
        StringKey.ApsAutoIsfStandard105ProfileName,
        StringKey.ApsAutoIsfStandard110ProfileName
    )
    private val lowTierKeys = setOf(
        StringKey.ApsAutoIsfLow70ProfileName,
        StringKey.ApsAutoIsfLow80ProfileName,
        StringKey.ApsAutoIsfLow90ProfileName
    )
    private val steroidTierKeys = setOf(
        StringKey.ApsAutoIsfSteroid100ProfileName,
        StringKey.ApsAutoIsfSteroid110ProfileName,
        StringKey.ApsAutoIsfSteroid130ProfileName,
        StringKey.ApsAutoIsfSteroid150ProfileName,
        StringKey.ApsAutoIsfSteroid190ProfileName,
        StringKey.ApsAutoIsfSteroid250ProfileName
    )

    // Same A/B/C order as standardTierKeys/lowTierKeys above, kept as ordered lists (rather than
    // indexing into the Sets) so the rung math below reads the same as its OpenAPSAutoISFPlugin.kt
    // counterpart (standardRoleLadder/lowRoleLadder).
    private val standardRoleLadder = listOf(
        StringKey.ApsAutoIsfStandard100ProfileName,
        StringKey.ApsAutoIsfStandard105ProfileName,
        StringKey.ApsAutoIsfStandard110ProfileName
    )
    private val lowRoleLadder = listOf(
        StringKey.ApsAutoIsfLow70ProfileName,
        StringKey.ApsAutoIsfLow80ProfileName,
        StringKey.ApsAutoIsfLow90ProfileName
    )

    // Ported from OpenAPSAutoISFPlugin.kt's resolveTieredProfileName/ladderIndexOf/lockstepPartnerCurrent
    // (2026-09-14, aisf321UK_847 drift fix). Root cause: submit() below used to write only the single
    // role key picked in the spinner (e.g. LowCurrent) straight to prefs on the loop phone, with no
    // partner update -- unlike applySetRole() on the relay-receiving side, which always keeps
    // StandardCurrent/LowCurrent locked to the same A/B/C letter. That let this dialog silently drift
    // Standard and Low apart (e.g. LowCurrent reassigned down to TierA while StandardCurrent stayed
    // parked at TierC from earlier), and BasalUp's switchToStandardAtSharedTier() -- which trusts
    // whichever role you are CURRENTLY RUNNING as the true rung -- then flipped Standard down to match
    // Low's drifted TierA the next time it fired, discarding the TierC it had legitimately been at.
    private fun resolveTieredProfileName(tierKey: StringKey, baseRoleKey: StringKey): String {
        val tiered = preferences.get(tierKey).trim()
        return tiered.ifEmpty { preferences.get(baseRoleKey) }
    }

    private fun ladderIndexOf(currentProfileName: String, rungsAscending: List<StringKey>): Int {
        rungsAscending.forEachIndexed { index, key ->
            val configured = preferences.get(key).trim()
            if (configured.isNotEmpty() && configured == currentProfileName) return index
        }
        return -1
    }

    private fun lockstepPartnerCurrent(roleKey: StringKey, profileName: String) {
        when (roleKey) {
            StringKey.ApsAutoIsfStandardProfileName -> {
                val idx = ladderIndexOf(profileName, standardRoleLadder)
                if (idx < 0) return
                val low = resolveTieredProfileName(lowRoleLadder[idx], StringKey.ApsAutoIsfLowProfileName)
                if (low.isNotBlank()) preferences.put(StringKey.ApsAutoIsfLowProfileName, low)
            }

            StringKey.ApsAutoIsfLowProfileName      -> {
                val idx = ladderIndexOf(profileName, lowRoleLadder)
                if (idx < 0) return
                val std = resolveTieredProfileName(standardRoleLadder[idx], StringKey.ApsAutoIsfStandard100ProfileName)
                    .ifBlank { preferences.get(StringKey.ApsAutoIsfStandardProfileName) }
                if (std.isNotBlank()) preferences.put(StringKey.ApsAutoIsfStandardProfileName, std)
            }

            else                                    -> Unit
        }
    }

    private fun mirroredRoleMap(): Map<String, String> =
        preferences.get(StringNonKey.MirroredAutoIsfSettings)
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(" = ")
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 3)
            }
            .toMap()

    private fun rolePref(key: StringKey): String =
        if (config.AAPSCLIENT) mirroredRoleMap()[key.key].orEmpty().trim()
        else preferences.get(key).trim()

    private fun roleDisplay(key: StringKey): String = rolePref(key).ifBlank { "(not set)" }

    private fun roleStateSummaryText(): String {
        val src = if (config.AAPSCLIENT) "Live snapshot" else "This phone"
        return buildString {
            appendLine("$src:")
            appendLine("StandardCurrent ${roleDisplay(StringKey.ApsAutoIsfStandardProfileName)}")
            appendLine("LowCurrent ${roleDisplay(StringKey.ApsAutoIsfLowProfileName)}")
            appendLine("Standard A/B/C ${roleDisplay(StringKey.ApsAutoIsfStandard100ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfStandard105ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfStandard110ProfileName)}")
            appendLine("Low A/B/C ${roleDisplay(StringKey.ApsAutoIsfLow70ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfLow80ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfLow90ProfileName)}")
            append("Steroid A–F ${roleDisplay(StringKey.ApsAutoIsfSteroid100ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfSteroid110ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfSteroid130ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfSteroid150ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfSteroid190ProfileName)} / ${roleDisplay(StringKey.ApsAutoIsfSteroid250ProfileName)}")
        }
    }

    // Stars the most specific role that currently holds the running original profile. A matching
    // Standard A/B/C or Low A/B/C wins over its Current so both don't light up when lock-step has
    // pointed the live role at the same name as that rung. Client reads Live's snapshot.
    private fun activeRoleKeys(running: String): Set<StringKey> {
        if (running.isBlank()) return emptySet()
        val matched = mutableSetOf<StringKey>()
        for (key in standardTierKeys + lowTierKeys + steroidTierKeys) {
            val assigned = rolePref(key)
            if (assigned.isNotEmpty() && assigned == running) matched += key
        }
        if (matched.none { it in standardTierKeys }) {
            val std = rolePref(StringKey.ApsAutoIsfStandardProfileName)
            if (std == running) matched += StringKey.ApsAutoIsfStandardProfileName
        }
        if (matched.none { it in lowTierKeys }) {
            val low = rolePref(StringKey.ApsAutoIsfLowProfileName)
            if (low == running) matched += StringKey.ApsAutoIsfLowProfileName
        }
        if (matched.isEmpty()) {
            fun has(n: Int) = Regex("(?<!\\d)$n(?!\\d)").containsMatchIn(running)
            when {
                has(105) -> matched += StringKey.ApsAutoIsfStandard105ProfileName
                has(110) -> matched += StringKey.ApsAutoIsfStandard110ProfileName
                has(70)  -> matched += StringKey.ApsAutoIsfLow70ProfileName
                has(80)  -> matched += StringKey.ApsAutoIsfLow80ProfileName
                has(90)  -> matched += StringKey.ApsAutoIsfLow90ProfileName
                has(100) -> matched += StringKey.ApsAutoIsfStandard100ProfileName
            }
        }
        return matched
    }

    private fun roleSelectorLabels(): List<String> {
        val running = profileFunction.getOriginalProfileName()
        val active = activeRoleKeys(running)
        return roleOptions.map { opt ->
            if (opt.key == null) opt.label
            else buildString {
                append(opt.label)
                val assigned = rolePref(opt.key)
                if (assigned.isNotEmpty()) append(" ($assigned)")
                if (opt.key in active) append(" *")
            }
        }
    }

    // Backup channel to the loop phone: a "SetRole <prefKey>=<profile>" careportal Note, picked up via
    // the secondary-NS allowlist (LoadSecondaryBolusCarbsWorker) and applied by OpenAPSAutoISFPlugin.
    // Slow (~40-70 min) but independent of the fast coded-duration path; both are idempotent.
    //
    // 2026-09-14: skip entirely on a VirtualPump. This dialog already applies the role assignment
    // locally and synchronously (the preferences.put + lockstepPartnerCurrent call right above each
    // call site) -- the note does nothing for the emitting device itself. Its only real purpose is
    // relaying the assignment to whichever device IS the real loop phone (OpenAPSAutoISFPlugin's own
    // isRealLoopPhone() = !AAPSCLIENT && activePump !is VirtualPump gates who may ever APPLY one).
    // Emitting it from Virtual is worse than merely redundant: Virtual and Live typically share one
    // Nightscout project, so a role change made here for local testing would otherwise sit as a
    // careportal Note that Live's own SetRole handler picks up ~40-70 min later and applies for real --
    // a test action on the non-dosing device silently overwriting the actual loop phone's role state.
    // AAPSCLIENT is untouched: Client has no local role prefs at all (see submit()'s own comment), so
    // this note is its ONLY path to affect real dosing and must still be emitted there.
    private fun emitSetRoleNote(roleKey: StringKey, profileName: String) {
        if (activePlugin.activePump is VirtualPump) return
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

        // Role selector captured now -- submit() dismisses this dialog before the confirm callback.
        // Fast 51–57 only when that option has a durationCode. StandardTierA / SteroidTier stay Note-only.
        val roleIndex = binding.roleAssignSpinner.selectedItemPosition
        val roleOption = roleOptions.getOrElse(roleIndex) { roleOptions[0] }
        val roleDurationCode = if (roleOption.durationCode != null && typedDuration == 0 && percent == 100) roleOption.durationCode else null
        val duration = roleDurationCode
            ?: if (typedDuration in 51..57 && (roleOption.key == null || roleOption.durationCode == null)) 60
            else typedDuration

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
                            emitSetRoleNote(steroidKey, profileName)
                        } ?: roleOption.key?.let { roleKey ->
                            if (!config.AAPSCLIENT) {
                                preferences.put(roleKey, profileName)
                                // 2026-09-14 drift fix: keep the partner Current locked to the same
                                // A/B/C letter, same as applySetRole() does on the relay-receiving side.
                                lockstepPartnerCurrent(roleKey, profileName)
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
