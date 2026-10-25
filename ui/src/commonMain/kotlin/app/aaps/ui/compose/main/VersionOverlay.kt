package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * @see VersionOverlayPreview
 */
@Composable
fun VersionOverlay(
    modifier: Modifier = Modifier,
) {
    val config = LocalConfig.current
    val preferences = LocalPreferences.current
    // 2026-10-08, per explicit request: shown on every flavor, clients included (it used to need APS or PUMPCONTROL),
    // with its padding on the left edge where the label is placed.
    val colors = AapsTheme.generalColors
    val versionColor = when {
        config.COMMITTED                                                          -> colors.versionCommitted
        preferences.get(LongComposedKey.AppExpiration, config.VERSION_NAME) != 0L -> colors.versionWarning
        else                                                                      -> colors.versionUncommitted
    }
    val appName = when {
        config.AAPSCLIENT3 -> stringResource(UiStrings.overview_app_client3)
        config.AAPSCLIENT2 -> stringResource(UiStrings.overview_app_client2)
        config.AAPSCLIENT1 -> stringResource(UiStrings.overview_app_client)
        config.PUMPCONTROL -> stringResource(UiStrings.overview_app_pump_control)
        else               -> stringResource(UiStrings.overview_app_master)
    }
    // Only the major version, so "4.0.0-rc1" shows as v4
    val label = stringResource(UiStrings.overview_app_version, appName, config.VERSION_NAME.firstOrNull { it.isDigit() }?.toString().orEmpty())
    // The first part of the Nightscout address, for example "aapsfour" from https://aapsfour.ns.com.hr
    val nsUrl by preferences.observe(StringKey.NsClientUrl).collectAsStateWithLifecycle()
    val site = nsUrl.substringAfter("://").substringBefore('/').substringBefore('.').trim()
    // This is the patient_name setting, renamed GeneralInstanceName in v4. Observe it so edits show immediately.
    val patientName by preferences.observe(StringKey.GeneralInstanceName).collectAsStateWithLifecycle()
    val version = "${config.VERSION_NAME} (${config.HEAD.take(4)})"
    // 2026-10-21, per explicit request: one line, as in 3426. The version on the left, the app and its Nightscout site on the
    // right. The line is fixed, so it stays on screen while the page scrolls.
    Row(modifier = modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
        Text(
            text = patientName.trim().let { if (it.isEmpty()) version else "$version · $it" },
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            color = versionColor,
            fontSize = 10.sp
        )
        Text(
            text = if (site.isEmpty()) label else stringResource(UiStrings.overview_app_site, label, site),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}
