package app.aaps.ui.compose.profileManagement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

@Composable
fun codedRoleLabel(key: String): String = when (key) {
    "autoisf_standard_profile_name" -> stringResource(UiStrings.coded_role_standard)
    "autoisf_low_profile_name" -> stringResource(UiStrings.coded_role_low)
    "autoisf_standard100_profile_name" -> stringResource(UiStrings.coded_role_standard_a)
    "autoisf_standard105_profile_name" -> stringResource(UiStrings.coded_role_standard_b)
    "autoisf_standard110_profile_name" -> stringResource(UiStrings.coded_role_standard_c)
    "autoisf_low70_profile_name" -> stringResource(UiStrings.coded_role_low_a)
    "autoisf_low80_profile_name" -> stringResource(UiStrings.coded_role_low_b)
    "autoisf_low90_profile_name" -> stringResource(UiStrings.coded_role_low_c)
    "autoisf_steroid_100_profile_name" -> stringResource(UiStrings.coded_role_steroid_a)
    "autoisf_steroid_110_profile_name" -> stringResource(UiStrings.coded_role_steroid_b)
    "autoisf_steroid_130_profile_name" -> stringResource(UiStrings.coded_role_steroid_c)
    "autoisf_steroid_150_profile_name" -> stringResource(UiStrings.coded_role_steroid_d)
    "autoisf_steroid_190_profile_name" -> stringResource(UiStrings.coded_role_steroid_e)
    "autoisf_steroid_250_profile_name" -> stringResource(UiStrings.coded_role_steroid_f)
    else -> key
}

@Composable
fun CodedProfilesDialog(
    profileNames: List<String>,
    initial: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val slots = codedProfileSlots()
    var selected by remember { mutableStateOf(slots.mapIndexed { index, _ -> initial.getOrElse(index) { "" } }) }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    val notSet = stringResource(UiStrings.coded_role_not_set)
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(UiStrings.coded_profiles), style = MaterialTheme.typography.titleLarge)
                Text(text = stringResource(UiStrings.coded_profiles_help), style = MaterialTheme.typography.bodyMedium)
                if (profileNames.isEmpty()) {
                    Text(text = stringResource(UiStrings.coded_profiles_empty), style = MaterialTheme.typography.bodyMedium)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    slots.forEachIndexed { index, slot ->
                        val value = selected[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = profileNames.isNotEmpty()) { openIndex = index }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(text = codedRoleLabel(slot.key), style = MaterialTheme.typography.labelLarge)
                            Text(text = if (value.isEmpty()) notSet else value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
                    Button(
                        onClick = { onSave(selected) },
                        enabled = profileNames.isNotEmpty()
                    ) { Text(stringResource(CoreUiStrings.ok)) }
                }
            }
        }
    }
    val picking = openIndex
    if (picking != null) {
        val slot = slots[picking]
        val current = selected[picking]
        val choices = buildList {
            if (slot.optional) add("")
            profileNames.forEach { if (it !in this) add(it) }
            if (current.isNotEmpty() && current !in this) add(current)
        }
        ChoiceListDialog(
            title = codedRoleLabel(slot.key),
            choices = choices,
            label = { name -> if (name.isEmpty()) notSet else name },
            onPick = { name ->
                selected = selected.toMutableList().also { it[picking] = name }
                openIndex = null
            },
            onDismiss = { openIndex = null }
        )
    }
}

@Composable
fun ChoiceListDialog(
    title: String,
    choices: List<String>,
    label: @Composable (String) -> String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        Text(
                            text = label(choice),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(choice) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
            }
        }
    }
}
