package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class BooleanNonKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    SetupWizardIUnderstand("I_understand", false),
    /** Profile store owner (2026-11-17): the one-time "Client" defaults of [app.aaps.plugins.sync.nsShared.ProfileStoreOwner] ran on this client. */
    NsClientProfileClientDefaultsApplied("nsclient_profile_client_defaults_applied", false, exportable = false),
    /** Statistics, Sensor tab (2026-11-04, per explicit request): draw the sensor's raw signal (the default) or BG */
    StatsSensorRaw("stats_sensor_raw", true),
    ObjectivesLoopUsed("ObjectivesLoopUsed", false),
    ObjectivesActionsUsed("ObjectivesActionsUsed", false),
    ObjectivesScaleUsed("ObjectivesScaleUsed", false),
    ObjectivesPumpStatusIsAvailableInNS("ObjectivespumpStatusIsAvailableInNS", false),
    ObjectivesBgIsAvailableInNs("ObjectivesbgIsAvailableInNS", false),
    ObjectivesProfileSwitchUsed("ObjectivesProfileSwitchUsed", false),
    ObjectivesDisconnectUsed("ObjectivesDisconnectUsed", false),
    ObjectivesReconnectUsed("ObjectivesReconnectUsed", false),
    ObjectivesTempTargetUsed("ObjectivesTempTargetUsed", false),
    AutosensUsedOnMainPhone("used_autosens_on_main_phone", false),
    // 2026-11-15, per explicit request: the storage breakdown SMS is sent once after the update that added it, see ApkStorageMaintenance
    StorageBreakdownOnceSent("storage_breakdown_once_sent", false, exportable = false),
}