package app.aaps.ui.compose.scenes.wizard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.aaps.core.data.model.SceneAction
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.scenes.AutoIsfSettingsEditor
import app.aaps.ui.compose.scenes.SmbToggleEditor

/**
 * @see SmbStepPreview
 */
@Composable
internal fun SmbStep(
    state: SceneWizardViewModel.WizardState,
    onToggle: (Boolean) -> Unit,
    onUpdate: (SceneAction) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onAutoIsfToggle: (Boolean) -> Unit = {},
    onAutoIsfUpdate: (SceneAction) -> Unit = {},
    showAutoIsf: Boolean = false
) {
    WizardStepLayout(
        secondaryButton = WizardButton(text = stringResource(CoreUiStrings.back), onClick = onBack),
        primaryButton = WizardButton(text = stringResource(CoreUiStrings.next), onClick = onNext)
    ) {
        Text(
            text = stringResource(CoreUiStrings.smb_shortname),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(CoreUiStrings.scene_wizard_smb_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ActionToggle(
            label = stringResource(CoreUiStrings.scene_wizard_include_action, stringResource(CoreUiStrings.smb_shortname)),
            checked = state.smbEnabled,
            onCheckedChange = onToggle
        )
        AnimatedVisibility(
            visible = state.smbEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                SmbToggleEditor(
                    action = state.smbAction,
                    onUpdate = onUpdate
                )
            }
        }
        // AutoISF fork: AutoISF settings for the time of the scene, on the same page as SMB. Only offered
        // while AutoISF is the active algorithm; a scene that has them keeps them when it is edited.
        if (showAutoIsf) {
            Text(
                text = stringResource(CoreUiStrings.scene_wizard_autoisf_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionToggle(
                label = stringResource(CoreUiStrings.scene_wizard_include_action, stringResource(CoreUiStrings.scene_wizard_autoisf_title)),
                checked = state.autoIsfEnabled,
                onCheckedChange = onAutoIsfToggle
            )
            AnimatedVisibility(
                visible = state.autoIsfEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    AutoIsfSettingsEditor(
                        action = state.autoIsfAction,
                        onUpdate = onAutoIsfUpdate
                    )
                }
            }
        }
    }
}
