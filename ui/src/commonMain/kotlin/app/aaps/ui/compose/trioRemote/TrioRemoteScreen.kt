package app.aaps.ui.compose.trioRemote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.trioRemote.TrioRemoteViewModel.Problem
import kotlin.math.round

/**
 * Commands to a Trio phone (2026-10-29, per explicit request), laid out like LoopFollow's remote: one card per command,
 * each with a confirmation before anything is sent.
 *
 * @param requestBolusProtection asks for the bolus protection and runs its argument when it is granted
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrioRemoteScreen(
    viewModel: TrioRemoteViewModel,
    onNavigateBack: () -> Unit,
    requestBolusProtection: (onGranted: () -> Unit) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(CoreUiStrings.trio_remote)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clearFocusOnTap(focusManager)
                .verticalScroll(rememberScrollState())
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraLarge)
        ) {
            SetupCard(state.setup)
            if (state.setup.ready) {
                state.problem?.let { ProblemText(it, viewModel) }
                state.result?.let { ResultText(it, viewModel) }
                if (state.sending) Text(stringResource(UiStrings.trio_remote_sending))
                TempTargetCard(state, viewModel)
                MealCard(state, viewModel)
                BolusCard(state, viewModel)
                OverrideCard(state, viewModel)
            }
        }
    }

    state.confirming?.let { command ->
        OkCancelDialog(
            title = stringResource(UiStrings.trio_remote_confirm_title),
            message = commandText(command, viewModel),
            secondMessage = stringResource(UiStrings.trio_remote_confirm_note),
            onConfirm = {
                viewModel.dismissConfirmation()
                // A bolus also needs the bolus protection, as every bolus in AAPS does
                if (command is TrioRemoteCommand.Bolus) requestBolusProtection { viewModel.send(command) }
                else viewModel.send(command)
            },
            onDismiss = viewModel::dismissConfirmation
        )
    }
}

@Composable
private fun SetupCard(setup: TrioRemoteSetup) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
            if (!setup.enabled) {
                Text(stringResource(UiStrings.trio_remote_off))
                return@Column
            }
            setup.trio?.let { trio ->
                Text(stringResource(UiStrings.trio_remote_trio_app, trio.bundleId))
                Text(stringResource(if (trio.production) UiStrings.trio_remote_server_production else UiStrings.trio_remote_server_development))
            }
            if (setup.missing.isNotEmpty()) {
                Text(stringResource(UiStrings.trio_remote_missing_title), color = MaterialTheme.colorScheme.error)
                setup.missing.forEach { missing ->
                    Text(
                        stringResource(
                            when (missing) {
                                TrioRemoteSetup.Missing.USER          -> UiStrings.trio_remote_missing_user
                                TrioRemoteSetup.Missing.SHARED_SECRET -> UiStrings.trio_remote_missing_shared_secret
                                TrioRemoteSetup.Missing.KEY_ID        -> UiStrings.trio_remote_missing_key_id
                                TrioRemoteSetup.Missing.APNS_KEY      -> UiStrings.trio_remote_missing_apns_key
                                TrioRemoteSetup.Missing.TRIO_PROFILE  -> UiStrings.trio_remote_missing_trio_profile
                                TrioRemoteSetup.Missing.TEAM_ID       -> UiStrings.trio_remote_missing_team_id
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandCard(title: String, content: @Composable () -> Unit) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
private fun TempTargetCard(state: TrioRemoteUiState, viewModel: TrioRemoteViewModel) {
    CommandCard(stringResource(UiStrings.trio_remote_temp_target)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            NumberField(state.target, viewModel::onTargetChange, stringResource(UiStrings.trio_remote_target, state.units.displayLabel), Modifier.weight(1f))
            NumberField(state.duration, viewModel::onDurationChange, stringResource(UiStrings.trio_remote_duration), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::askTempTarget, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_send)) }
            OutlinedButton(onClick = viewModel::askCancelTempTarget, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_cancel_temp_target)) }
        }
    }
}

@Composable
private fun MealCard(state: TrioRemoteUiState, viewModel: TrioRemoteViewModel) {
    CommandCard(stringResource(UiStrings.trio_remote_meal)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            NumberField(state.carbs, viewModel::onCarbsChange, stringResource(UiStrings.trio_remote_carbs), Modifier.weight(1f))
            NumberField(state.fat, viewModel::onFatChange, stringResource(UiStrings.trio_remote_fat), Modifier.weight(1f))
            NumberField(state.protein, viewModel::onProteinChange, stringResource(UiStrings.trio_remote_protein), Modifier.weight(1f))
        }
        NumberField(state.minutesAgo, viewModel::onMinutesAgoChange, stringResource(UiStrings.trio_remote_minutes_ago), Modifier.fillMaxWidth())
        Button(onClick = viewModel::askMeal, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_send)) }
    }
}

@Composable
private fun BolusCard(state: TrioRemoteUiState, viewModel: TrioRemoteViewModel) {
    CommandCard(stringResource(UiStrings.trio_remote_bolus)) {
        NumberField(state.bolus, viewModel::onBolusChange, stringResource(UiStrings.trio_remote_insulin), Modifier.fillMaxWidth())
        Button(onClick = viewModel::askBolus, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_send)) }
    }
}

@Composable
private fun OverrideCard(state: TrioRemoteUiState, viewModel: TrioRemoteViewModel) {
    CommandCard(stringResource(UiStrings.trio_remote_override)) {
        if (state.setup.overridePresets.isEmpty()) Text(stringResource(UiStrings.trio_remote_no_presets))
        state.setup.overridePresets.forEach { preset ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(preset.name, modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.askStartOverride(preset.name) }, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_start)) }
            }
        }
        OutlinedButton(onClick = viewModel::askCancelOverride, enabled = !state.sending) { Text(stringResource(UiStrings.trio_remote_cancel_override)) }
    }
}

@Composable
private fun ProblemText(problem: Problem, viewModel: TrioRemoteViewModel) {
    val text = when (problem) {
        Problem.TARGET      -> stringResource(
            UiStrings.trio_remote_problem_target,
            viewModel.targetText(HardLimits.LIMIT_TEMP_TARGET_BG.start),
            viewModel.targetText(HardLimits.LIMIT_TEMP_TARGET_BG.endInclusive)
        )

        Problem.DURATION    -> stringResource(
            UiStrings.trio_remote_problem_duration,
            TrioRemoteViewModel.DURATION_RANGE.first,
            TrioRemoteViewModel.DURATION_RANGE.last
        )

        Problem.CARBS       -> stringResource(UiStrings.trio_remote_problem_carbs, viewModel.maxCarbs)
        Problem.MINUTES_AGO -> stringResource(UiStrings.trio_remote_problem_minutes_ago, TrioRemoteViewModel.MAX_MINUTES_AGO)
        Problem.BOLUS       -> stringResource(UiStrings.trio_remote_problem_bolus, insulinText(viewModel.maxBolus))
    }
    Text(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ResultText(outcome: SendOutcome, viewModel: TrioRemoteViewModel) {
    val command = commandText(outcome.command, viewModel)
    when (val result = outcome.result) {
        TrioRemoteResult.Sent      -> Text(stringResource(UiStrings.trio_remote_sent, command))
        is TrioRemoteResult.Failed -> Text(stringResource(UiStrings.trio_remote_failed, command, result.reason), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun insulinText(units: Double): String = stringResource(UiStrings.trio_remote_insulin_value, formatTwoDecimals(units))

/** Two decimals with a point, the way Trio is sent it */
private fun formatTwoDecimals(value: Double): String {
    val hundredths = round(value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

@Composable
private fun commandText(command: TrioRemoteCommand, viewModel: TrioRemoteViewModel): String = when (command) {
    is TrioRemoteCommand.TempTarget    ->
        stringResource(UiStrings.trio_remote_command_temp_target, viewModel.targetText(command.targetMgdl.toDouble()), command.durationMinutes)

    TrioRemoteCommand.CancelTempTarget -> stringResource(UiStrings.trio_remote_command_cancel_temp_target)
    is TrioRemoteCommand.Meal          -> command.eatenAt?.let { eatenAt ->
        stringResource(UiStrings.trio_remote_command_meal_earlier, command.carbs, command.fat, command.protein, viewModel.minutesAgo(eatenAt))
    } ?: stringResource(UiStrings.trio_remote_command_meal, command.carbs, command.fat, command.protein)

    is TrioRemoteCommand.Bolus         -> stringResource(UiStrings.trio_remote_command_bolus, insulinText(command.units))
    is TrioRemoteCommand.StartOverride -> stringResource(UiStrings.trio_remote_command_start_override, command.name)
    TrioRemoteCommand.CancelOverride   -> stringResource(UiStrings.trio_remote_command_cancel_override)
}
