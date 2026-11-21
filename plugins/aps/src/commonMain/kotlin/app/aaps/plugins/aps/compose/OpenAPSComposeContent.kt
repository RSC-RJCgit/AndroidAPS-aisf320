package app.aaps.plugins.aps.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.autoisf.AutoIsfListSettings
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.ToolbarConfig

class OpenAPSComposeContent(
    private val apsPlugin: APS,
    private val rxBus: RxBus,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    /** The loop phone's result from NS, for a phone with no result of its own (a client); null where not wanted. */
    private val nsResult: NsApsResultSource? = null
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val scope = rememberCoroutineScope()
        // Headings for the loop phone's AutoISF list values (2026-11-21, per explicit request), from this phone's own lists
        val listSettings = AutoIsfListSettings(LocalPreferences.current, LocalDecimalFormatter.current)
        val viewModel = remember {
            OpenAPSViewModel(
                apsPlugin = apsPlugin,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil,
                scope = scope,
                nsResult = nsResult,
                autoIsfLabels = { listSettings.snapshotLabels() },
                localAutoIsfValues = {
                    nsResult?.localListInputs?.invoke()?.let { (peak, running) -> listSettings.snapshot(peak, running) }.orEmpty()
                }
            )
        }

        val state = viewModel.uiState.collectAsStateWithLifecycle()

        OpenAPSScreen(
            state = state.value,
            onRefresh = viewModel::onRefresh
        )
    }
}
