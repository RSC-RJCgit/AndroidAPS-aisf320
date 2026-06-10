package app.aaps.core.interfaces.notifications

import app.aaps.core.interfaces.notifications.NotificationId.Companion.fromOrdinal

/**
 * Identity + intrinsic severity of every AAPS notification.
 *
 * [defaultLevel] is the single source of truth for severity — a caller should not normally
 * override it at the post site. [NotificationLevel.URGENT] is the alarm tier (sound + ramp +
 * full-screen) and is reserved for acute insulin-delivery failures, critical BG, and
 * user-configured alarms.
 *
 * The system-notification id is derived from [Enum.ordinal] (see [fromOrdinal]); there is no
 * hand-assigned integer id anymore (the old pre-enum `legacyId` was a fossil with no external
 * consumer).
 */
@Suppress("unused")
enum class NotificationId(
    val category: NotificationCategory = NotificationCategory.GENERAL,
    val defaultLevel: NotificationLevel = NotificationLevel.NORMAL,
    val allowMultiple: Boolean = false
) {

    // Profile
    PROFILE_SET_OK(category = NotificationCategory.PROFILE, defaultLevel = NotificationLevel.INFO),
    PROFILE_NOT_SET_NOT_INITIALIZED(category = NotificationCategory.PROFILE, defaultLevel = NotificationLevel.URGENT),

    // Basal profile failed to write to the pump (wrong basal until fixed). Also covers the old
    // DanaR-only PROFILE_SET_FAILED, which was merged here.
    FAILED_UPDATE_PROFILE(category = NotificationCategory.PROFILE, defaultLevel = NotificationLevel.URGENT),
    INVALID_PROFILE_NOT_ACCEPTED(category = NotificationCategory.PROFILE),

    // Pump — general
    EXTENDED_BOLUS_DISABLED(category = NotificationCategory.PUMP),
    PUMP_ERROR(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    WRONG_SERIAL_NUMBER(category = NotificationCategory.PUMP),
    WRONG_BASAL_STEP(category = NotificationCategory.PUMP),
    WRONG_DRIVER(category = NotificationCategory.PUMP),
    PUMP_UNREACHABLE(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    UNSUPPORTED_FIRMWARE(category = NotificationCategory.PUMP),
    MINIMAL_BASAL_VALUE_REPLACED(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.INFO),
    BASAL_PROFILE_NOT_ALIGNED_TO_HOURS(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.INFO),
    WRONG_PUMP_PASSWORD(category = NotificationCategory.PUMP),
    MAXIMUM_BASAL_VALUE_REPLACED(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.INFO),
    DEVICE_NOT_PAIRED(category = NotificationCategory.PUMP),
    UNSUPPORTED_ACTION_IN_PUMP(category = NotificationCategory.PUMP),
    WRONG_PUMP_DATA(category = NotificationCategory.PUMP),
    PUMP_SUSPENDED(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.IMPORTANT),
    BLUETOOTH_NOT_ENABLED(category = NotificationCategory.PUMP),
    PATCH_NOT_ACTIVE(category = NotificationCategory.PUMP),
    PUMP_SETTINGS_FAILED(category = NotificationCategory.PUMP),
    PUMP_TIMEZONE_UPDATE_FAILED(category = NotificationCategory.PUMP),
    BLUETOOTH_NOT_SUPPORTED(category = NotificationCategory.PUMP),
    PUMP_WARNING(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.IMPORTANT),
    PUMP_SYNC_ERROR(category = NotificationCategory.PUMP),
    BASAL_VALUE_BELOW_MINIMUM(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.INFO),

    // Pump — Combo
    COMBO_PUMP_ALARM(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    COMBO_UNKNOWN_TBR(category = NotificationCategory.PUMP),

    // Pump — Medtronic
    MEDTRONIC_PUMP_ALARM(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    RILEYLINK_CONNECTION(category = NotificationCategory.PUMP),
    MDT_INVALID_HISTORY_DATA(category = NotificationCategory.PUMP),

    // Pump — Insight
    INSIGHT_DATE_TIME_UPDATED(category = NotificationCategory.PUMP),
    INSIGHT_TIMEOUT_DURING_HANDSHAKE(category = NotificationCategory.PUMP),

    // Pump — Omnipod
    OMNIPOD_POD_NOT_ATTACHED(category = NotificationCategory.PUMP),
    OMNIPOD_POD_SUSPENDED(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.IMPORTANT),
    OMNIPOD_POD_ALERTS_UPDATED(category = NotificationCategory.PUMP),
    OMNIPOD_POD_ALERTS(category = NotificationCategory.PUMP),
    OMNIPOD_TBR_ALERTS(category = NotificationCategory.PUMP),
    OMNIPOD_POD_FAULT(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    OMNIPOD_UNCERTAIN_SMB(category = NotificationCategory.PUMP),
    OMNIPOD_UNKNOWN_TBR(category = NotificationCategory.PUMP),
    OMNIPOD_STARTUP_STATUS_REFRESH_FAILED(category = NotificationCategory.PUMP),
    OMNIPOD_TIME_OUT_OF_SYNC(category = NotificationCategory.PUMP),

    // Pump — EOPatch
    EOFLOW_PATCH_ALERT(category = NotificationCategory.PUMP, allowMultiple = true),

    // Pump — Equil
    EQUIL_ALARM(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),
    EQUIL_ALARM_INSULIN(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),

    // Pump — Dana
    DANA_PUMP_ALARM(category = NotificationCategory.PUMP, defaultLevel = NotificationLevel.URGENT),

    // Pump — Dana emulator
    PUMP_EMULATOR_DISPLAY(category = NotificationCategory.PUMP),

    // CGM
    BG_READINGS_MISSED(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.IMPORTANT),
    SENSOR_CHANGE_DETECTED(category = NotificationCategory.CGM),

    // CGM — Aidex
    AIDEX_SENSOR_EXPIRED(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.URGENT),
    AIDEX_SENSOR_ERROR(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.URGENT),
    AIDEX_SENSOR_STABILIZING(category = NotificationCategory.CGM),
    AIDEX_REPLACE_SENSOR(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.URGENT),
    AIDEX_SIGNAL_LOST(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.IMPORTANT),
    EVERSENSE_RELEASE(category = NotificationCategory.CGM),
    EVERSENSE_PLACEMENT(category = NotificationCategory.CGM),
    EVERSENSE_CREDENTIALS(category = NotificationCategory.CGM),
    EVERSENSE_FIRMWARE(category = NotificationCategory.CGM, defaultLevel = NotificationLevel.INFO),

    // Loop / APS
    EASY_MODE_ENABLED(category = NotificationCategory.LOOP),
    UD_MODE_ENABLED(category = NotificationCategory.LOOP),
    SHORT_DIA(category = NotificationCategory.LOOP),
    CARBS_REQUIRED(category = NotificationCategory.LOOP, defaultLevel = NotificationLevel.IMPORTANT),
    SMB_FALLBACK(category = NotificationCategory.LOOP, defaultLevel = NotificationLevel.IMPORTANT),
    DYN_ISF_FALLBACK(category = NotificationCategory.LOOP, defaultLevel = NotificationLevel.IMPORTANT),

    // Sync — Nightscout
    OLD_NS(category = NotificationCategory.SYNC),
    NSCLIENT_NO_WRITE_PERMISSION(category = NotificationCategory.SYNC),
    NS_ANNOUNCEMENT(category = NotificationCategory.SYNC, defaultLevel = NotificationLevel.ANNOUNCEMENT),
    NS_ALARM(category = NotificationCategory.SYNC, defaultLevel = NotificationLevel.IMPORTANT),
    NS_URGENT_ALARM(category = NotificationCategory.SYNC, defaultLevel = NotificationLevel.URGENT),
    NS_MALFUNCTION(category = NotificationCategory.SYNC),
    NSCLIENT_VERSION_DOES_NOT_MATCH(category = NotificationCategory.SYNC),
    OPEN_HUMANS_SIGNED_OUT(category = NotificationCategory.SYNC),

    // Sync — SMS
    INVALID_PHONE_NUMBER(category = NotificationCategory.SYNC),
    INVALID_MESSAGE_BODY(category = NotificationCategory.SYNC),
    APPROACHING_DAILY_LIMIT(category = NotificationCategory.SYNC),

    // System
    TOAST_ALARM(category = NotificationCategory.SYSTEM),
    DST_LOOP_DISABLED(category = NotificationCategory.SYSTEM),
    DST_IN_24H(category = NotificationCategory.SYSTEM),
    DISK_FULL(category = NotificationCategory.SYSTEM, defaultLevel = NotificationLevel.URGENT),
    OVER_24H_TIME_CHANGE_REQUESTED(category = NotificationCategory.SYSTEM),
    INVALID_VERSION(category = NotificationCategory.SYSTEM),
    TIME_OR_TIMEZONE_CHANGE(category = NotificationCategory.SYSTEM),
    NEW_VERSION_DETECTED(category = NotificationCategory.SYSTEM),
    VERSION_EXPIRE(category = NotificationCategory.SYSTEM),
    IDENTIFICATION_NOT_SET(category = NotificationCategory.SYSTEM),
    MASTER_PASSWORD_NOT_SET(category = NotificationCategory.SYSTEM),
    AAPS_DIR_NOT_SELECTED(category = NotificationCategory.SYSTEM),
    GOOGLE_DRIVE_ERROR(category = NotificationCategory.SYSTEM),
    SETTINGS_EXPORT_RESULT(category = NotificationCategory.SYSTEM),
    SNACKBAR_FALLBACK(category = NotificationCategory.SYSTEM, allowMultiple = true),

    // Automation — general notification action (NOT the "Alarm" action, which uses the system
    // alarm clock via TimerUtil.scheduleReminder, not this notification path).
    AUTOMATION_MESSAGE(category = NotificationCategory.AUTOMATION, allowMultiple = true),

    // Scenes
    SCENE_ENDED(category = NotificationCategory.SYSTEM, allowMultiple = true),
    SCENE_CHAINED(category = NotificationCategory.SYSTEM, allowMultiple = true),
    SCENE_CHAIN_SKIPPED(category = NotificationCategory.SYSTEM, allowMultiple = true),
    SCENE_CHAIN_ERROR(category = NotificationCategory.SYSTEM, allowMultiple = true),
    EVERSENSE_ALARM(category = NotificationCategory.CGM);

    companion object {

        fun fromOrdinal(ordinal: Int): NotificationId? = entries.getOrNull(ordinal)
    }
}


