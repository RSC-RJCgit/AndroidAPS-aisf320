package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable

/**
 * What the overview chips show.
 *
 * Kept in their own file rather than beside `ChipsViewModel`: these are plain values with no Android
 * in them, and every composable and preview that reads them can then be shared code.
 */
@Immutable
data class IobUiState(
    val text: String = "",
    val iobTotal: Double = 0.0
)

@Immutable
data class CobUiState(
    val text: String = "",
    val carbsReq: Int = 0,
    val cobValue: Double = 0.0
)

@Immutable
data class SensitivityUiState(
    val asText: String = "",
    val isfFrom: String = "",
    val isfTo: String = "",
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false,
    val autoIsfHistory: Boolean = false,
    /** First 5 letters of this device's main Nightscout site, shown at the right of the chip (2026-10-16). */
    val siteTag: String = ""
)

/** "https://nsnewrsc.my.nightscoutpro.com" -> "nsnew". Empty when no site is set. */
internal fun nsSiteTag(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://").take(5)
