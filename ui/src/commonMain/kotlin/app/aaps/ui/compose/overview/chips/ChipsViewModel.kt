package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.overview.SensitivityOverview
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
import app.aaps.core.keys.DoubleNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.readTier
import app.aaps.core.keys.tierSummary
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.AUTO_ISF_HISTORY_WINDOW_MS
import app.aaps.ui.compose.overview.AutoIsfHistoryRow
import app.aaps.ui.compose.overview.autoIsfHistoryRows
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** List 3 rows carried out on this phone's own display on a Client, instead of being relayed to the loop phone as a coded TT. */
const val LOCAL_CLEAN_GRAPH = -42.0
const val LOCAL_GRAPH_RESET = -43.0

data class List1Row(
    val label: String,
    val current: String,
    val downMmol: Double,
    val upMmol: Double?,
    val readOnly: Boolean = false,
    val pickProfiles: Boolean = false,
)

data class CodedProfileRole(
    val title: String,
    val key: StringKey,
    val optional: Boolean,
    val blockSteroidName: Boolean,
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
    private val profileRepository: ProfileRepository,
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
    private val sensitivityOverview: SensitivityOverview,
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

    val overviewActions: StateFlow<List<OverviewAction>> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        buildOverviewActions()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData,
            autoIsfHistory = activePlugin.activeAPS?.algorithm == APSResult.Algorithm.AUTO_ISF
                || config.AAPSCLIENT
                || persistenceLayer.getAutoIsfValuesFromTimeToTime(
                    dateUtil.now() - AUTO_ISF_HISTORY_WINDOW_MS,
                    dateUtil.now()
                ).isNotEmpty()
        )
    }

    var autoIsfHistoryOpen by mutableStateOf(false)
        private set
    var autoIsfHistoryRows by mutableStateOf<List<AutoIsfHistoryRow>>(emptyList())
        private set

    /** Opens the history table. Kept here so turning the phone does not close it. */
    fun openAutoIsfHistory() {
        viewModelScope.launch {
            autoIsfHistoryRows = loadAutoIsfHistory()
            autoIsfHistoryOpen = true
            // The table is already open. A slow cloud upload must not hold it back.
            runCatching { maintenance.exportCoordinated("ISF_LONG_PRESS") }
        }
    }

    fun closeAutoIsfHistory() {
        autoIsfHistoryOpen = false
    }

    /** Rows for the history table, newest first. Glucose and deltas are in the user's units. */
    suspend fun loadAutoIsfHistory(): List<AutoIsfHistoryRow> {
        val now = dateUtil.now()
        val units = profileFunction.getUnits()
        val fromLivePhone = config.APS && !config.AAPSCLIENT &&
            preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
            activePlugin.activePump.selectedActivePump() is VirtualPump
        val steps = persistenceLayer.getStepsCountFromTimeToTime(now - AUTO_ISF_HISTORY_WINDOW_MS, now)
        val aivRows = persistenceLayer.getAutoIsfValuesFromTimeToTime(now - AUTO_ISF_HISTORY_WINDOW_MS, now)
            .sortedByDescending { it.timestamp }
        // Profile basal at each row's time (not the running/temp rate). Looked up here because the lookup is suspend.
        val profileBasal = aivRows.associate { it.timestamp to profileFunction.getProfile(it.timestamp)?.getBasal(it.timestamp) }
        return aivRows
            .autoIsfHistoryRows(
                profileBasalAt = { profileBasal[it] },
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

    var insulinPeakText by mutableStateOf("--")
        private set

    fun openList1() {
        list1Open = true
        viewModelScope.launch { refreshInsulinPeak() }
    }

    // Name of the running profile without modifiers, read when List 1 opens for the "Tier now" row.
    private var runningProfileForTier = ""

    private suspend fun refreshInsulinPeak() {
        insulinPeakText = profileFunction.getRunningOrRequestedICfg()?.peak?.toString() ?: "--"
        runningProfileForTier = profileFunction.getOriginalProfileName()
        list1Generation++
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

    // List 3 (2026-10-10, per explicit request): opened by a double-tap on the ISF chip. One row so far, Clean graph, no text. Like Lists 1
    // and 2: a client relays the coded TT (and note) to the loop phone, the loop phone and the virtual phone apply it on this phone.
    var list3Open by mutableStateOf(false)
        private set

    fun openList3() {
        list3Open = true
    }

    fun closeList3() {
        list3Open = false
    }

    // Loop phone and virtual phone: two rows, applied on this phone. Client (2026-10-10, per explicit request: it is only a display matter):
    // the same two rows for THIS phone's own display, then the same two relayed to the loop phone.
    fun list3Rows(): List<List1Row> {
        val clean = "no SMB labels or arrows, plain green line, graph text lines off (hypoprediction stays)"
        val normal = "SMB labels and arrows, line colours and the text lines on again"
        return numbered(
            if (config.AAPSCLIENT) listOf(
                List1Row("Clean graph, no text (this phone)", clean, LOCAL_CLEAN_GRAPH, null),
                List1Row("Graph back to normal (this phone)", normal, LOCAL_GRAPH_RESET, null),
                List1Row("Clean graph, no text (loop phone)", clean, 5.244, null),
                List1Row("Graph back to normal (loop phone)", normal, 5.246, null),
            ) else listOf(
                List1Row("Clean graph, no text", clean, 5.244, null),
                List1Row("Graph back to normal", normal, 5.246, null),
            )
        )
    }

    fun applyList3(code: Double) {
        when (code) {
            LOCAL_CLEAN_GRAPH -> {
                preferences.put(BooleanNonKey.ApsAutoIsfCleanGraphRequested, true)
                preferences.put(BooleanKey.ApsAutoIsfShowGraphText, false)
            }
            LOCAL_GRAPH_RESET -> {
                preferences.put(BooleanNonKey.ApsAutoIsfGraphResetRequested, true)
                preferences.put(BooleanKey.ApsAutoIsfShowGraphText, true)
            }
            else              -> applyList1(code)
        }
    }

    // KMP client relay (2026-10-08, per explicit request): a client has no AutoISF loop of its own, so a List 1 or List 2 tap
    // used to do nothing at all (the plugin's handler returns for a client). Now it sends the coded 5-minute TT to the master,
    // as the 3426 client does: the master reads it, applies the change and cancels the TT. A real TT that is already on is never
    // cancelled: the code is queued and retried every 5 minutes. The queue is shown at the top of both lists.
    private val relayQueue = ArrayDeque<Double>()
    private var relayJob: Job? = null
    var queuedRelayCodes by mutableStateOf<List<Double>>(emptyList())
        private set

    private fun relayToMaster(mmol: Double) {
        relayQueue.addLast(mmol)
        queuedRelayCodes = relayQueue.toList()
        if (relayJob?.isActive == true) return
        relayJob = viewModelScope.launch {
            while (relayQueue.isNotEmpty()) {
                if (persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null) {
                    aapsLogger.info(LTag.CORE, "Client relay TT ${relayQueue.first()} deferred 5 min; existing TT preserved")
                    delay(5 * 60_000L)
                    continue
                }
                val next = relayQueue.removeFirst()
                queuedRelayCodes = relayQueue.toList()
                insertRelayTt(next)
                if (relayQueue.isNotEmpty()) delay(5 * 60_000L)
            }
        }
    }

    private suspend fun insertRelayTt(mmol: Double) {
        // 18.0, the 3426 app's scale, not this app's Constants.MMOLL_TO_MGDL (18.01559): a 3426 master matches a code to within
        // 0.0018 mg/dL on its own scale, so a TT at 18.01559 would never be recognised there. The KMP master accepts both scales.
        val mgdl = mmol * 18.0
        val sentAt = dateUtil.now()
        persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = TT(
                timestamp = sentAt,
                duration = 5 * 60_000L,
                reason = TT.Reason.CUSTOM,
                lowTarget = mgdl,
                highTarget = mgdl
            ),
            action = Action.TT,
            source = Sources.TTDialog,
            note = "TT code $mmol (KMP client list)",
            listValues = listOf(
                ValueWithUnit.TETTReason(TT.Reason.CUSTOM),
                ValueWithUnit.Mgdl(mgdl),
                ValueWithUnit.Minute(5)
            )
        )
        // The note copy (2026-10-08, per explicit request): "LC<code>@<TT start time>". The master applies whichever of the TT and this
        // note it sees first and skips the other, matching them by that time.
        val copy = "LC$mmol@$sentAt"
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = sentAt + 1,
                type = TE.Type.NOTE,
                note = copy,
                duration = 60_000L,
                glucoseUnit = profileFunction.getUnits(),
            ),
            timestamp = sentAt + 1,
            action = Action.CAREPORTAL,
            source = Sources.TTDialog,
            note = copy,
            listValues = listOf(ValueWithUnit.SimpleString(copy)),
        )
        aapsLogger.info(LTag.CORE, "Client relay TT $mmol sent to the master, with the note copy $copy")
    }

    /** Text for the top of the lists while codes wait for a running TT to end, or null when nothing waits. */
    fun queuedRelayText(): String? =
        if (queuedRelayCodes.isEmpty()) null
        else "Waiting for the running TT to end (retry every 5 min): " + queuedRelayCodes.joinToString(", ")

    fun applyList1(mmol: Double) {
        if (config.AAPSCLIENT) relayToMaster(mmol) else rxBus.send(EventAutoIsfDirectTtCode(mmol))
        viewModelScope.launch {
            delay(400)
            refreshInsulinPeak()
            list1Generation++
            list2Generation++
        }
    }

    private suspend fun buildOverviewActions(): List<OverviewAction> {
        if (config.AAPSCLIENT) return emptyList()
        if (activePlugin.activeAPS?.algorithm != APSResult.Algorithm.AUTO_ISF) return emptyList()
        if (!preferences.get(BooleanKey.AutomationStatesEnabled)) return emptyList()
        val states = preferences.get(StringNonKey.AutomationCurrentStates)
        val mj = stateValue(states, "MJ")
        val steroids = stateValue(states, "Steroids")
        val actions = mutableListOf<OverviewAction>()
        if (preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)) {
            when (mj) {
                "NOMJremains" -> actions += OverviewAction(rh.gs(UiStrings.overview_mj_start), 5.158)
                "" -> Unit
                else -> actions += OverviewAction(rh.gs(UiStrings.overview_mj_restore), 5.160)
            }
        }
        if (preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)) {
            val hour = dateUtil.hourString().toIntOrNull() ?: 0
            val profile = profileFunction.getProfile()
            val originalPercent = (profile as? ProfileSealed.EPS)?.value?.originalPercentage ?: profile?.percentage
            val profileName = profileFunction.getOriginalProfileName()
            val atHundred = originalPercent == 100
            if (hour >= 6 && mj == "NOMJremains" && steroids == "Steroids Off") {
                actions += OverviewAction(rh.gs(UiStrings.overview_steroid_on), 5.162)
            }
            if (atHundred && steroids == "SteroidsON" && profileName == preferences.get(StringKey.ApsAutoIsfSteroid110ProfileName)) {
                actions += OverviewAction(rh.gs(UiStrings.overview_steroid_130), 5.168)
            }
            if (atHundred && steroids == "SteroidsON" && mj == "NOMJremains") {
                if (profileName == preferences.get(StringKey.ApsAutoIsfSteroid130ProfileName)) {
                    actions += OverviewAction(rh.gs(UiStrings.overview_steroid_150), 5.170)
                }
                if (profileName == preferences.get(StringKey.ApsAutoIsfSteroid150ProfileName)) {
                    actions += OverviewAction(rh.gs(UiStrings.overview_steroid_190), 5.172)
                }
                if (profileName == preferences.get(StringKey.ApsAutoIsfSteroid190ProfileName)) {
                    actions += OverviewAction(rh.gs(UiStrings.overview_steroid_250), 5.174)
                }
                actions += OverviewAction(rh.gs(UiStrings.overview_steroid_off), 5.176)
            }
        }
        return actions
    }

    private fun stateValue(json: String, name: String): String {
        val key = "\"$name\""
        val at = json.indexOf(key)
        if (at < 0) return ""
        val colon = json.indexOf(':', at + key.length)
        if (colon < 0) return ""
        val open = json.indexOf('"', colon + 1)
        val close = if (open < 0) -1 else json.indexOf('"', open + 1)
        if (open < 0 || close < 0) return ""
        return json.substring(open + 1, close)
    }

    fun list1Rows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        val two: (Double) -> String = { decimalFormatter.to2Decimal(it) }
        val one: (Double) -> String = { decimalFormatter.to1Decimal(it) }
        return numbered(listOf(
            List1Row("SMBdel base + mild-Bst", "base=${two(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))}, mildBst=${two(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}", 5.002, 5.004),
            List1Row("Tog Libre sens on/off", onOff(preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjEnabled)), 5.006, null),
            List1Row("Tog Bst autos(all) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)), 5.008, null),
            List1Row("pp ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal)), 5.012, 5.014),
            List1Row("acce ISF Wt (Or)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal)), 5.016, 5.018),
            List1Row("Dura weight", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfDuraWeight)), 5.022, 5.024),
            List1Row("Libre slope", decimalFormatter.to2Decimal(preferences.get(DoubleKey.FslCalSlope)), 5.026, 5.028),
            List1Row("Libre offset", decimalFormatter.to2Decimal(preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)), 5.032, 5.034),
            List1Row("Live Libre slope/offset", libreLiveText(), 0.0, null, readOnly = true),
            List1Row("SMB offset", one(preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride)), 5.036, 5.038),
            List1Row("Clean main graph", "no SMBs, solid green", 5.042, null),
            List1Row("Wizard bolus %", preferences.get(IntKey.OverviewBolusPercentage).toString(), 5.046, 5.048),
            List1Row("Mild boost", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio)), 5.052, 5.054),
            List1Row("pp ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh)), 5.056, 5.058),
            List1Row("acce ISF Wt (High)", two(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh)), 5.062, 5.064),
            List1Row("higher ISF range Wt", one(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight)), 5.068, 5.070),
            List1Row("Peak insulin time", insulinPeakText, 5.074, 5.076),
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
            List1Row("Tog Graph2 (carb model curve) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)), 5.138, null),
            List1Row("Cloud logs upload", "send now", 5.140, null),
            List1Row("Tog Graph5 (main clone) on/off", onOff(preferences.get(BooleanKey.ApsAutoIsfShowGraph5)), 5.142, null),
            List1Row("MJ state: MJ active", "set this state", 5.222, null),
            List1Row("MJ state: MJ2", "set this state", 5.224, null),
            List1Row("MJ state: MJ3", "set this state", 5.146, null),
            List1Row("MJ state: NOMJremains", "set this state", 5.144, null),
            List1Row("Profile: Standard", preferences.get(StringKey.ApsAutoIsfStandardProfileName), 5.148, null),
            List1Row("Profile: Low", preferences.get(StringKey.ApsAutoIsfLowProfileName), 5.150, null),
            List1Row("Tier now", tierNowText(), 0.0, null, readOnly = true),
            List1Row("Tier set A", "Standard and Low", 5.216, null),
            List1Row("Tier set B", "Standard and Low", 5.218, null),
            List1Row("Tier set C", "Standard and Low", 5.220, null),
            List1Row("Re-pick coded profiles", "Standard, Low, Steroid", 0.0, null, pickProfiles = true),
            List1Row("Libre UKF set 1", onOff(preferences.get(BooleanNonKey.ApsAutoIsfFslUseUkfSmoothing)), 5.152, null),
            List1Row("Sensor age code", onOff(preferences.get(BooleanNonKey.ApsAutoIsfSensorAgeCodeEnabled)), 5.156, null),
            List1Row("Hypo alarm state: set AlarmRecent", "sets the alarm time to now", 5.240, null),
            List1Row("Hypo alarm state: clear", "NoAlarmRecent", 5.242, null),
        ))
    }

    fun list2Rows(): List<List1Row> {
        val onOff: (Boolean) -> String = { if (it) "ON" else "OFF" }
        return numbered(listOf(
            List1Row("MJ injection", "low, 0.35, 70", 5.158, null),
            List1Row("MJ restore", "standard, 0.50, 70", 5.160, null),
            List1Row("Steroids on", preferences.get(StringKey.ApsAutoIsfSteroid110ProfileName), 5.162, null),
            List1Row("MJ buttons", onOff(preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled)), 5.164, null),
            List1Row("Steroid buttons", onOff(preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled)), 5.166, null),
            List1Row("Steroids 130", preferences.get(StringKey.ApsAutoIsfSteroid130ProfileName), 5.168, null),
            List1Row("Steroids 150", preferences.get(StringKey.ApsAutoIsfSteroid150ProfileName), 5.170, null),
            List1Row("Steroids 190", preferences.get(StringKey.ApsAutoIsfSteroid190ProfileName), 5.172, null),
            List1Row("Steroids 250", preferences.get(StringKey.ApsAutoIsfSteroid250ProfileName), 5.174, null),
            List1Row("Steroids off", preferences.get(StringKey.ApsAutoIsfSteroid100ProfileName), 5.176, null),
            List1Row("Tier 3 UAM boost", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostEnabled)), 5.194, null),
            List1Row("Profile batch auto", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchAutoEnabled)), 5.210, null),
            List1Row("Profile batch hold A", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertEnabled)), 5.212, null),
            List1Row("Profile batch hold C", onOff(preferences.get(BooleanNonKey.ApsAutoIsfProfileBatchRevertCEnabled)), 5.214, null),
            List1Row("AutoISF calcs UKF1", onOff(preferences.get(BooleanKey.ApsAutoIsfUseUkf1ForDosing)), 5.196, null),
            List1Row("Location texts", onOff(preferences.get(BooleanKey.AutomationCodedLocationsEnabled)), 5.198, null),
            List1Row("Stepcount import from remote main AAPS phone", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)), 5.206, null),
            List1Row("MJ state copy from the loop phone", onOff(preferences.get(BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual)), 5.236, null),
            List1Row("Fast rise", onOff(preferences.get(BooleanKey.ApsAutoIsfFastRiseEnabled)), 5.226, null),
            List1Row("LoReb", onOff(preferences.get(BooleanKey.ApsAutoIsfLowReboundGuardEnabled)), 5.228, null),
            List1Row("T3 unrestricted", onOff(preferences.get(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled)), 5.230, null),
            List1Row("Insulin totals row", onOff(preferences.get(BooleanKey.ApsAutoIsfShowInsulinTotals)), 5.232, null),
            List1Row("Loop interval", if (preferences.get(BooleanKey.ApsAutoIsfLoopEveryMinute)) "every 1 min" else "every 5 min", 5.234, null),
            List1Row("Location text phone", preferences.get(StringKey.AutomationLocationSmsDeviceModel).ifBlank { onOff(false) }, 5.204, null),
            List1Row("Send AnyDesk restart", "send now", 5.178, null),
            List1Row("Boost scale", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostScale)), 5.182, 5.184),
            List1Row("Boost max", decimalFormatter.to2Decimal(preferences.get(DoubleKey.ApsAutoIsfUamBoostMaxBolus)), 5.186, 5.188),
            List1Row("Boost IOB max", preferences.get(IntKey.ApsAutoIsfUamBoostMaxIobPercent).toString(), 5.190, 5.192),
            List1Row("Stage newest APK (keep 20)", newestApkText(), 5.202, null),
            List1Row("Install newest APK (Shizuku)", newestApkText(), 5.200, null),
            List1Row("ADB wireless: attempt Shizuku start", adbPortText(), 5.208, null),
        ))
    }

    // The number is the place in the list on screen. Each row is on one list only.
    private fun numbered(rows: List<List1Row>): List<List1Row> =
        rows.mapIndexed { index, row -> row.copy(label = "${index + 1}. ${row.label}") }

    // The Standard and Low roles and the running profile, each placed on the A, B, C ladders the same way the loop reads them.
    private fun tierNowText(): String {
        val standardName = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val lowName = preferences.get(StringKey.ApsAutoIsfLowProfileName)
        val runningName = runningProfileForTier
        val reading = readTier(
            standardName = standardName,
            lowName = lowName,
            runningName = runningName,
            standardRungs = listOf(
                preferences.get(StringKey.ApsAutoIsfStandard100ProfileName),
                preferences.get(StringKey.ApsAutoIsfStandard105ProfileName),
                preferences.get(StringKey.ApsAutoIsfStandard110ProfileName),
            ),
            lowRungs = listOf(
                preferences.get(StringKey.ApsAutoIsfLow70ProfileName),
                preferences.get(StringKey.ApsAutoIsfLow80ProfileName),
                preferences.get(StringKey.ApsAutoIsfLow90ProfileName),
            ),
        )
        return tierSummary(reading, standardName, lowName, runningName)
    }

    private fun newestApkText(): String {
        val newest = preferences.get(LongNonKey.ApsAutoIsfApkNewestNnn)
        return if (newest > 0L) "Newest: $newest" else "Newest: not found yet"
    }

    private fun adbPortText(): String {
        val port = preferences.get(IntKey.ApsAutoIsfAdbConnectPort)
        return if (port > 0) "Port $port" else "Port not set"
    }

    private fun libreLiveText(): String {
        val liveSlope = preferences.get(DoubleKey.FslCalSlope)
        val liveOffset = preferences.get(DoubleKey.FslCalOffset)
        val baseSlope = preferences.get(DoubleNonKey.ApsAutoIsfLibreSlopeOrig)
        val baseOffset = preferences.get(DoubleNonKey.ApsAutoIsfLibreOffsetOrig)
        val tier = preferences.get(BooleanNonKey.ApsAutoIsfOldSensorAdjActive)
        return "Live slope ${decimalFormatter.to2Decimal(liveSlope)}, offset ${decimalFormatter.to2Decimal(liveOffset)}. " +
            "Baseline slope ${decimalFormatter.to2Decimal(baseSlope)}, offset ${decimalFormatter.to2Decimal(baseOffset)}. " +
            "Sensor age tier ${if (tier) "on" else "off"}."
    }

    fun profileNames(): List<String> =
        profileRepository.profile.value?.getProfileList()?.map { it.toString() } ?: emptyList()

    fun codedProfileRoles(): List<CodedProfileRole> = listOf(
        CodedProfileRole("Standard current", StringKey.ApsAutoIsfStandardProfileName, optional = false, blockSteroidName = true),
        CodedProfileRole("Low current", StringKey.ApsAutoIsfLowProfileName, optional = false, blockSteroidName = true),
        CodedProfileRole("Standard tier A", StringKey.ApsAutoIsfStandard100ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Standard tier B", StringKey.ApsAutoIsfStandard105ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Standard tier C", StringKey.ApsAutoIsfStandard110ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Low tier A", StringKey.ApsAutoIsfLow70ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Low tier B", StringKey.ApsAutoIsfLow80ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Low tier C", StringKey.ApsAutoIsfLow90ProfileName, optional = true, blockSteroidName = true),
        CodedProfileRole("Steroid 100", StringKey.ApsAutoIsfSteroid100ProfileName, optional = false, blockSteroidName = false),
        CodedProfileRole("Steroid 110", StringKey.ApsAutoIsfSteroid110ProfileName, optional = false, blockSteroidName = false),
        CodedProfileRole("Steroid 130", StringKey.ApsAutoIsfSteroid130ProfileName, optional = false, blockSteroidName = false),
        CodedProfileRole("Steroid 150", StringKey.ApsAutoIsfSteroid150ProfileName, optional = false, blockSteroidName = false),
        CodedProfileRole("Steroid 190", StringKey.ApsAutoIsfSteroid190ProfileName, optional = false, blockSteroidName = false),
        CodedProfileRole("Steroid 250", StringKey.ApsAutoIsfSteroid250ProfileName, optional = false, blockSteroidName = false),
    )

    fun codedRoleValue(role: CodedProfileRole): String = preferences.get(role.key)

    // Returns a short reason when the name is refused. Empty means it was saved.
    fun setCodedRole(role: CodedProfileRole, name: String): String {
        if (role.blockSteroidName && (name.contains("steroid", ignoreCase = true) || name.contains("%"))) {
            return "That name belongs on a steroid role."
        }
        // 2026-10-08, per explicit request: a client never changes a setting on its own phone, only on Live. It sends the same
        // "SetRole <key>=<profile>" CarePortal note the 3426 client sends; the master applies it and writes RoleSet. Nothing is
        // written here until Live's value comes back through the settings it publishes.
        if (config.AAPSCLIENT) {
            viewModelScope.launch { sendSetRoleNote(role.key, name) }
            return ""
        }
        preferences.put(role.key, name)
        list1Generation++
        return ""
    }

    private suspend fun sendSetRoleNote(key: StringKey, name: String) {
        val ts = dateUtil.now()
        val text = "SetRole ${key.key}=$name"
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = ts,
                type = TE.Type.NOTE,
                note = text,
                duration = 60_000L,
                glucoseUnit = profileFunction.getUnits(),
            ),
            timestamp = ts,
            action = Action.CAREPORTAL,
            source = Sources.ProfileSwitchDialog,
            note = null,
            listValues = listOf(ValueWithUnit.SimpleString("SetRole ${key.key}")),
        )
        aapsLogger.info(LTag.CORE, "Client sent $text to the master")
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
