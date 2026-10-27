package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcSettingsOff
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.search.M3SearchBar
import app.aaps.ui.search.SearchUiState

/**
 * Main top bar with M3-style search bar.
 * Layout: [Menu] [----Search Bar----] [SimpleMode?] [Settings]
 *
 * @param searchUiState Current search UI state
 * @param onMenuClick Called when menu button is clicked
 * @param onPreferencesClick Called when preferences button is clicked
 * @param onSearchQueryChange Called when search query changes
 * @param onSearchClear Called when search query is cleared
 * @param onSearchActiveChange Called when search active state changes
 * @param isSimpleMode When true, shows a non-interactive simple-mode indicator next to Settings
 * @param compact Shorter bar for landscape so the graphs keep their height
 * @param bgInfo The current BG, shown small beside the search field so it never scrolls away (null shows nothing)
 * @param modifier Modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    searchUiState: SearchUiState,
    onMenuClick: () -> Unit,
    onPreferencesClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClear: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    isSimpleMode: Boolean = false,
    compact: Boolean = false,
    bgInfo: BgInfoData? = null,
    modifier: Modifier = Modifier
) {
    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .height(AapsSpacing.compactTopBarHeight)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(CoreUiStrings.open_navigation)
                )
            }
            M3SearchBar(
                query = searchUiState.query,
                isActive = searchUiState.isSearchActive,
                onQueryChange = onSearchQueryChange,
                onClearClick = onSearchClear,
                onActiveChange = onSearchActiveChange,
                compact = true,
                modifier = Modifier.weight(1f)
            )
            TopBarBg(bgInfo, compact = true)
            IconButton(onClick = onPreferencesClick) {
                Icon(
                    imageVector = if (isSimpleMode) IcSettingsOff else Icons.Default.Settings,
                    contentDescription = stringResource(CoreUiStrings.settings)
                )
            }
        }
        return
    }
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                M3SearchBar(
                    query = searchUiState.query,
                    isActive = searchUiState.isSearchActive,
                    onQueryChange = onSearchQueryChange,
                    onClearClick = onSearchClear,
                    onActiveChange = onSearchActiveChange,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(CoreUiStrings.open_navigation)
                )
            }
        },
        actions = {
            TopBarBg(bgInfo, compact = false)
            IconButton(onClick = onPreferencesClick) {
                // In simple mode the gear shows "crossed" (IcSettingsOff) to signal the mode;
                // the button action (open settings) is unchanged.
                Icon(
                    imageVector = if (isSimpleMode) IcSettingsOff else Icons.Default.Settings,
                    contentDescription = stringResource(CoreUiStrings.settings)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        windowInsets = WindowInsets(0),
        modifier = modifier
    )
}

/**
 * The current BG beside the search field (2026-10-27, per explicit request): the top bar never scrolls, so the BG is
 * always in view. Same rules as the big BG: coloured by range, struck through when the reading is old. Display only,
 * so it cannot take a tap meant for search.
 */
@Composable
private fun TopBarBg(bgInfo: BgInfoData?, compact: Boolean) {
    val info = bgInfo ?: return
    val color = when (info.bgRange) {
        BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
        BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
        BgRange.LOW      -> AapsTheme.generalColors.bgLow
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = info.bgText,
            color = color,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textDecoration = if (info.isOutdated) TextDecoration.LineThrough else TextDecoration.None,
            maxLines = 1
        )
        info.trendArrow?.takeIf { it != TrendArrow.NONE }?.let { trend ->
            Text(
                text = trend.topBarSymbol(),
                color = color,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1
            )
        }
        info.deltaText?.let { delta ->
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = delta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** The arrow for the top bar. A triple arrow has no symbol of its own ("X"), so it shows as the double one. */
private fun TrendArrow.topBarSymbol(): String = when (this) {
    TrendArrow.TRIPLE_UP   -> TrendArrow.DOUBLE_UP.symbol
    TrendArrow.TRIPLE_DOWN -> TrendArrow.DOUBLE_DOWN.symbol
    else                   -> symbol
}
