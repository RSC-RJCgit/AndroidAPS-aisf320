package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.keys.StringNonKey
import app.aaps.core.objects.overview.MasterFreeStorage
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.icons.IcSettingsOff
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.graphs.graphTimestamp
import app.aaps.ui.compose.overview.graphs.rememberMasterGraphTimeZone
import app.aaps.ui.compose.quickLaunch.LocalQuickLaunchAnchor
import app.aaps.ui.search.M3SearchBar
import app.aaps.ui.search.SearchUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

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
    val dateUtil = LocalDateUtil.current
    val displayZone = rememberMasterGraphTimeZone()
    val clock = remember(dateUtil) {
        flow {
            while (true) {
                val now = dateUtil.now()
                emit(now)
                delay(60_000L - now % 60_000L)
            }
        }
    }
    val now by clock.collectAsStateWithLifecycle(initialValue = dateUtil.now())
    // 2026-11-28, per explicit request: the search bar's middle, so the quick launch bar can open over its bottom half
    val quickLaunchAnchor = LocalQuickLaunchAnchor.current
    val searchBarAnchor = if (quickLaunchAnchor != null) Modifier.onGloballyPositioned {
        quickLaunchAnchor.searchBarMidInRoot = it.positionInRoot().y + it.size.height / 2f
    } else Modifier
    // The master's free storage after the clock (2026-11-14, per explicit request); a master shows its own.
    val isMaster = !LocalInspectionMode.current && LocalConfig.current.APS && !LocalConfig.current.AAPSCLIENT
    // The saved value is read here at once (2026-11-15, per explicit request): the NS handler restored it only when it first
    // ran, which on the Windows client could be minutes after a restart or an update, so no GB was shown until then
    if (!LocalInspectionMode.current) {
        val preferences = LocalPreferences.current
        remember(preferences) { MasterFreeStorage.restore(preferences.get(StringNonKey.ClientMasterFreeStorage)) }
    }
    // A mirrored value over 30 min old stays, greyed out, for up to 24 h (2026-11-15, per explicit request)
    val storage = MasterFreeStorage.display(now, isMaster)
    val staleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val timeText = buildAnnotatedString {
        append(graphTimestamp(dateUtil, now, displayZone))
        // No-break spaces (2026-11-15): with a normal space a short one-line bar wrapped the GB onto a hidden second line
        storage.gb?.let { gb ->
            append("  ")
            if (storage.stale) withStyle(SpanStyle(color = staleColor)) { append("$gb GB") } else append("$gb GB")
        }
    }
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
                modifier = Modifier.weight(1f).then(searchBarAnchor)
            )
            TopBarBg(bgInfo, compact = true, timeText = timeText)
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
                    modifier = Modifier.weight(1f).then(searchBarAnchor)
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
            TopBarBg(bgInfo, compact = false, timeText = timeText)
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
private fun TopBarBg(bgInfo: BgInfoData?, compact: Boolean, timeText: AnnotatedString) {
    // The compact bar (landscape, Windows) is one fixed 48 dp row: a second line fell below it, so the time and free
    // storage go beside the BG there (2026-11-15, per explicit request).
    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            if (bgInfo != null) TopBarBgValue(bgInfo, compact = true)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(start = 4.dp)
    ) {
        if (bgInfo != null) TopBarBgValue(bgInfo, compact)
        Text(
            text = timeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun TopBarBgValue(info: BgInfoData, compact: Boolean) {
    val color = when (info.bgRange) {
        BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
        BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
        BgRange.LOW      -> AapsTheme.generalColors.bgLow
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
