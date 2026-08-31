package app.aaps.plugins.automation.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class AutomationStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    AutomationEvents("AUTOMATION_EVENTS", ""),
    CarbsAgoMigrationDone("automation_carbs_ago_migration_done", ""),
    AcceUpGuardsMigrationDone("automation_acceup_guards_migration_done", ""),
    // Persisted inside/outside and cooldown state for the ten coded location slots. This prevents an
    // ordinary AAPS restart while already inside a location from looking like a new installation.
    CodedLocationStates("automation_coded_location_states", ""),
    // JSON map {eventTitle: accepted(Boolean)} of user decisions on CLOSE-match native automations (see
    // CodedAutomationNames.kt) -- only entries the user has actually reviewed via the on-update popup are
    // present; anything absent is treated as "not yet decided" (still suppressed, still pending review).
    CodedAutomationDecisions("automation_coded_decisions", ""),
}
