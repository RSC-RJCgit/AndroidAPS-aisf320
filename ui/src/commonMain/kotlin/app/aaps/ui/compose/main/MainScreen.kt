package app.aaps.ui.compose.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.BolusProgressState
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.ThreeButtonDialog
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.aboutDialog.AboutAlertDialog
import app.aaps.ui.compose.aboutDialog.AboutDialogData
import app.aaps.ui.compose.maintenance.ImportSource
import app.aaps.ui.compose.maintenance.MaintenanceDialogs
import app.aaps.ui.compose.maintenance.MaintenanceViewModel
import app.aaps.ui.compose.manageSheet.ManageSheetState
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.overview.EversenseCalibrationProgress
import app.aaps.ui.compose.overview.EatReminderBanner
import app.aaps.ui.compose.overview.OverviewScreen
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphInspectPillOf
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.TopBarPillHost
import app.aaps.ui.compose.overview.graphs.LocalTopBarPillHost
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.compose.quickLaunch.QuickLaunchAction
import app.aaps.ui.compose.quickLaunch.QuickLaunchButtonSize
import app.aaps.ui.compose.quickLaunch.QuickLaunchLargeButtonSize
import app.aaps.ui.compose.quickLaunch.QuickLaunchToolbar
import app.aaps.ui.compose.quickLaunch.ResolvedQuickLaunchItem
import app.aaps.ui.compose.scenesSheet.ScenesBottomSheet
import app.aaps.ui.compose.scenesSheet.ScenesViewModel
import app.aaps.ui.compose.treatmentsSheet.TreatmentBottomSheet
import app.aaps.ui.compose.treatmentsSheet.TreatmentViewModel
import app.aaps.ui.compose.trioRemote.missingTexts
import app.aaps.ui.search.SearchIndexEntry
import app.aaps.ui.search.SearchResults
import app.aaps.ui.search.SearchUiState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    aboutDialogData: AboutDialogData?,
    manageSheetState: ManageSheetState,
    manageViewModel: ManageViewModel,
    maintenanceViewModel: MaintenanceViewModel,
    statusViewModel: StatusViewModel,
    treatmentViewModel: TreatmentViewModel,
    scenesViewModel: ScenesViewModel,
    loopActionViewModel: app.aaps.ui.compose.loopSheet.LoopActionViewModel,
    // Search
    searchUiState: SearchUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearchClear: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchResultClick: (SearchIndexEntry) -> Unit,
    onSearchHistoryClick: (String) -> Unit,
    onSearchHistoryRemove: (String) -> Unit,
    onSearchPluginToggle: (PluginBase) -> Unit,
    onConfirmSearchPluginSwitch: () -> Unit,
    onDismissSearchPluginSwitch: () -> Unit,
    onConfirmSearchHardwarePump: () -> Unit,
    onDismissSearchHardwarePump: () -> Unit,
    // Menu/navigation
    onMenuClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    onDrawerClosed: () -> Unit,
    onAboutDialogDismiss: () -> Unit,
    /** Null hides the button - only Android has the problem it links to. */
    onOpenBatteryHelp: (() -> Unit)?,
    onMaintenanceSheetDismiss: () -> Unit,
    onDirectoryClick: () -> Unit,
    onAddArchiveFolder: (() -> Unit)? = null,
    onLaunchBrowser: (String) -> Unit,
    onBringToForeground: () -> Unit,
    onImportSettingsNavigate: (ImportSource) -> Unit,
    onRecreateActivity: () -> Unit,
    // Notifications
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    autoShowNotificationSheet: Boolean,
    onAutoShowConsumed: () -> Unit,
    // Alert kinds silent or off on this phone, for the line in the notification sheet (none on the desktop)
    silentAlertKinds: Int = 0,
    onOpenAlertKinds: (() -> Unit)? = null,
    // Pump setup
    pumpSetupPlugin: PluginBase? = null,
    // BG source shortcut
    bgSetupPlugin: PluginBase? = null,
    bgQualityBadgeIcon: ImageVector? = null,
    bgQualityBadgeTint: Color = Color.Unspecified,
    bgQualityBadgeDescription: String? = null,
    // Objectives progress
    objectivesSetupPlugin: PluginBase? = null,
    objectivesProgressText: String? = null,
    objectivesProgressDescription: String? = null,
    objectivesBypassed: Boolean = false,
    // Permissions
    permissionsMissing: Boolean = false,
    onPermissionsClick: () -> Unit = {},
    // Toolbar
    quickLaunchItems: List<ResolvedQuickLaunchItem> = emptyList(),
    onQuickLaunchActionClick: (QuickLaunchAction) -> Unit = {},
    calcProgress: Int,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    treatmentButtonsDef: PreferenceSubScreenDef,
    // Pump activity
    bolusState: BolusProgressState? = null,
    pumpStatusText: String = "",
    queueStatusText: AnnotatedString? = null,
    isPumpCommunicating: Boolean = false,
    onStopBolus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LocalDateUtil.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showTreatmentSheet by remember { mutableStateOf(false) }
    var showAutomationSheet by remember { mutableStateOf(false) }
    val availableCodedActions by chipsViewModel.availableCodedActions.collectAsStateWithLifecycle()
    val visibleQuickLaunchItems = quickLaunchItems.filter { item ->
        val action = item.action
        action !is QuickLaunchAction.CodedAction || action in availableCodedActions
    }
    var pendingCodedAction by remember { mutableStateOf<QuickLaunchAction.CodedAction?>(null) }
    val handleQuickLaunch: (QuickLaunchAction) -> Unit = { action ->
        if (action is QuickLaunchAction.CodedAction) pendingCodedAction = action
        else onQuickLaunchActionClick(action)
    }
    pendingCodedAction?.let { action ->
        OkCancelDialog(
            title = stringResource(action.label),
            message = stringResource(UiStrings.overview_action_confirm),
            onConfirm = {
                pendingCodedAction = null
                chipsViewModel.confirmCodedAction(action)
            },
            onDismiss = { pendingCodedAction = null }
        )
    }
    var showLoopActionSheet by remember { mutableStateOf(false) }
    // The bottom bar's buttons in a sheet, opened by a tap on the big BG circle (2026-11-12, per explicit request)
    var showNavigationSheet by remember { mutableStateOf(false) }
    val showNavigationBarPref by remember { mainViewModel.preferences.observe(BooleanKey.OverviewShowNavigationBar) }
        .collectAsStateWithLifecycle()
    // 2026-11-26, per explicit request: List 2 "Quick-launch buttons" normal / large, this phone only
    val quickLaunchLarge by remember { mainViewModel.preferences.observe(BooleanNonKey.QuickLaunchLargeButtons) }
        .collectAsStateWithLifecycle()
    val quickLaunchButtonSize = if (quickLaunchLarge) QuickLaunchLargeButtonSize else QuickLaunchButtonSize
    // 2026-11-27, per explicit request: the portrait quick-launch bar can be dragged by its grip and turned vertical
    val quickLaunchVertical by remember { mainViewModel.preferences.observe(BooleanNonKey.QuickLaunchVertical) }
        .collectAsStateWithLifecycle()
    val quickLaunchSavedX by remember { mainViewModel.preferences.observe(IntNonKey.QuickLaunchX) }.collectAsStateWithLifecycle()
    val quickLaunchSavedY by remember { mainViewModel.preferences.observe(IntNonKey.QuickLaunchY) }.collectAsStateWithLifecycle()
    var quickLaunchDragPos by remember { mutableStateOf<Offset?>(null) }
    var quickLaunchPlacedAt by remember { mutableStateOf(Offset.Zero) }
    var quickLaunchBarSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var quickLaunchAreaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val automationState by scenesViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current

    // Sync drawer state with ui state
    LaunchedEffect(uiState.isDrawerOpen) {
        if (uiState.isDrawerOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && uiState.isDrawerOpen) {
            onDrawerClosed()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawer(
                drawerState = drawerState,
                versionName = mainViewModel.versionName,
                onNavigate = { request ->
                    // Close the drawer first, then open the screen. Opened while the drawer was still
                    // sliding in (a fast tap, as the e2e test makes), one Back from that screen left
                    // the app instead of coming back to the overview.
                    scope.launch {
                        drawerState.close()
                        onDrawerClosed()
                        onNavigate(request)
                    }
                },
                isTreatmentsEnabled = uiState.isProfileLoaded,
                showExit = mainViewModel.showExit
            )
        },
        // Opened only from the menu button. A swipe from the left edge opened it while the user was
        // dragging a graph to scroll it back in time. A swipe still closes it once it is open.
        gesturesEnabled = drawerState.isOpen,
        modifier = modifier
    ) {
        // Protect both the overview and its overlaid controls from side navigation bars/cutouts.
        // Consume horizontal insets here so Scaffold does not reserve the same space a second time.
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val landscapeNavigation = maxWidth > maxHeight
            val previewMode = maxHeight < PREVIEW_MODE_MIN_HEIGHT
            var chromeVisible by remember { mutableStateOf(false) }
            val showChrome = landscapeNavigation || !previewMode || chromeVisible
            val interactionSource = remember { MutableInteractionSource() }
            val accessibilityManager = LocalAccessibilityManager.current

            // Measure actual bar heights for content padding in non-preview mode
            var topBarHeightPx by remember { mutableIntStateOf(0) }
            var bottomBarHeightPx by remember { mutableIntStateOf(0) }

            // Over the one-column overview the top bar (menu, search, settings) is a switch with two
            // states, shown or hidden, never half. A push up in the scrolling part hides it; a pull down
            // when that part is already at its top shows it again. The content moves down and up by its
            // height, nothing is drawn over anything. At the start of the app it shows, then hides (a
            // peek), so everyone sees where it is. It stays while a search is open. A tap on the menu
            // button left while it is hidden shows it again too, behind the opening menu.
            //
            // In preview mode (a short window, a phone in landscape) the same gestures show and hide all
            // the bars, which then lie over the content, and the menu button stays in the top left corner.
            // The content takes every touch: a layer over it that only woke the bars ate the first touch,
            // so a swipe on the graphs did not scroll them.
            var overviewStacked by remember { mutableStateOf(false) }
            var topBarHidden by remember { mutableStateOf(false) }
            var topBarPeekDone by rememberSaveable { mutableStateOf(false) }
            val searchActive by rememberUpdatedState(searchUiState.isSearchActive)
            val inPreviewMode by rememberUpdatedState(previewMode)
            LaunchedEffect(overviewStacked) {
                if (!overviewStacked || topBarPeekDone) return@LaunchedEffect
                delay(TOP_BAR_PEEK_MS)
                if (!searchActive) topBarHidden = true
                topBarPeekDone = true
            }
            val topBarScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (searchActive) return Offset.Zero
                        val userInput = source == NestedScrollSource.UserInput
                        // A push up that the content took, or one it could not take: only the graphs scroll,
                        // and a short set of graphs fits without scrolling. The bar must hide then too
                        val pushUp = consumed.y < -TOP_BAR_SCROLL_THRESHOLD_PX || (userInput && available.y < -TOP_BAR_SCROLL_THRESHOLD_PX)
                        // A drag down that the content could not take: it is at its top already
                        val pullDown = userInput && available.y > TOP_BAR_SCROLL_THRESHOLD_PX
                        if (inPreviewMode) {
                            if (pushUp) chromeVisible = false else if (pullDown) chromeVisible = true
                        } else if (overviewStacked) {
                            if (pushUp) topBarHidden = true else if (pullDown) topBarHidden = false
                        }
                        return Offset.Zero
                    }
                }
            }
            // In preview mode the top bar comes and goes with the other bars
            val topBarShown = if (previewMode) showChrome
            else showChrome && !(overviewStacked && topBarHidden && !searchUiState.isSearchActive)

            // Auto-hide chrome after timeout, reset when leaving preview mode. Not while the menu or a search
            // is open: the bars shown behind the menu are there when it closes, and the timeout starts then
            val drawerOpen = drawerState.isOpen
            LaunchedEffect(chromeVisible, previewMode, drawerOpen, searchUiState.isSearchActive) {
                if (!previewMode) {
                    chromeVisible = false
                    return@LaunchedEffect
                }
                if (chromeVisible && !drawerOpen && !searchUiState.isSearchActive) {
                    // Honours the system "Time to take action" setting: a screen reader user moving
                    // through the bars needs longer than 3 s before they hide again.
                    delay(accessibilityManager?.calculateRecommendedTimeoutMillis(AUTO_HIDE_DELAY_MS, containsIcons = true, containsControls = true) ?: AUTO_HIDE_DELAY_MS)
                    chromeVisible = false
                }
            }

            // topBar/bottomBar are intentionally absent — chrome is rendered as
            // overlays inside the content (see AnimatedVisibility blocks below) so
            // it can hide in preview mode without reflowing layout. The status-bar
            // and navigation-bar protection scrims rely on this: they read raw
            // WindowInsets.statusBars / navigationBars, which would be consumed
            // (returning zero height) if those Scaffold slots were populated.
            // Where the graphs' press-and-hold pill is drawn: over the top bar (see TopBarPillHost)
            val topBarPillHost = remember { TopBarPillHost() }
            val topBarBgInfo by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
            Scaffold { scaffoldPadding ->
                val hasToolbar = visibleQuickLaunchItems.isNotEmpty()
                val loopActionState = loopActionViewModel.uiState.collectAsStateWithLifecycle().value
                // Portrait bottom bar: hidden by default (OverviewShowNavigationBar), the Quick launch bar then sits in its place.
                // It still shows while one of its attention buttons is up, so a waiting loop suggestion or a missing setup
                // cannot go unseen behind a tap on the BG.
                val attentionPending = pumpSetupPlugin != null || bgSetupPlugin != null || permissionsMissing || loopActionState.actionAvailable
                val bottomBarShown = showNavigationBarPref || attentionPending

                // Keep the top bar (including Search and Settings) available even when
                // landscape height is short. The bottom navigation may still auto-hide.
                val topBarHeight = with(density) { topBarHeightPx.toDp() }
                val bottomBarHeight = if (bottomBarShown) with(density) { bottomBarHeightPx.toDp() } else 0.dp
                val contentPadding = PaddingValues(
                    start = if (landscapeNavigation) scaffoldPadding.calculateStartPadding(layoutDirection) + AapsSpacing.navigationRailWidth else 0.dp,
                    top = scaffoldPadding.calculateTopPadding() + topBarHeight,
                    bottom = scaffoldPadding.calculateBottomPadding() + if (previewMode || landscapeNavigation) 0.dp else bottomBarHeight
                )

                val activeSceneState by mainViewModel.activeSceneState.collectAsStateWithLifecycle()
                val sceneExpired by mainViewModel.sceneExpired.collectAsStateWithLifecycle()
                val activeSceneChainTargetName by mainViewModel.activeSceneChainTargetName.collectAsStateWithLifecycle()
                val eatReminderState by mainViewModel.eatReminderState.collectAsStateWithLifecycle()
                val masterReachable by mainViewModel.masterReachable.collectAsStateWithLifecycle()
                // Stable pairing signal — hides the mutating nav buttons on an unpaired client.
                val masterOrPairedClient by mainViewModel.masterOrPairedClient.collectAsStateWithLifecycle()
                // (Probe-while-offline is now global — see ComposeMainActivity. This screen still reads
                // masterReachable for its own gating.)
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main content. The graphs hand their press-and-hold pill to the top bar below (2026-10-27).
                    CompositionLocalProvider(LocalTopBarPillHost provides topBarPillHost) {
                    OverviewScreen(
                        profileName = uiState.profileName,
                        profilePsId = uiState.profilePsId,
                        isProfileModified = uiState.isProfileModified,
                        profileProgress = uiState.profileProgress,
                        tempTargetText = uiState.tempTargetText,
                        tempTargetState = uiState.tempTargetState,
                        tempTargetProgress = uiState.tempTargetProgress,
                        tempTargetReason = uiState.tempTargetReason,
                        tempTargetRecordId = uiState.tempTargetRecordId,
                        runningMode = uiState.runningMode,
                        runningModeText = uiState.runningModeText,
                        runningModeRemaining = uiState.runningModeRemaining,
                        runningModeProgress = uiState.runningModeProgress,
                        runningModeRecordId = uiState.runningModeRecordId,
                        tbrState = uiState.tbrState,
                        tbrRate = uiState.tbrRate,
                        tbrText = uiState.tbrText,
                        tbrProgress = uiState.tbrProgress,
                        smbEnabled = uiState.smbEnabled,
                        isSimpleMode = uiState.isSimpleMode,
                        calcProgress = calcProgress,
                        graphViewModel = graphViewModel,
                        chipsViewModel = chipsViewModel,
                        manageViewModel = manageViewModel,
                        statusViewModel = statusViewModel,
                        statusLightsDef = statusLightsDef,
                        onNavigate = onNavigate,
                        onBgClick = { showNavigationSheet = true },
                        onTbrChipClick = mainViewModel::showTbrInfo,
                        onIobChipClick = chipsViewModel::showIobInfo,
                        notifications = notifications,
                        onDismissNotification = onDismissNotification,
                        onNotificationActionClick = onNotificationActionClick,
                        autoShowNotificationSheet = autoShowNotificationSheet,
                        onAutoShowConsumed = onAutoShowConsumed,
                        silentAlertKinds = silentAlertKinds,
                        onOpenAlertKinds = onOpenAlertKinds,
                        activeSceneState = activeSceneState,
                        sceneExpired = sceneExpired,
                        activeSceneChainTargetName = activeSceneChainTargetName,
                        onEndScene = { mainViewModel.requestSceneDeactivation() },
                        onDismissScene = { mainViewModel.dismissExpiredScene() },
                        endSceneEnabled = masterReachable,
                        commandsAllowed = masterOrPairedClient,
                        formatDuration = mainViewModel::formatDuration,
                        eatReminderBanner = {
                            EversenseCalibrationProgress()
                            EatReminderBanner(
                                state = eatReminderState,
                                onStop = mainViewModel::stopEatReminder,
                                onExtend = mainViewModel::extendEatReminder,
                                // Like End scene: a client's buttons ask the master, which needs the connection.
                                controlsEnabled = masterReachable
                            )
                        },
                        paddingValues = contentPadding,
                        fabBottomOffset = if (hasToolbar && showChrome && !landscapeNavigation) quickLaunchButtonSize + 8.dp else 0.dp,
                        bolusState = bolusState,
                        pumpStatusText = pumpStatusText,
                        queueStatusText = queueStatusText,
                        isPumpCommunicating = isPumpCommunicating,
                        onStopBolus = onStopBolus,
                        onStackedLayout = { overviewStacked = it },
                        // While the full bar is hidden the menu button stays, in the corner of the BG circle.
                        // A tap opens the menu and shows the bar again behind it (in preview mode all the bars):
                        // when the menu closes, search and settings are there, which reminds where they live.
                        // The menu still takes one tap. The bar comes after the menu is open: shown at the tap,
                        // it stopped the menu opening.
                        onCompactMenuClick = if (!topBarShown) {
                            {
                                scope.launch {
                                    drawerState.open()
                                    if (inPreviewMode) chromeVisible = true else topBarHidden = false
                                    onMenuClick()
                                }
                            }
                        } else null
                    )
                    }

                    // Search results overlay
                    if (searchUiState.isSearchActive) {
                        SearchResults(
                            results = searchUiState.results,
                            wikiResults = searchUiState.wikiResults,
                            isSearching = searchUiState.isSearching,
                            isSearchingWiki = searchUiState.isSearchingWiki,
                            wikiOffline = searchUiState.wikiOffline,
                            revision = searchUiState.revision,
                            onResultClick = onSearchResultClick,
                            onPluginToggle = onSearchPluginToggle,
                            query = searchUiState.query,
                            history = searchUiState.history,
                            onHistoryClick = onSearchHistoryClick,
                            onHistoryRemove = onSearchHistoryRemove,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentPadding)
                        )
                    }

                    // Plugin enable/disable confirmations raised from search results (same dialogs as Config Builder)
                    searchUiState.pluginSwitchConfirmation?.let { confirmation ->
                        OkCancelDialog(
                            title = stringResource(CoreUiStrings.configbuilder_switch_confirmation_title),
                            message = stringResource(
                                CoreUiStrings.configbuilder_switch_confirmation,
                                confirmation.fromName,
                                confirmation.toName
                            ),
                            onConfirm = onConfirmSearchPluginSwitch,
                            onDismiss = onDismissSearchPluginSwitch
                        )
                    }
                    searchUiState.hardwarePumpConfirmation?.let { confirmation ->
                        OkCancelDialog(
                            title = stringResource(CoreUiStrings.confirmation),
                            message = confirmation.message,
                            onConfirm = onConfirmSearchHardwarePump,
                            onDismiss = onDismissSearchHardwarePump
                        )
                    }

                    // Version overlay. Fixed to the screen, not to the scrolling content. With the top
                    // bar shown it is lifted into the bar's empty band under the search bar, so it sits
                    // above the profile chip instead of level with the chip's text. In preview mode the
                    // content starts right under the status bar, so there it stays at the content's edge.
                    // The one-column overview shows the version in its own strip, see OverviewScreenStacked
                    if (!overviewStacked) {
                        VersionOverlay(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(contentPadding)
                                .offset(y = if (showChrome) -VERSION_LABEL_LIFT else 0.dp)
                        )
                    }

                    // Status bar protection scrim — keeps system icons legible
                    // against the floating search bar / graph content
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.surface)
                    )

                    // Navigation bar protection scrim
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsBottomHeight(WindowInsets.navigationBars)
                            .background(MaterialTheme.colorScheme.surface)
                    )

                    // Top bar overlay
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically { -it },
                        exit = slideOutVertically { -it },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            // Clipped under the status bar: it slid over the clock while it went away
                            .clipToBounds()
                    ) {
                        MainTopBar(
                            searchUiState = searchUiState,
                            onMenuClick = {
                                scope.launch {
                                    drawerState.open()
                                    onMenuClick()
                                }
                            },
                            onPreferencesClick = { onNavigate(NavigationRequest.Element(ElementType.SETTINGS)) },
                            onSearchQueryChange = onSearchQueryChange,
                            onSearchClear = onSearchClear,
                            onSearchActiveChange = onSearchActiveChange,
                            isSimpleMode = uiState.isSimpleMode,
                            compact = landscapeNavigation,
                            // The current BG beside the search field, so it never scrolls away (2026-10-27)
                            bgInfo = topBarBgInfo.bgInfo,
                            // Guard against transient 0 heights during AnimatedVisibility exit:
                            // the resulting contentPadding invalidation can schedule a remeasure
                            // on a node that's losing its owner — crashes in dispatchDraw.
                            modifier = Modifier.onSizeChanged {
                                if (it.height > 0 && it.height != topBarHeightPx) topBarHeightPx = it.height
                            }
                        )
                    }

                    // The press-and-hold pill of the graphs, over the top bar while a finger is down (2026-10-27, per explicit
                    // request). The bar does not scroll, so the pill is always in view; it draws nothing while no graph is held.
                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = scaffoldPadding.calculateTopPadding())
                            // Beside the landscape rail, as the content is: the rail is drawn on top, and it hid the left of the
                            // stats summary (its ring) (2026-10-29)
                            .padding(start = if (landscapeNavigation) AapsSpacing.navigationRailWidth else 0.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // Logs where the pill is drawn and how big, once per change (2026-10-29). It showed that after the wide /
                        // split toggle the top bar never measured a pill again, see TopBarPillHost.current.
                        val windowSize = LocalWindowInfo.current.containerSize
                        var lastPillPlace by remember { mutableStateOf("") }
                        val summary = topBarPillHost.summaryContent
                        val graphs = topBarPillHost.current
                        // Steady pill during a hold (2026-11-16, per explicit request): it was as wide as its text and centred, and
                        // grew and shrank by a row, so it jumped sideways and up and down at every reading (iPhone log 16 Nov:
                        // 830 <-> 1075 px wide, 892 / 951 / 1010 px high, several times a second). Now it is as wide as the bar and
                        // keeps the tallest height of this hold until it closes.
                        val pillShown by remember(graphs) { derivedStateOf { graphs != null && graphs.inspect.pill != null } }
                        var holdMinHeightPx by remember { mutableIntStateOf(0) }
                        LaunchedEffect(pillShown) { if (!pillShown) holdMinHeightPx = 0 }
                        val holdMinHeight = with(LocalDensity.current) { holdMinHeightPx.toDp() }
                        Box(
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                if (graphs == null && summary == null) return@onGloballyPositioned
                                val bounds = coordinates.boundsInWindow()
                                val place = "at ${bounds.left.roundToInt()},${bounds.top.roundToInt()} size ${coordinates.size.width}x${coordinates.size.height}, " +
                                    "window ${windowSize.width}x${windowSize.height}"
                                if (place != lastPillPlace) {
                                    lastPillPlace = place
                                    graphViewModel.logInspect("Graph inspect: top bar pill $place")
                                }
                            }
                        ) {
                            when {
                                summary != null -> summary()
                                // A group of its own for each graph section, so new graphs never reuse the old graphs' pill
                                graphs != null  -> key(graphs) {
                                    GraphInspectPillOf(
                                        inspect = graphs.inspect,
                                        graphViewModel = graphs.graphViewModel,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = holdMinHeight)
                                            .onSizeChanged { if (it.height > holdMinHeightPx) holdMinHeightPx = it.height }
                                    )
                                }
                            }
                        }
                    }

                    // The bar's buttons. [beforeAction] lets the BG-tap sheet close itself before a button acts.
                    @Composable
                    fun NavigationItems(modifier: Modifier, vertical: Boolean, beforeAction: () -> Unit = {}) {
                            MainNavigationBar(
                                vertical = vertical,
                                onManageClick = {
                                    beforeAction()
                                    manageSheetState.show()
                                },
                                onTreatmentClick = {
                                    beforeAction()
                                    treatmentViewModel.refreshState()
                                    showTreatmentSheet = true
                                },
                                masterOrPairedClient = masterOrPairedClient,
                                onAutomationClick = {
                                    beforeAction()
                                    scenesViewModel.refreshState()
                                    showAutomationSheet = true
                                },
                                // Total drives nav-button visibility (button stays visible whenever
                                // scenes/automation exist, even if currently un-activatable).
                                automationTotal = automationState.items.size + automationState.sceneItems.size,
                                pumpSetupPlugin = pumpSetupPlugin,
                                bgSetupPlugin = bgSetupPlugin,
                                bgQualityBadgeIcon = bgQualityBadgeIcon,
                                bgQualityBadgeTint = bgQualityBadgeTint,
                                bgQualityBadgeDescription = bgQualityBadgeDescription,
                                objectivesSetupPlugin = objectivesSetupPlugin,
                                objectivesProgressText = objectivesProgressText,
                                objectivesProgressDescription = objectivesProgressDescription,
                                objectivesBypassed = objectivesBypassed,
                                onNavigate = { request ->
                                    beforeAction()
                                    onNavigate(request)
                                },
                                permissionsMissing = permissionsMissing,
                                onPermissionsClick = {
                                    beforeAction()
                                    onPermissionsClick()
                                },
                                loopActionAvailable = loopActionState.actionAvailable,
                                onLoopActionClick = {
                                    beforeAction()
                                    showLoopActionSheet = true
                                },
                                modifier = modifier
                            )
                    }

                    // Landscape uses a rail with space reserved beside the graphs. Portrait shows the bottom bar only while
                    // the setting asks for it or something needs attention (see bottomBarShown).
                    AnimatedVisibility(
                        visible = landscapeNavigation || (showChrome && bottomBarShown),
                        enter = if (landscapeNavigation) slideInHorizontally { -it } else slideInVertically { it },
                        exit = if (landscapeNavigation) slideOutHorizontally { -it } else slideOutVertically { it },
                        modifier = Modifier
                            .align(if (landscapeNavigation) Alignment.CenterStart else Alignment.BottomCenter)
                            .then(
                                if (landscapeNavigation) Modifier
                                    .fillMaxHeight()
                                    .padding(
                                        start = scaffoldPadding.calculateStartPadding(layoutDirection),
                                        top = topBarHeight,
                                        bottom = scaffoldPadding.calculateBottomPadding()
                                    )
                                else Modifier.padding(bottom = scaffoldPadding.calculateBottomPadding())
                            )
                    ) {
                        if (landscapeNavigation) {
                            Column(modifier = Modifier.fillMaxHeight().width(AapsSpacing.navigationRailWidth)) {
                                NavigationItems(
                                    modifier = Modifier.weight(1f),
                                    vertical = true
                                )
                                if (hasToolbar) {
                                    QuickLaunchToolbar(
                                        items = visibleQuickLaunchItems,
                                        onActionClick = handleQuickLaunch,
                                        vertical = true,
                                        large = quickLaunchLarge
                                    )
                                }
                            }
                        } else {
                            NavigationItems(
                                modifier = Modifier.onSizeChanged {
                                    if (it.height > 0 && it.height != bottomBarHeightPx) bottomBarHeightPx = it.height
                                },
                                vertical = false
                            )
                        }
                    }

                    // Tap on the big BG circle: the bar's buttons, badges included, in a sheet. Each closes the sheet first.
                    if (showNavigationSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showNavigationSheet = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            NavigationItems(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                vertical = false,
                                beforeAction = { showNavigationSheet = false }
                            )
                        }
                    }

                    // Quick launch stays on the bottom in portrait only. Landscape puts it in the rail.
                    // With the bottom bar hidden, bottomBarHeight is 0 and it moves down into the bar's place.
                    // 2026-11-27, per explicit request: once dragged by its grip, the bar sits where it was left (kept on screen)
                    // 2026-11-27, per explicit request: and inside the safe area, clear of Android's navigation bar or gesture strip,
                    // the status bar and any cutout, where touches go to the system and the grip could not be taken again. A bar saved
                    // outside it is pulled back in at the next layout.
                    val quickLaunchDensity = LocalDensity.current
                    val quickLaunchDirection = LocalLayoutDirection.current
                    val qlSafe = WindowInsets.safeDrawing
                    val qlInsetLeft = qlSafe.getLeft(quickLaunchDensity, quickLaunchDirection).toFloat()
                    val qlInsetRight = qlSafe.getRight(quickLaunchDensity, quickLaunchDirection).toFloat()
                    val qlInsetTop = qlSafe.getTop(quickLaunchDensity).toFloat()
                    val qlInsetBottom = qlSafe.getBottom(quickLaunchDensity).toFloat()
                    fun clampQuickLaunch(p: Offset): Offset {
                        val maxX = (quickLaunchAreaSize.width - quickLaunchBarSize.width - qlInsetRight).coerceAtLeast(qlInsetLeft)
                        val maxY = (quickLaunchAreaSize.height - quickLaunchBarSize.height - qlInsetBottom).coerceAtLeast(qlInsetTop)
                        return Offset(p.x.coerceIn(qlInsetLeft, maxX), p.y.coerceIn(qlInsetTop, maxY))
                    }
                    val quickLaunchSaved = if (quickLaunchSavedX >= 0 && quickLaunchSavedY >= 0)
                        Offset(quickLaunchSavedX.toFloat(), quickLaunchSavedY.toFloat()) else null
                    val quickLaunchAt = (quickLaunchDragPos ?: quickLaunchSaved)?.let { clampQuickLaunch(it) }
                    // 2026-11-27, per explicit request: left touching the left or right edge, the bar turns vertical and stays on that
                    // side (a right-side bar is saved as QUICK_LAUNCH_RIGHT_EDGE, which the clamp puts at the edge whatever its width)
                    val quickLaunchEdgePx = with(LocalDensity.current) { QUICK_LAUNCH_EDGE_SNAP.toPx() }
                    fun quickLaunchSide(x: Float): Int = when {
                        quickLaunchAreaSize.width <= 0 || quickLaunchBarSize.width <= 0               -> 0
                        x <= qlInsetLeft + quickLaunchEdgePx                                                         -> -1
                        x >= quickLaunchAreaSize.width - quickLaunchBarSize.width - qlInsetRight - quickLaunchEdgePx -> 1
                        else                                                                           -> 0
                    }
                    fun snapQuickLaunchToSide(side: Int, y: Float) {
                        val x = if (side < 0) 0 else QUICK_LAUNCH_RIGHT_EDGE
                        quickLaunchDragPos = Offset(x.toFloat(), y)
                        mainViewModel.preferences.put(IntNonKey.QuickLaunchX, x)
                        mainViewModel.preferences.put(IntNonKey.QuickLaunchY, y.roundToInt())
                        mainViewModel.preferences.put(BooleanNonKey.QuickLaunchVertical, true)
                    }
                    // At startup: a horizontal bar saved against a side edge (also one saved before this rule) turns vertical once
                    LaunchedEffect(quickLaunchSavedX, quickLaunchSavedY, quickLaunchVertical, quickLaunchAreaSize, quickLaunchBarSize) {
                        if (quickLaunchVertical || quickLaunchSaved == null || quickLaunchDragPos != null) return@LaunchedEffect
                        val side = quickLaunchSide(clampQuickLaunch(quickLaunchSaved).x)
                        if (side != 0) snapQuickLaunchToSide(side, quickLaunchSaved.y)
                    }
                    AnimatedVisibility(
                        visible = hasToolbar && showChrome && !landscapeNavigation,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                        modifier = (if (quickLaunchAt != null) Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(quickLaunchAt.x.roundToInt(), quickLaunchAt.y.roundToInt()) }
                        else Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = scaffoldPadding.calculateBottomPadding() + bottomBarHeight + 8.dp
                            ))
                            .onGloballyPositioned { coords ->
                                quickLaunchPlacedAt = coords.positionInParent()
                                quickLaunchBarSize = coords.size
                                coords.parentLayoutCoordinates?.size?.let { quickLaunchAreaSize = it }
                            }
                    ) {
                        QuickLaunchToolbar(
                            items = visibleQuickLaunchItems,
                            onActionClick = handleQuickLaunch,
                            vertical = quickLaunchVertical,
                            large = quickLaunchLarge,
                            onGripDrag = { amount ->
                                // From where the bar really is (a right-edge mark is far beyond the screen until clamped)
                                quickLaunchDragPos = clampQuickLaunch((quickLaunchDragPos?.let { clampQuickLaunch(it) } ?: quickLaunchAt ?: quickLaunchPlacedAt) + amount)
                            },
                            onGripDragEnd = {
                                quickLaunchDragPos?.let {
                                    val side = quickLaunchSide(it.x)
                                    if (side != 0) snapQuickLaunchToSide(side, it.y)
                                    else {
                                        mainViewModel.preferences.put(IntNonKey.QuickLaunchX, it.x.roundToInt())
                                        mainViewModel.preferences.put(IntNonKey.QuickLaunchY, it.y.roundToInt())
                                        // 2026-11-28, per explicit request: let go away from both sides, a vertical bar turns
                                        // horizontal again (double-tap still turns it either way until the next drag)
                                        if (quickLaunchVertical) mainViewModel.preferences.put(BooleanNonKey.QuickLaunchVertical, false)
                                    }
                                }
                            },
                            onGripDoubleTap = {
                                // Turning it keeps the top-left corner where it is
                                if (quickLaunchAt == null) {
                                    mainViewModel.preferences.put(IntNonKey.QuickLaunchX, quickLaunchPlacedAt.x.roundToInt())
                                    mainViewModel.preferences.put(IntNonKey.QuickLaunchY, quickLaunchPlacedAt.y.roundToInt())
                                }
                                mainViewModel.preferences.put(BooleanNonKey.QuickLaunchVertical, !quickLaunchVertical)
                            },
                        )
                    }

                    // Tap overlay to restore chrome in preview mode (only when hidden)
                    if (previewMode && !chromeVisible && !landscapeNavigation) {
                        // Named: the top bar, menu and toolbar come back only through this, and a
                        // screen reader announced it as a nameless control.
                        val showControls = stringResource(UiStrings.a11y_show_controls)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { chromeVisible = true }
                                .semantics { contentDescription = showControls }
                        )
                    }
                }
            }
        }
    }

    // Treatment bottom sheet
    if (showTreatmentSheet) {
        val treatmentState by treatmentViewModel.uiState.collectAsStateWithLifecycle()
        val trioSetup = remember { mainViewModel.trioSetup() }
        TreatmentBottomSheet(
            onDismiss = { showTreatmentSheet = false },
            showCgm = treatmentState.showCgm,
            showCalibration = treatmentState.showCalibration,
            showTreatment = treatmentState.showTreatment,
            showInsulin = treatmentState.showInsulin,
            showCarbs = treatmentState.showCarbs,
            showCalculator = treatmentState.showCalculator,
            cgmIcon = treatmentState.cgmIcon,
            showSettingsIcon = treatmentState.showSettingsIcon,
            quickWizardItems = treatmentState.quickWizardItems,
            onNavigate = onNavigate,
            treatmentButtonsDef = treatmentButtonsDef,
            // Shown while Trio remote control is on; greyed out with what is missing until it is complete (2026-11-07)
            onQuickWizardTrio = if (trioSetup.enabled) mainViewModel::sendQuickWizardToTrio else null,
            quickWizardTrioMissing = if (trioSetup.enabled) trioSetup.missingTexts() else emptyList(),
        )
    }

    // Automation bottom sheet
    if (showAutomationSheet) {
        ScenesBottomSheet(
            onDismiss = { showAutomationSheet = false },
            automationItems = automationState.items,
            onItemClick = { item -> mainViewModel.requestAutomationConfirmation(item.eventId) },
            sceneItems = automationState.sceneItems,
            onSceneClick = { sceneId -> mainViewModel.requestSceneConfirmation(sceneId) },
            onEditScenesClick = { onNavigate(NavigationRequest.Element(ElementType.SCENE_MANAGEMENT)) }
        )
    }

    // Loop accept action bottom sheet
    if (showLoopActionSheet) {
        val loopState by loopActionViewModel.uiState.collectAsStateWithLifecycle()
        app.aaps.ui.compose.loopSheet.LoopActionBottomSheet(
            state = loopState,
            onPerform = { mainViewModel.performLoopAccept() },
            onDismiss = { showLoopActionSheet = false }
        )
    }

    // Shared confirmation dialog (automation actions, TT presets, scene end — from toolbar or
    // bottom sheets). When the confirmation carries a secondary action (e.g., scene chain skip),
    // render a 3-button dialog; otherwise the standard 2-button OK/Cancel.
    val actionConfirmation by mainViewModel.actionConfirmation.collectAsStateWithLifecycle()
    actionConfirmation?.let { confirmation ->
        val secondaryAction = confirmation.secondaryAction
        val secondaryLabel = confirmation.secondaryLabel
        if (secondaryAction != null && secondaryLabel != null) {
            ThreeButtonDialog(
                title = confirmation.title,
                message = confirmation.message,
                icon = confirmation.icon,
                primaryLabel = confirmation.confirmLabel ?: stringResource(CoreUiStrings.ok),
                onPrimary = { mainViewModel.executeConfirmableAction(confirmation.onConfirmAction) },
                secondaryLabel = secondaryLabel,
                onSecondary = { mainViewModel.executeConfirmableAction(secondaryAction) },
                onDismiss = { mainViewModel.dismissActionConfirmation() }
            )
        } else {
            OkCancelDialog(
                title = confirmation.title,
                message = confirmation.message,
                icon = confirmation.icon,
                onConfirm = { mainViewModel.executeConfirmableAction(confirmation.onConfirmAction) },
                onDismiss = { mainViewModel.dismissActionConfirmation() }
            )
        }
    }

    // Maintenance dialogs (sheets, confirmations, export chain)
    MaintenanceDialogs(
        maintenanceViewModel = maintenanceViewModel,
        showMaintenanceSheet = uiState.showMaintenanceSheet,
        onMaintenanceSheetDismiss = onMaintenanceSheetDismiss,
        onDirectoryClick = onDirectoryClick,
        onAddArchiveFolder = onAddArchiveFolder,
        onImportSettingsNavigate = onImportSettingsNavigate,
        onRecreateActivity = onRecreateActivity,
        onLaunchBrowser = onLaunchBrowser,
        onBringToForeground = onBringToForeground,
        onSnackbar = { snackbarHostState.showSnackbar(it) }
    )

    // About dialog
    if (uiState.showAboutDialog && aboutDialogData != null) {
        AboutAlertDialog(
            data = aboutDialogData,
            onDismiss = onAboutDialogDismiss,
            onOpenBatteryHelp = onOpenBatteryHelp
        )
    }
}

private val PREVIEW_MODE_MIN_HEIGHT: Dp = 500.dp

/** A quick-launch bar let go this close to the left or right edge turns vertical on that side (2026-11-27, per explicit request) */
private val QUICK_LAUNCH_EDGE_SNAP: Dp = 8.dp

/** Saved x of a bar on the right edge: far past any screen, so the clamp puts it at the edge whatever its width */
private const val QUICK_LAUNCH_RIGHT_EDGE = 100_000
private const val AUTO_HIDE_DELAY_MS = 3000L

/** How long the top bar shows at the start of the app before it hides, see topBarHidden. */
private const val TOP_BAR_PEEK_MS = 1500L

/** A scroll step smaller than this does not switch the top bar, so a jitter does not flicker it. */
private const val TOP_BAR_SCROLL_THRESHOLD_PX = 4f

/**
 * How far the version label is lifted above the content's top edge while the top bar is shown. The
 * bar has 8 dp of nothing under its search bar and the first chip starts a few dp into the content;
 * this centers the 12 dp label between the two.
 */
private val VERSION_LABEL_LIFT: Dp = 6.dp
