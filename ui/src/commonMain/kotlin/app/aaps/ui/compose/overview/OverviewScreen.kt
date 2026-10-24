package app.aaps.ui.compose.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.interfaces.pump.BolusProgressState
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.TABLET_MIN_SW_DP
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.keys.interfaces.AppPlatform
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.pump.PumpActivityDialog
import app.aaps.core.ui.compose.pump.PumpActivityFab
import app.aaps.core.ui.compose.smallestScreenWidthDp
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.notificationsSheet.NotificationBottomSheet
import app.aaps.ui.compose.notificationsSheet.NotificationFab
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.delay

/**
 * Window width, in dp, from which a desktop window gets the full-width overview rather than the split
 * one. About a 1536 dp window (a 1920 px screen at 125 %) is the full-screen case; a half-screen window
 * stays split.
 */
private const val DESKTOP_FULL_WIDTH_MIN_DP = 1400

/** Narrower than this, the split layout's left panel leaves the graphs too little room, so it stays wide. */
private const val SPLIT_LAYOUT_MIN_WIDTH_DP = 720f

internal enum class OverviewLayout { WIDE, SPLIT, TABLET }

/**
 * Which main screen layout to use (2026-10-24, per explicit request). Portrait is always wide. In landscape [choice] (the
 * Landscape layout setting) decides: "wide" and "split" force one, and "auto" keeps the old rule, which gives a tablet its
 * split layout and everything else wide. A tablet's split is its own layout (with the large clock); a phone's split needs
 * [SPLIT_LAYOUT_MIN_WIDTH_DP] of width, as it did before 26 Sep, when phones were moved to wide.
 */
internal fun overviewLayout(choice: String, landscape: Boolean, tabletScreen: Boolean, splitFits: Boolean, wideDesktopWindow: Boolean): OverviewLayout =
    when {
        !landscape        -> OverviewLayout.WIDE
        choice == "wide"  -> OverviewLayout.WIDE
        choice == "split" -> when {
            tabletScreen -> OverviewLayout.TABLET
            splitFits    -> OverviewLayout.SPLIT
            else         -> OverviewLayout.WIDE
        }

        else              -> if (tabletScreen && !wideDesktopWindow) OverviewLayout.TABLET else OverviewLayout.WIDE
    }

@Composable
fun OverviewScreen(
    profileName: String,
    profilePsId: Long = 0,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetRecordId: Long = 0,
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeRecordId: Long = 0,
    tbrState: TbrState,
    smbEnabled: Boolean,
    isSimpleMode: Boolean,
    calcProgress: Int,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    manageViewModel: ManageViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    autoShowNotificationSheet: Boolean,
    onAutoShowConsumed: () -> Unit,
    activeSceneState: ActiveSceneState? = null,
    sceneExpired: Boolean = false,
    activeSceneChainTargetName: String? = null,
    onEndScene: () -> Unit = {},
    onDismissScene: () -> Unit = {},
    endSceneEnabled: Boolean = true,
    // Disables the command chips' click (running mode / profile / temp target) on an unpaired client — same gate as nav/Manage.
    commandsAllowed: Boolean = true,
    formatDuration: (Long) -> String = { ms -> "${(ms / 60000L).toInt()}m" },
    paddingValues: PaddingValues,
    fabBottomOffset: Dp = 0.dp,
    /**
     * Height of the bars drawn over the content instead of beside it (preview mode). The columns add it to the
     * space at their end, so their last row can be scrolled clear of the bars and the toolbar while these show.
     */
    overlaidBarsHeight: Dp = 0.dp,
    bolusState: BolusProgressState? = null,
    pumpStatusText: String = "",
    queueStatusText: AnnotatedString? = null,
    isPumpCommunicating: Boolean = false,
    onStopBolus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showNotificationSheet by remember { mutableStateOf(false) }
    var showPumpActivityDialog by remember { mutableStateOf(false) }
    val showPumpFab = isPumpCommunicating || (bolusState != null && bolusState.isSMB)

    // Three seconds is enough to glance at the card, but not to hear it read out. Ask the platform
    // how long this content needs instead: with a screen reader on it stretches the timeout, and
    // with one off calculateRecommendedTimeoutMillis hands back the original 3 seconds unchanged.
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(showPumpFab) {
        if (!showPumpFab && showPumpActivityDialog) {
            delay(
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = 3_000,
                    containsIcons = true,
                    containsText = true,
                    containsControls = true
                ) ?: 3_000
            )
            showPumpActivityDialog = false
        }
    }

    LaunchedEffect(autoShowNotificationSheet) {
        if (autoShowNotificationSheet) {
            showNotificationSheet = true
            onAutoShowConsumed()
        }
    }

    val runningModeSceneManaged = activeSceneState?.scopedRecords?.rmId
        ?.let { it == runningModeRecordId && it > 0 } == true
    val tempTargetSceneManaged = activeSceneState?.scopedRecords?.ttId
        ?.let { it == tempTargetRecordId && it > 0 } == true
    val profileSceneManaged = activeSceneState?.scopedRecords?.psId
        ?.let { it == profilePsId && it > 0 } == true

    val isLandscape = isLandscape()
    // A wide desktop window gets the full-width layout (BG header on top, graphs across the whole window, the page
    // scrolls) instead of the split with a fixed left panel. Only desktop: a tablet keeps the split at any width.
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val wideDesktopWindow = LocalConfig.current.platform == AppPlatform.Desktop && windowWidthDp >= DESKTOP_FULL_WIDTH_MIN_DP
    val tabletScreen = smallestScreenWidthDp() >= TABLET_MIN_SW_DP
    val splitFits = windowWidthDp >= SPLIT_LAYOUT_MIN_WIDTH_DP
    val preferences = LocalPreferences.current
    val layoutChoice by preferences.observe(StringKey.OverviewLandscapeLayout).collectAsState()
    val layout = overviewLayout(layoutChoice, isLandscape, tabletScreen, splitFits, wideDesktopWindow)

    Box(modifier = modifier.fillMaxSize()) {
        if (layout == OverviewLayout.TABLET) {
            OverviewScreenTablet(
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                profileSceneManaged = profileSceneManaged,
                tempTargetText = tempTargetText,
                tempTargetState = tempTargetState,
                tempTargetProgress = tempTargetProgress,
                tempTargetReason = tempTargetReason,
                tempTargetSceneManaged = tempTargetSceneManaged,
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeRemaining = runningModeRemaining,
                runningModeProgress = runningModeProgress,
                runningModeSceneManaged = runningModeSceneManaged,
                tbrState = tbrState,
                smbEnabled = smbEnabled,
                isSimpleMode = isSimpleMode,
                graphViewModel = graphViewModel,
                chipsViewModel = chipsViewModel,
                manageViewModel = manageViewModel,
                statusViewModel = statusViewModel,
                statusLightsDef = statusLightsDef,
                onNavigate = onNavigate,
                onTbrChipClick = onTbrChipClick,
                onIobChipClick = onIobChipClick,
                paddingValues = paddingValues,
                activeSceneState = activeSceneState,
                sceneExpired = sceneExpired,
                activeSceneChainTargetName = activeSceneChainTargetName,
                onEndScene = onEndScene,
                onDismissScene = onDismissScene,
                endSceneEnabled = endSceneEnabled,
                commandsAllowed = commandsAllowed,
                formatDuration = formatDuration,
                overlaidBarsHeight = overlaidBarsHeight
            )
        } else if (layout == OverviewLayout.SPLIT) {
            OverviewScreenSplit(
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                profileSceneManaged = profileSceneManaged,
                tempTargetText = tempTargetText,
                tempTargetState = tempTargetState,
                tempTargetProgress = tempTargetProgress,
                tempTargetReason = tempTargetReason,
                tempTargetSceneManaged = tempTargetSceneManaged,
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeRemaining = runningModeRemaining,
                runningModeProgress = runningModeProgress,
                runningModeSceneManaged = runningModeSceneManaged,
                tbrState = tbrState,
                smbEnabled = smbEnabled,
                isSimpleMode = isSimpleMode,
                graphViewModel = graphViewModel,
                chipsViewModel = chipsViewModel,
                manageViewModel = manageViewModel,
                statusViewModel = statusViewModel,
                statusLightsDef = statusLightsDef,
                onNavigate = onNavigate,
                onTbrChipClick = onTbrChipClick,
                onIobChipClick = onIobChipClick,
                paddingValues = paddingValues,
                activeSceneState = activeSceneState,
                sceneExpired = sceneExpired,
                activeSceneChainTargetName = activeSceneChainTargetName,
                onEndScene = onEndScene,
                onDismissScene = onDismissScene,
                endSceneEnabled = endSceneEnabled,
                commandsAllowed = commandsAllowed,
                formatDuration = formatDuration,
                overlaidBarsHeight = overlaidBarsHeight
            )
        } else {
            OverviewScreenStacked(
                    profileName = profileName,
                    isProfileModified = isProfileModified,
                    profileProgress = profileProgress,
                    profileSceneManaged = profileSceneManaged,
                    tempTargetText = tempTargetText,
                    tempTargetState = tempTargetState,
                    tempTargetProgress = tempTargetProgress,
                    tempTargetReason = tempTargetReason,
                    tempTargetSceneManaged = tempTargetSceneManaged,
                    runningMode = runningMode,
                    runningModeText = runningModeText,
                    runningModeRemaining = runningModeRemaining,
                    runningModeProgress = runningModeProgress,
                    runningModeSceneManaged = runningModeSceneManaged,
                    tbrState = tbrState,
                    smbEnabled = smbEnabled,
                    isSimpleMode = isSimpleMode,
                    graphViewModel = graphViewModel,
                    chipsViewModel = chipsViewModel,
                    manageViewModel = manageViewModel,
                    statusViewModel = statusViewModel,
                    statusLightsDef = statusLightsDef,
                    onNavigate = onNavigate,
                    onTbrChipClick = onTbrChipClick,
                    onIobChipClick = onIobChipClick,
                    paddingValues = paddingValues,
                    activeSceneState = activeSceneState,
                    sceneExpired = sceneExpired,
                    activeSceneChainTargetName = activeSceneChainTargetName,
                    onEndScene = onEndScene,
                    onDismissScene = onDismissScene,
                    endSceneEnabled = endSceneEnabled,
                    commandsAllowed = commandsAllowed,
                    formatDuration = formatDuration,
                    overlaidBarsHeight = overlaidBarsHeight
            )
        }

        // 2026-10-24, per explicit request: one tap switches between the wide and the split layout and keeps the choice
        // (the Landscape layout setting). Only in landscape, and only where split fits. A phone in landscape hides the top
        // bar, so the button sits in the corner of the screen itself.
        if (isLandscape && (tabletScreen || splitFits)) {
            val toSplit = layout == OverviewLayout.WIDE
            IconButton(
                onClick = { preferences.put(StringKey.OverviewLandscapeLayout, if (toSplit) "split" else "wide") },
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(paddingValues)
            ) {
                Icon(
                    imageVector = if (toSplit) Icons.Filled.VerticalSplit else Icons.Filled.ViewAgenda,
                    contentDescription = stringResource(if (toSplit) UiStrings.overview_switch_to_split_layout else UiStrings.overview_switch_to_wide_layout)
                )
            }
        }

        List1Dialog(chipsViewModel)
        List2Dialog(chipsViewModel)
        List3Dialog(chipsViewModel)
        if (chipsViewModel.autoIsfHistoryOpen) {
            AutoIsfHistoryDialog(
                rows = chipsViewModel.autoIsfHistoryRows,
                onDismiss = chipsViewModel::closeAutoIsfHistory
            )
        }

        // Calculation progress (IOB / graph data). Overlaid on top of content so it never reflows
        // the layout — previously a flow child of the content Column which caused the screen to jump.
        AnimatedVisibility(
            visible = calcProgress < 100,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(paddingValues)
                .fillMaxWidth()
        ) {
            LinearProgressIndicator(
                progress = { calcProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
        }

        PumpActivityFab(
            visible = showPumpFab,
            bolusState = bolusState,
            onClick = { showPumpActivityDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(paddingValues)
                .padding(end = 16.dp, bottom = 128.dp + fabBottomOffset)
        )

        NotificationFab(
            notificationCount = notifications.size,
            highestLevel = notifications.minByOrNull { it.level.ordinal }?.level,
            onClick = { showNotificationSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(paddingValues)
                .padding(end = 16.dp, bottom = 72.dp + fabBottomOffset)
        )
    }

    if (showPumpActivityDialog) {
        PumpActivityDialog(
            bolusState = bolusState,
            pumpStatus = pumpStatusText,
            queueStatus = queueStatusText,
            isModal = false,
            onStop = onStopBolus,
            onDismiss = { showPumpActivityDialog = false }
        )
    }

    if (showNotificationSheet && notifications.isNotEmpty()) {
        NotificationBottomSheet(
            notifications = notifications,
            onDismissSheet = { showNotificationSheet = false },
            onDismissNotification = onDismissNotification,
            onNotificationActionClick = onNotificationActionClick
        )
    }
}
