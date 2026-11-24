package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.ui.UiStrings
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.LocalReadOnlyClient
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobCobChipsRow
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.chips.ProfileChip
import app.aaps.ui.compose.overview.chips.RunningModeChip
import app.aaps.ui.compose.overview.chips.SensitivityUiState
import app.aaps.ui.compose.overview.chips.TbrChip
import app.aaps.ui.compose.overview.chips.TempTargetChip

@Composable
fun OverviewChipsColumn(
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeSceneManaged: Boolean = false,
    smbEnabled: Boolean = false,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean = false,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean = false,
    tbrState: TbrState,
    tbrRate: Double? = null,
    iobUiState: IobUiState,
    cobUiState: CobUiState,
    sensitivityUiState: SensitivityUiState,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onTbrChipLongClick: () -> Unit = {},
    onTbrChipDoubleClick: () -> Unit = {},
    onIobChipClick: () -> Unit,
    onIobChipLongClick: () -> Unit = {},
    onIobChipDoubleClick: () -> Unit = {},
    onSensitivityChipDoubleClick: () -> Unit = {},
    // Running mode needs a paired client. Profile and v3-only TT management can also open without pairing.
    commandsAllowed: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // The iOS heartbeat status is shown in the graphs' press-and-hold pill since 2026-10-28, see GraphInspectPillOf
        NarrowChips(
            runningMode = runningMode,
            runningModeText = runningModeText,
            runningModeRemaining = runningModeRemaining,
            runningModeProgress = runningModeProgress,
            runningModeSceneManaged = runningModeSceneManaged,
            smbEnabled = smbEnabled,
            profileName = profileName,
            isProfileModified = isProfileModified,
            profileProgress = profileProgress,
            profileSceneManaged = profileSceneManaged,
            tempTargetText = tempTargetText,
            tempTargetState = tempTargetState,
            tempTargetProgress = tempTargetProgress,
            tempTargetReason = tempTargetReason,
            tempTargetSceneManaged = tempTargetSceneManaged,
            tbrState = tbrState,
            tbrRate = tbrRate,
            onNavigate = onNavigate,
            onTbrChipClick = onTbrChipClick,
            onTbrChipLongClick = onTbrChipLongClick,
            onTbrChipDoubleClick = onTbrChipDoubleClick,
            commandsAllowed = commandsAllowed
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IobCobChipsRow(
                iobUiState = iobUiState,
                cobUiState = cobUiState,
                onIobChipClick = onIobChipClick,
                onIobChipLongClick = onIobChipLongClick,
                onIobChipDoubleClick = onIobChipDoubleClick,
                modifier = Modifier.weight(1f)
            )
        }
        // Simple / Detailed graphs (2026-11-21, per explicit request): a small S or D at the end of the sensitivity row (it was
        // next to AIV on the client card, where it made that row wrap). A tap switches; this device only.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SensitivityChipBlock(
                state = sensitivityUiState,
                onDoubleClick = onSensitivityChipDoubleClick,
                modifier = Modifier.weight(1f)
            )
            SimpleGraphsSwitch()
        }
    }
}

@Composable
internal fun OverviewGlucoseCircle(
    graphViewModel: GraphViewModel,
    showTimeAgo: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val info by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val autoIsf by graphViewModel.autoIsfGraphFlow.collectAsStateWithLifecycle()
    val basal by graphViewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val now by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val basalRate = basal.actualBasal.lastOrNull { it.timestamp <= now }?.value
    val basalText = basalRate?.let { LocalDecimalFormatter.current.to2Decimal(it) }
    val longDelta = autoIsf.statusLongDelta ?: info.bgInfo?.longAvgDeltaText

    Box {
        BgInfoSection(bgInfo = info.bgInfo, timeAgoText = info.timeAgoText, showTimeAgo = showTimeAgo, onClick = onClick)
        GlucoseMetricChip(basalText, "glucose_chip_basal", Modifier.align(Alignment.TopStart))
        GlucoseMetricChip(longDelta, "glucose_chip_long_delta", Modifier.align(Alignment.TopEnd))
        GlucoseMetricChip(autoIsf.statusAcceleration, "glucose_chip_acceleration", Modifier.align(Alignment.BottomStart))
        GraphScaleLookbackChip(Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun GlucoseMetricChip(value: String?, descriptionKey: String, modifier: Modifier) {
    if (value.isNullOrBlank()) return
    val description = stringResource(TextRef.Named("ui", descriptionKey), value)
    GlucoseChipTooltip(description, modifier) { showTooltip ->
        // 2026-11-25, per explicit request: a tap shows the description too (it took a press and hold before)
        Surface(
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = ElementType.SENSITIVITY.color().copy(alpha = 0.2f),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = showTooltip)
                .semantics { contentDescription = description }
        ) {
            Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun GraphScaleLookbackChip(modifier: Modifier) {
    if (LocalInspectionMode.current) return
    val preferences = LocalPreferences.current
    val hours by preferences.observe(IntNonKey.GraphScaleLookbackHours).collectAsStateWithLifecycle()
    val description = stringResource(UiStrings.graph_scale_lookback_description)
    GlucoseChipTooltip(description, modifier) { _ ->
        Surface(
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = ElementType.SENSITIVITY.color().copy(alpha = 0.2f),
            modifier = Modifier
                .clickable(role = Role.Button) {
                    val next = when (preferences.get(IntNonKey.GraphScaleLookbackHours)) {
                        9 -> 6
                        6 -> 3
                        3 -> 1
                        else -> 9
                    }
                    preferences.put(IntNonKey.GraphScaleLookbackHours, next)
                }
                .semantics { contentDescription = description }
        ) {
            Text(
                text = hours.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlucoseChipTooltip(description: String, modifier: Modifier, content: @Composable (showTooltip: () -> Unit) -> Unit) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(description) } },
            state = tooltipState,
            content = { content { scope.launch { tooltipState.show() } } }
        )
    }
}

/**
 * The mode a tap switches to (2026-11-21, per explicit request; it showed the current mode before): "D" while the graphs are
 * Simple, "S" while they are Detailed. Screen readers hear "Switch to detailed graphs" or "Switch to simple graphs".
 */
@Composable
private fun SimpleGraphsSwitch() {
    if (LocalInspectionMode.current) return
    val preferences = LocalPreferences.current
    val simpleGraphs by preferences.observe(BooleanNonKey.ApsAutoIsfSimpleGraphs).collectAsStateWithLifecycle()
    val name = stringResource(if (simpleGraphs) UiStrings.graph_mode_detailed else UiStrings.graph_mode_simple)
    Text(
        text = stringResource(if (simpleGraphs) UiStrings.graph_mode_detailed_short else UiStrings.graph_mode_simple_short),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(role = Role.Button) { preferences.put(BooleanNonKey.ApsAutoIsfSimpleGraphs, !simpleGraphs) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = name }
    )
}

@Composable
private fun NarrowChips(
    runningMode: RM.Mode?,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeSceneManaged: Boolean,
    smbEnabled: Boolean,
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean,
    tbrState: TbrState,
    tbrRate: Double? = null,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onTbrChipLongClick: () -> Unit = {},
    onTbrChipDoubleClick: () -> Unit = {},
    commandsAllowed: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (runningModeText.isNotEmpty()) {
            RunningModeChip(
                mode = runningMode,
                text = runningModeText,
                progress = runningModeProgress,
                remaining = runningModeRemaining,
                sceneManaged = runningModeSceneManaged,
                smbEnabled = smbEnabled,
                enabled = commandsAllowed && LocalClientRole.current.allows(ElementType.RUNNING_MODE.role),
                onClick = { onNavigate(NavigationRequest.Element(ElementType.RUNNING_MODE)) }
            )
        }
        ProfileChip(
            profileName = profileName.ifEmpty { stringResource(CoreUiStrings.no_profile_set) },
            isModified = isProfileModified,
            progress = profileProgress,
            onClick = { onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)) },
            sceneManaged = profileSceneManaged,
            isNoProfile = profileName.isEmpty(),
            // Stays clickable even on an unpaired client: profile management opens read-only there,
            // so this is a view affordance, not a command one (unlike the running-mode / TT chips).
            modifier = Modifier.weight(1f)
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        if (tempTargetText.isNotEmpty()) {
            TempTargetChip(
                targetText = tempTargetText,
                state = tempTargetState,
                progress = tempTargetProgress,
                reason = tempTargetReason,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(NavigationRequest.Element(ElementType.TEMP_TARGET_MANAGEMENT)) },
                sceneManaged = tempTargetSceneManaged,
                enabled = (commandsAllowed || LocalReadOnlyClient.current) &&
                    LocalClientRole.current.allows(ElementType.TEMP_TARGET_MANAGEMENT.role)
            )
        }
        // The "Client2-v4" label moved to the fixed corner line (VersionOverlay) on 2026-10-21, as in 3426.
        TbrChip(
            state = tbrState,
            rate = tbrRate,
            onClick = onTbrChipClick,
            onLongClick = onTbrChipLongClick,
            onDoubleClick = onTbrChipDoubleClick
        )
    }
}

