package app.aaps.ui.compose.trioRemote

import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.ui.UiStrings

/** What is still missing before Trio can be sent to, as the Trio remote screen lists it (2026-11-07) */
internal fun TrioRemoteSetup.missingTexts(): List<TextRef> = missing.map { missing ->
    when (missing) {
        TrioRemoteSetup.Missing.USER          -> UiStrings.trio_remote_missing_user
        TrioRemoteSetup.Missing.SHARED_SECRET -> if (simulator) UiStrings.trio_simulator_missing_secret else UiStrings.trio_remote_missing_shared_secret
        TrioRemoteSetup.Missing.KEY_ID        -> UiStrings.trio_remote_missing_key_id
        TrioRemoteSetup.Missing.APNS_KEY      -> UiStrings.trio_remote_missing_apns_key
        TrioRemoteSetup.Missing.TRIO_PROFILE  -> UiStrings.trio_remote_missing_trio_profile
        TrioRemoteSetup.Missing.TEAM_ID       -> UiStrings.trio_remote_missing_team_id
        TrioRemoteSetup.Missing.SIMULATOR_ID  -> UiStrings.trio_simulator_missing_id
    }
}
