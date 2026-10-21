package app.aaps.ui.compose.wizardDialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.objects.wizard.CarbsType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcBread
import app.aaps.core.ui.compose.icons.IcCake
import app.aaps.core.ui.compose.icons.IcPizza
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlinx.coroutines.launch

/**
 * Bread / cake / pizza buttons with the info tooltip. Shared by the Bolus Wizard and the QuickWizard editor
 * (2026-10-22), so both show the same choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarbsTypeSelector(selected: CarbsType, onChange: (CarbsType) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.weight(1f)
        ) {
            CarbsType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = selected == type,
                    onClick = { onChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index, CarbsType.entries.size),
                    icon = {}
                ) {
                    Icon(
                        imageVector = when (type) {
                            CarbsType.BREAD -> IcBread
                            CarbsType.CAKE  -> IcCake
                            CarbsType.PIZZA -> IcPizza
                        },
                        // Was the enum name ("BREAD"), untranslated and saying
                        // nothing about what the choice does to the carbs.
                        contentDescription = carbsTypeEffect(type),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        val tooltipState = rememberTooltipState()
        val scope = rememberCoroutineScope()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip {
                    Column {
                        Text(
                            text = when (selected) {
                                CarbsType.BREAD -> stringResource(CoreUiStrings.carbs_type_bread)
                                CarbsType.CAKE  -> stringResource(CoreUiStrings.carbs_type_cake)
                                CarbsType.PIZZA -> stringResource(CoreUiStrings.carbs_type_pizza)
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = carbsTypeEffect(selected),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            state = tooltipState
        ) {
            IconButton(onClick = { scope.launch { tooltipState.show() } }) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(CoreUiStrings.carousel_show_card),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** What a carbs type does to the entered carbs, said on its button and shown in the info tooltip. */
@Composable
internal fun carbsTypeEffect(type: CarbsType): String =
    if (type == CarbsType.BREAD) stringResource(UiStrings.wizard_carbs_type_bread_desc)
    else stringResource(
        UiStrings.wizard_carbs_type_desc,
        100 - type.carbsPercent,
        type.eCarbsPercent,
        type.eCarbsDelayMinutes,
        type.eCarbsDurationHours
    )
