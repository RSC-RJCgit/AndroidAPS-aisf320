package app.aaps.plugins.sync.nsShared

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences

internal fun Preferences.secondaryTreatmentsConfigured(): Boolean =
    get(BooleanKey.NsClientUseSecondaryTreatments) &&
        get(StringKey.NsClientSecondaryUrl).isNotBlank() &&
        get(StringKey.NsClientSecondaryAccessToken).isNotBlank()
