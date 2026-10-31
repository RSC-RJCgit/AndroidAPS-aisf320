package app.aaps.plugins.constraints.objectives

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.constraints.Objectives.Companion.AUTOSENS_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.AUTO_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.CLOSED_LOOP_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.EXAM_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.FIRST_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.LGS_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.SMB_OBJECTIVE
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.di.APS
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginObjectives
import app.aaps.plugins.constraints.ConstraintsStrings
import app.aaps.plugins.constraints.objectives.compose.ObjectivesComposeContent
import app.aaps.plugins.constraints.objectives.keys.ObjectivesBooleanComposedKey
import app.aaps.plugins.constraints.objectives.keys.ObjectivesLongComposedKey
import app.aaps.plugins.constraints.objectives.objectives.Objective
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.jvm.JvmSuppressWildcards

@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@APS
@IntKey(840)
@SingleIn(AppScope::class)
@Inject
class ObjectivesPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    preferences: Preferences,
    private val config: Config,
    val objectives: List<@JvmSuppressWildcards Objective>,
    notificationManager: NotificationManager,
    private val activePlugin: ActivePlugin
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .composeContent { ObjectivesComposeContent() }
        .icon(IcPluginObjectives)
        .pluginName(CoreUiStrings.objectives)
        // Objectives must be present on a master and must not be switchable off. Declared even though a
        // CONSTRAINTS plugin is enabled by registration anyway (see PluginBase.enforcedState): stating the
        // rule keeps it true if the plugin's mainType ever changes, and it is what makes canToggle false so
        // the Config Builder shows a locked switch rather than a live one that does nothing.
        .enforce(EnforcedState.Enabled) { config.APS }
        .description(ConstraintsStrings.description_objectives),
    ownPreferences = ObjectivesBooleanComposedKey.entries + ObjectivesLongComposedKey.entries,
    aapsLogger, rh, preferences, notificationManager
), PluginConstraints, Objectives {

    /** The install/update notice is on screen, so it can be taken away once objectives no longer limit anything. */
    private var limitNoticePosted = false

    /**
     * Virtual master bypass (2026-10-31, per explicit request): with the switch on, a master on VirtualPump is not
     * limited by objectives at all. Asked about the pump every time, so moving that phone to a real pump makes
     * objectives apply again at once, even with the switch left on.
     */
    override val isBypassed: Boolean
        get() = preferences.get(BooleanNonKey.ObjectivesVirtualPumpBypass) && isVirtualPumpActive()

    /** The switch is only offered where it would do something. */
    fun isVirtualPumpActive(): Boolean = activePlugin.activePump.selectedActivePump() is VirtualPump

    override suspend fun onStart() {
        super.onStart()
        checkAfterInstallOrUpdate()
    }

    /**
     * Once per install or update (2026-10-31, per explicit request): say which functions objectives limit, if any.
     * A fresh install starts with no objective started, and nothing else on screen said that the loop, SMB and
     * automation were off because of it. The commit is part of the version, because a CI build keeps the version
     * name.
     */
    internal fun checkAfterInstallOrUpdate() {
        val version = "${config.VERSION_NAME} ${config.HEAD}"
        if (preferences.get(StringNonKey.ObjectivesLastCheckedVersion) == version) return
        preferences.put(StringNonKey.ObjectivesLastCheckedVersion, version)
        if (!config.APS || isBypassed) return
        val limited = limitedFunctions()
        if (limited.isEmpty()) return
        notificationManager.post(NotificationId.OBJECTIVES_LIMIT_FUNCTIONS, rh.gs(ConstraintsStrings.objectives_limit_after_update, limited.joinToString("; ")))
        limitNoticePosted = true
    }

    /** What objectives limit right now, each with its reason, in the order of the constraints below. */
    fun limitedFunctions(): List<String> {
        if (objectives.isEmpty() || isBypassed) return emptyList()
        return buildList {
            if (!objectives[FIRST_OBJECTIVE].isStarted)
                add(limited(ConstraintsStrings.objectives_fn_loop, rh.gs(ConstraintsStrings.objectivenotstarted, FIRST_OBJECTIVE + 1)))
            if (objectives[LGS_OBJECTIVE].isStarted && !objectives[LGS_OBJECTIVE].isAccomplished)
                add(limited(ConstraintsStrings.objectives_fn_lgs, rh.gs(ConstraintsStrings.objectivenotfinished, LGS_OBJECTIVE + 1)))
            if (!objectives[CLOSED_LOOP_OBJECTIVE].isStarted)
                add(limited(ConstraintsStrings.objectives_fn_closed_loop, rh.gs(ConstraintsStrings.objectivenotstarted, CLOSED_LOOP_OBJECTIVE + 1)))
            if (!objectives[AUTOSENS_OBJECTIVE].isStarted)
                add(limited(ConstraintsStrings.objectives_fn_autosens, rh.gs(ConstraintsStrings.objectivenotstarted, AUTOSENS_OBJECTIVE + 1)))
            if (!objectives[SMB_OBJECTIVE].isStarted)
                add(limited(ConstraintsStrings.objectives_fn_smb, rh.gs(ConstraintsStrings.objectivenotstarted, SMB_OBJECTIVE + 1)))
            if (!objectives[AUTO_OBJECTIVE].isStarted)
                add(limited(ConstraintsStrings.objectives_fn_automation, rh.gs(ConstraintsStrings.objectivenotstarted, AUTO_OBJECTIVE + 1)))
            if (!objectives[EXAM_OBJECTIVE].isAccomplished)
                add(limited(ConstraintsStrings.objectives_fn_concentration, rh.gs(ConstraintsStrings.objectivenotfinished, EXAM_OBJECTIVE + 1)))
        }
    }

    private fun limited(function: TextRef, reason: String): String = rh.gs(ConstraintsStrings.objectives_limited_item, rh.gs(function), reason)

    /** Takes the install/update notice away once objectives no longer limit anything. Called from every loop. */
    private fun dismissLimitNoticeIfCleared() {
        if (limitNoticePosted && limitedFunctions().isEmpty()) {
            notificationManager.dismiss(NotificationId.OBJECTIVES_LIMIT_FUNCTIONS)
            limitNoticePosted = false
        }
    }

    fun reset() {
        for (objective in objectives) {
            objective.startedOn = 0
            objective.accomplishedOn = 0
        }
        preferences.put(BooleanNonKey.ObjectivesBgIsAvailableInNs, false)
        preferences.put(BooleanNonKey.ObjectivesPumpStatusIsAvailableInNS, false)
        preferences.put(IntNonKey.ObjectivesManualEnacts, 0)
        preferences.put(BooleanNonKey.ObjectivesProfileSwitchUsed, false)
        preferences.put(BooleanNonKey.ObjectivesDisconnectUsed, false)
        preferences.put(BooleanNonKey.ObjectivesReconnectUsed, false)
        preferences.put(BooleanNonKey.ObjectivesTempTargetUsed, false)
        preferences.put(BooleanNonKey.ObjectivesLoopUsed, false)
        preferences.put(BooleanNonKey.ObjectivesScaleUsed, false)
    }

    fun allPriorAccomplished(position: Int): Boolean {
        var accomplished = true
        for (i in 0 until position) {
            accomplished = accomplished && objectives[i].isAccomplished
        }
        return accomplished
    }

    /**
     * Constraints interface
     */
    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        dismissLimitNoticeIfCleared()
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[FIRST_OBJECTIVE].isStarted)
            value.set(false, rh.gs(ConstraintsStrings.objectivenotstarted, FIRST_OBJECTIVE + 1), this)
        return value
    }

    override fun isLgsForced(value: Constraint<Boolean>): Constraint<Boolean> {
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (objectives[LGS_OBJECTIVE].isStarted && !objectives[LGS_OBJECTIVE].isAccomplished)
            value.set(true, rh.gs(ConstraintsStrings.objectivenotfinished, LGS_OBJECTIVE + 1), this)
        return value
    }

    override suspend fun isClosedLoopAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[CLOSED_LOOP_OBJECTIVE].isStarted)
            value.set(false, rh.gs(ConstraintsStrings.objectivenotstarted, CLOSED_LOOP_OBJECTIVE + 1), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[AUTOSENS_OBJECTIVE].isStarted)
            value.set(false, rh.gs(ConstraintsStrings.objectivenotstarted, AUTOSENS_OBJECTIVE + 1), this)
        return value
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[SMB_OBJECTIVE].isStarted)
            value.set(false, rh.gs(ConstraintsStrings.objectivenotstarted, SMB_OBJECTIVE + 1), this)
        return value
    }

    override fun isAutomationEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        // Check if initialized
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[AUTO_OBJECTIVE].isStarted)
            value.set(false, rh.gs(ConstraintsStrings.objectivenotstarted, AUTO_OBJECTIVE + 1), this)
        return value
    }

    override fun isConcentrationEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (objectives.isEmpty() || isBypassed) return value
        if (!objectives[EXAM_OBJECTIVE].isAccomplished) {
            value.set(false, rh.gs(ConstraintsStrings.objectivenotfinished, EXAM_OBJECTIVE + 1), this)
        }
        return value
    }

    override val size: Int get() = objectives.size
    override val accomplishedCount: Int get() = objectives.count { it.isAccomplished }

    override fun isAccomplished(index: Int) = objectives[index].isAccomplished
    override fun isStarted(index: Int): Boolean = objectives[index].isStarted
}
