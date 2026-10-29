package app.aaps.ui.compose.overview.aapsClient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.AapsClientStatusData
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.rememberBringIntoViewOnExpand
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * @see AapsClientStatusCardCollapsedPreview
 * @see AapsClientStatusCardMixedLevelsPreview
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AapsClientStatusCard(
    statusData: AapsClientStatusData,
    flavorTint: Color,
    modifier: Modifier = Modifier,
    onOpenList1: () -> Unit = {},
    onOpenList2: () -> Unit = {},
    onOpenList3: () -> Unit = {},
    onOpenAutoIsfHistory: () -> Unit = {},
    onOpenStats: () -> Unit = {}
) {
    val items = listOfNotNull(statusData.pump, statusData.openAps, statusData.autoIsf, statusData.uploader)
    if (items.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandRequester = rememberBringIntoViewOnExpand(expanded)

    AapsCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .bringIntoViewRequester(expandRequester)
    ) {
        Column {
            // Header row — compact chips + expand/collapse toggle. Padded like the status section's row
            // (around the whole row, so the chevron counts), which makes the two cards the same height.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(flavorTint)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = stringResource(UiStrings.client_settings1_chip),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onOpenList1)
                    )
                    Text(
                        text = stringResource(UiStrings.client_aiv_chip),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onOpenAutoIsfHistory)
                    )
                    statusData.openAps?.let { item ->
                        AapsClientStatusChip(item = item)
                    }
                    // A round yellow mark instead of "ST" (2026-10-29, per explicit request): the glucose statistics, like LoopFollow's ring
                    val statsLabel = stringResource(CoreUiStrings.statistics)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .size(AapsSpacing.large)
                            .clip(CircleShape)
                            .background(AapsTheme.generalColors.bgHigh)
                            .clickable(onClickLabel = statsLabel, role = Role.Button, onClick = onOpenStats)
                            .semantics { contentDescription = statsLabel }
                    )
                    // Direct actions are all AutoISF codes: not offered when the master runs AMA or SMB
                    if (statusData.autoIsfLists)
                        Text(
                            text = stringResource(UiStrings.client_settings2_chip),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable(onClick = onOpenList2)
                        )
                    Text(
                        text = stringResource(UiStrings.client_settings3_chip),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onOpenList3)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) CoreUiStrings.collapse else CoreUiStrings.expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }

            // Expanded: detail rows
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        AapsClientDetailRow(item = item)
                    }
                }
            }
        }
    }
}
