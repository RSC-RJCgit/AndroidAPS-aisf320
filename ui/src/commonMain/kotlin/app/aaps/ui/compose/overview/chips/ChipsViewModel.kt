package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutoIsfDirectTtCode
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.AUTO_ISF_HISTORY_WINDOW_MS
import app.aaps.ui.compose.overview.AutoIsfHistoryRow
import app.aaps.ui.compose.overview.autoIsfHistoryRows
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class List1Row(
    val label: String,
    val current: String,
    val downMmol: Double,
    val upMmol: Double?,
)

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val constraintChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val maintenance: Maintenance,
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(150_000L) // 2.5 minutes
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " ${constraintsProcessed.carbsReq}${rh.gs(CoreUiStrings.required)}"
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    val sensitivityUiState: StateFlow<SensitivityUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)
        val lastAutosensRatio = lastAutosensData?.autosensResult?.ratio
        val lastAutosensPercent = lastAutosensRatio?.let { it * 100 }

        val isEnabled = if (config.AAPSCLIENT) preferences.get(BooleanNonKey.AutosensUsedOnMainPhone)
        else constraintChecker.isAutosensModeEnabled().value()

        val profile = profileFunction.getProfile()
        val request = loop.lastRun?.request
        val isfMgdl = profile?.getProfileIsfMgdl()
        val variableSens =
            if (config.APS) request?.variableSens ?: 0.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        val ratioUsed =
            if (config.APS) request?.autosensResult?.ratio ?: 1.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.openAPSData.suggested?.sensitivityRatio ?: 1.0
            else 1.0
        val units = profileFunction.getUnits()

        var asText = ""
        var isfFrom = ""
        var isfTo = ""
        val dialogText = ArrayList<String>()

        if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null) {
            // Variable ISF branch — hide "AS: 100%" from overview when ratio is exactly 100%
            lastAutosensPercent?.let {
                if (it != 100.0)
                    asText = rh.gs(CoreUiStrings.autosens_short, it)
                dialogText.add(rh.gs(CoreUiStrings.autosens_long, it))
            }
            val profileIsfDisplayed = profileUtil.fromMgdlToUnits(isfMgdl, units)
            val variableIsfDisplayed = profileUtil.fromMgdlToUnits(variableSens, units)
            isfFrom = decimalFormatter.to1Decimal(profileIsfDisplayed)
            isfTo = decimalFormatter.to1Decimal(variableIsfDisplayed)
            dialogText.add(rh.gs(CoreUiStrings.isf_profile, profileIsfDisplayed))
            dialogText.add(rh.gs(CoreUiStrings.isf_variable, variableIsfDisplayed))
            if (ratioUsed != 1.0 && ratioUsed != lastAutosensRatio)
                dialogText.add(rh.gs(CoreUiStrings.algorithm_long, ratioUsed * 100))
            val isfForCarbs = profile.getIsfMgdlForCarbs(dateUtil.now(), "Overview", config, processedDeviceStatusData)
            dialogText.add(rh.gs(CoreUiStrings.isf_for_carbs, profileUtil.fromMgdlToUnits(isfForCarbs, units)))
            if (config.APS) {
                activePlugin.activeAPS?.getSensitivityOverviewString()?.let { dialogText.add(it) }
            }
        } else {
            // Standard autosens-only branch — hide "AS: 100%" from chip but always show in dialog
            lastAutosensData?.let {
                val pct = it.autosensResult.ratio * 100
                if (pct != 100.0)
                    asText = rh.gs(CoreUiStrings.autosens_short, pct)
                dialogText.add(rh.gs(CoreUiStrings.autosens_long, pct))
            }
            if (isfMgdl != null) {
                val profileIsfDisplayed = profileUtil.fromMgdlToUnits(isfMgdl, units)
                dialogText.add(rh.gs(CoreUiStrings.isf_profile, profileIsfDisplayed))
                lastAutosensRatio?.let { ratio ->
                    dialogText.add(rh.gs(CoreUiStrings.isf_effective, profileUtil.fromMgdlToUnits(isfMgdl * ratio, units)))
                }
            }
        }

        return SensitivityUiState(
            asText = asText,
            isfFrom = isfFrom,
            isfTo = isfTo,
            dialogText = dialogText.joinToString("\n"),
            ratio = lastAutosensRatio ?: 1.0,
            isEnabled = isEnabled,
            hasData = lastAutosensData != null,
            autoIsfHistory = activePlugin.activeAPS?.algorithm == APSResult.Algorithm.AUTO_ISF
        )
    }

    /** Rows for the history table, newest first. Glucose and deltas are in the user's units. */
    suspend fun loadAutoIsfHistory(): List<AutoIsfHistoryRow> {
        maintenance.exportCoordinated("ISF_LONG_PRESS")
        val now = dateUtil.now()
        val units = profileFunction.getUnits()
        val fromLivePhone = config.APS && !config.AAPSCLIENT &&
            preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
            activePlugin.activePump.selectedActivePump() is VirtualPump
        val steps = persistenceLayer.getStepsCountFromTimeToTime(now - AUTO_ISF_HISTORY_WINDOW_MS, now)
        return persistenceLayer.getAutoIsfValuesFromTimeToTime(now - AUTO_ISF_HISTORY_WINDOW_MS, now)
            .sortedByDescending { it.timestamp }
            .autoIsfHistoryRows(
                timeText = { dateUtil.timeString(it) },
                glucoseText = { profileUtil.fromMgdlToStringInUnits(it, units) },
                deltaText = { profileUtil.fromMgdlToSignedStringInUnits(it, units) },
                format2 = { decimalFormatter.to2Decimal(it) },
                steps = steps,
                fromLivePhone = fromLivePhone,
                ownDevice = "openaps://${config.deviceModelForUpload}"
            )
    }

    var list1Open by mutableStateOf(false)
        private set
    var list1Generation by mutableIntStateOf(0)
        private set

    fun openList1() {
        list1Open = true
    }

    fun closeList1() {
        list1Open = false
    }

    var list2Open by mutableStateOf(false)
        private set
    var list2Generation by mutableIntStateOf(0)
        private set

    fun openList2() {
        list2Open = true
    }

    fun closeList2() {
        list2Open = false
    }

    fun applyList1(mmol: Double) {
        rxBus.send(EventAutoIsfDirectTtCode(mmol))
        viewModelScope.launch {
            delay(200)
            list1Generation++
            list2Generation++
        }
    }

    fun list1Rows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        val two: (Double) -> String = { decimalFormatter.to2Decimal(it) }
        val one: (Double) -> String = { decimalFormatter.to1Decimal(it) }
        return listOf(
            List1Row("SMBdel base + mild-Bst", "base=${two(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))}, mildBst=${two(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}", 5.002, 5.004),
            List1Row("Tog Libre sens on/off", onOff(preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)), 5.006, null),
            List1Row("Tog Bst autos(all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)), 5.008, null),
            List1Row("pp ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)), 5.012, 5.014),
            List1Row("acce ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)), 5.016, 5.018),
            List1Row("pp ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)), 5.056, 5.058),
            List1Row("acce ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)), 5.062, 5.064),
            List1Row("higher ISF range Wt", one(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)), 5.068, 5.070),
            List1Row("autoISF max (lowBG)", one(preferences.get(DoubleKey.ApsAutoIsfMaxLow)), 5.080, 5.082),
            List1Row("autoISF max (N)", one(preferences.get(DoubleKey.ApsAutoIsfMax)), 5.086, 5.088),
            List1Row("T1 tod offset 00-02h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0002)), 5.092, 5.094),
            List1Row("T2 tod offset 02-04h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0204)), 5.098, 5.100),
            List1Row("T3 tod offset 04-06h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0406)), 5.104, 5.106),
            List1Row("T4 tod offset 06-09h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0609)), 5.110, 5.112),
            List1Row("T5 tod offset 09-12h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset0912)), 5.116, 5.118),
            List1Row("T6 tod offset 12-18h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset1218)), 5.122, 5.124),
            List1Row("T7 tod offset 18-22h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset1822)), 5.128, 5.130),
            List1Row("T8 tod offset 22-00h", one(preferences.get(DoubleKey.ApsAutoIsfTodOffset2200)), 5.134, 5.136),
        ) + startedRows()
    }

    fun list2Rows(): List<List1Row> = startedRows()

    // The functions started with the carb-model curve. List 1 matches the other app.
    // List 2 is the basal-chip double tap, and it opens on these same functions.
    private fun startedRows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        return listOf(
            List1Row("Tog Graph2 (carb model curve) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)), 5.138, null),
            List1Row("Cloud logs upload", "send now", 5.140, null),
            List1Row("MJ state: MJ active", "set this state", 5.222, null),
            List1Row("MJ state: MJ2", "set this state", 5.224, null),
            List1Row("MJ state: MJ3", "set this state", 5.146, null),
            List1Row("MJ state: NOMJremains", "set this state", 5.144, null),
            List1Row("Profile: Standard", preferences.get(StringKey.ApsAutoIsfStandardProfileName), 5.148, null),
            List1Row("Profile: Low", preferences.get(StringKey.ApsAutoIsfLowProfileName), 5.150, null),
            List1Row("Sensor age code", onOff(preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled)), 5.156, null),
            List1Row("Libre UKF set 1", onOff(preferences.get(BooleanNonKey.ApsAutoIsfFslUseUkfSmoothing)), 5.152, null),
            List1Row("MJ start", "low, 0.35, 70", 5.158, null),
            List1Row("MJ restore", "standard, 0.50, 70", 5.160, null),
            List1Row("Steroids on", preferences.get(StringKey.ApsAutoIsfSteroid110ProfileName), 5.162, null),
            List1Row("Steroids 130", preferences.get(StringKey.ApsAutoIsfSteroid130ProfileName), 5.168, null),
            List1Row("Steroids 150", preferences.get(StringKey.ApsAutoIsfSteroid150ProfileName), 5.170, null),
            List1Row("Steroids 190", preferences.get(StringKey.ApsAutoIsfSteroid190ProfileName), 5.172, null),
            List1Row("Steroids 250", preferences.get(StringKey.ApsAutoIsfSteroid250ProfileName), 5.174, null),
            List1Row("Steroids off", preferences.get(StringKey.ApsAutoIsfSteroid100ProfileName), 5.176, null),
        )
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message =
                rh.gs(CoreUiStrings.bolus_iob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob) + "\n" +
                    rh.gs(CoreUiStrings.treatments_wizard_basaliob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, basalIob.basaliob) + "\n" +
                    rh.gs(CoreUiStrings.iob) + ": " + rh.gs(InterfacesStrings.format_insulin_units, total)
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}
