package app.aaps.plugins.sync.nsShared

import android.content.Context
import androidx.preference.PreferenceGroup
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.objects.graph.MasterGraphTime
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.R

internal fun PreferenceGroup.addClientGraphTimePreferences(context: Context) {
    addPreference(
        AdaptiveSwitchPreference(
            ctx = context,
            booleanKey = BooleanKey.ClientGraphFollowMasterTimeZone,
            title = R.string.client_graph_follow_master_time_zone,
            summary = R.string.client_graph_follow_master_time_zone_summary
        )
    )
    addPreference(
        AdaptiveStringPreference(
            ctx = context,
            stringKey = StringKey.ClientGraphManualUtcOffset,
            title = R.string.client_graph_manual_utc_offset,
            summary = R.string.client_graph_manual_utc_offset_summary,
            dialogMessage = R.string.client_graph_manual_utc_offset_summary,
            validatorParams = DefaultEditTextValidator.Parameters(
                emptyAllowed = true,
                testType = EditTextValidator.TEST_REGEXP,
                customRegexp = MasterGraphTime.MANUAL_OFFSET_PATTERN
            )
        )
    )
}
