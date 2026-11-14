package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class BooleanNonKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    GeneralSetupWizardProcessed("startupwizard_processed", false),
    SetupWizardIUnderstand("I_understand", false),
    ObjectivesLoopUsed("ObjectivesLoopUsed", false),
    ObjectivesScaleUsed("ObjectivesScaleUsed", false),
    ObjectivesPumpStatusIsAvailableInNS("ObjectivespumpStatusIsAvailableInNS", false),
    ObjectivesBgIsAvailableInNs("ObjectivesbgIsAvailableInNS", false),
    ObjectivesProfileSwitchUsed("ObjectivesProfileSwitchUsed", false),
    ObjectivesDisconnectUsed("ObjectivesDisconnectUsed", false),
    ObjectivesReconnectUsed("ObjectivesReconnectUsed", false),
    ObjectivesTempTargetUsed("ObjectivesTempTargetUsed", false),
    AutosensUsedOnMainPhone("used_autosens_on_main_phone", false),

    // Wizard toggle states (persisted across restarts, not user-facing preferences)
    WizardIncludeCob("wizard_include_cob", false),
    WizardIncludeTrend("wizard_include_trend_bg", false),
    // Bolus calculator ticks remembered from the last confirmed meal calculation (2026-11-05, per explicit request: the
    // simple screen uses the detailed screen's last settings). Auto fat/protein, Unreliable SMBs and Small meal are saved
    // to the Overview "Bolus calculator defaults" settings instead.
    WizardIncludeBg("wizard_include_bg", true),
    WizardIncludeTt("wizard_include_tt", true),
    WizardIncludeIob("wizard_include_iob", true),
    WizardSplitFpuBolus("wizard_split_fpu_bolus", true),
    WizardDelayFpuInsteadOfCancel("wizard_delay_fpu_instead_of_cancel", true),
    WizardGiveRestLater("wizard_give_rest_later", true),
    // 2026-11-14, per explicit request: the held part goes with the carb split (on) or the protein/fat doses (off)
    WizardGiveRestWithSplit("wizard_give_rest_with_split", true),

    // Export destination settings (managed by ExportOptionsDialog, no preferences UI)
    ExportAllCloudEnabled("export_all_cloud_enabled", false),
    ExportLogEmailEnabled("export_log_email_enabled", true),
    ExportLogCloudEnabled("export_log_cloud_enabled", false),
    ExportSettingsLocalEnabled("export_settings_local_enabled", true),
    ExportSettingsCloudEnabled("export_settings_cloud_enabled", false),
    ExportCsvLocalEnabled("export_csv_local_enabled", true),
    ExportCsvCloudEnabled("export_csv_cloud_enabled", false),

    AllowHardwarePump("allow_hardware_pump", false),

    // Set (committed) right before a startup VACUUM and cleared after it finishes. If it is still
    // set on the next launch, the previous VACUUM died below the JVM (native abort / OOM) — used to
    // break the boot-crash loop. Transient device state, not a user setting → not exportable.
    VacuumInProgress("vacuum_in_progress", false, exportable = false),

    // Client: the pairing secret has been replaced by the key exchange in the hello. Until then the client sends a hello
    // with a key on each connection, which is also how a phone paired from a QR code says hello at all. Belongs to the
    // pairing.
    NsClientControlSecretReplaced("nsclient_control_secret_replaced", false, exportable = false),
}