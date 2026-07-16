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
}
