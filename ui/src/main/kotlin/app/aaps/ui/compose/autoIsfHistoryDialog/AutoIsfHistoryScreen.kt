package app.aaps.ui.compose.autoIsfHistoryDialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.ui.compose.AapsTopAppBar

// Placeholder screen — proves the long-press -> navigation wiring end-to-end before the real
// table UI (Time/BGL/Final/acce/bg/pp/dura/SMB/FR/iobTH/acce/Delta/SDelta/Req/TBR/S5/S15/S30/S60/S180,
// same 4-hour window as the source fork's AutoISFHistoryDialog) is built on top of it.
@Composable
fun AutoIsfHistoryScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text("AutoISF History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("AutoISF history table not yet implemented", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
