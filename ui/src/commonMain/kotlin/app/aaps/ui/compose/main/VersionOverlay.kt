package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences

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
    Text(
        text = "${config.VERSION_NAME} (${config.HEAD.substring(0, minOf(4, config.HEAD.length))})",
        color = versionColor,
        fontSize = 10.sp,
        modifier = modifier.padding(top = 4.dp, start = 4.dp)
    )
}
