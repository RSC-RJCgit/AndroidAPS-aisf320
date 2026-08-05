package app.aaps.plugins.main.general.overview.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class OverviewStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    GraphConfig("graphconfig", ""),
    // Non-empty once the coded-profile-name selection popup has been shown/completed (see
    // OverviewFragment.checkOnUpdatePopups()) -- gates it to once per install, not once per launch.
    // The actual choices live in StringKey.ApsAutoIsfStandardProfileName/LowProfileName; this is just
    // the "have we asked" marker.
    ApsAutoIsfProfileNamesReviewed("autoisf_profile_names_reviewed", "")
}