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
import app.aaps.core.interfaces.aps.ProfileRoleSynchronizer
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
import app.aaps.core.interfaces.overview.graph.autoIsfListsFor
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutoIsfDirectTtCode
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.autoisf.AUTO_ISF_HISTORY_WINDOW_MS
import app.aaps.core.objects.autoisf.AutoIsfHistoryRow
import app.aaps.core.objects.autoisf.AutoIsfListSettings
import app.aaps.core.objects.autoisf.List1Row
import app.aaps.core.objects.autoisf.numberedListRows
import app.aaps.core.objects.autoisf.loadAutoIsfHistoryRows
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import app.aaps.ui.UiStrings
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

    /**
     * On a client, the master's own IOB from its newest loop result (2026-11-08, per explicit request). A client does not
     * loop: its own sum from the synced doses uses its own insulin and profile, and can differ from what the master
     * doses with. Null on a master, or when the master's result is missing or older than [MASTER_IOB_MAX_AGE_MS], so the
     * client's own sum is shown instead of a stale number.
     */
    private fun masterIob(): Double? {
        if (!config.AAPSCLIENT) return null
        val data = processedDeviceStatusData.openAPSData
        val iob = data.suggested?.IOB?.takeIf { it.isFinite() } ?: return null
        if (dateUtil.now() - data.clockSuggested > MASTER_IOB_MAX_AGE_MS) return null
        return iob
    }

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = masterIob() ?: (bolusIob.iob + basalIob.basaliob)
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
                    cobText = rh.gs(CoreUiStrings.value_with_unit, cobText, rh.gs(CoreUiStrings.value_with_unit, constraintsProcessed.carbsReq.toString(), rh.gs(CoreUiStrings.required)))
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
            isfStep = data.isfStep,
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
    var autoIsfHistoryEndTime by mutableStateOf(0L)
        private set
    private var autoIsfHistoryJob: Job? = null

    fun openAutoIsfHistory(endTime: Long? = null) {
        autoIsfHistoryJob?.cancel()
        val selectedEnd = (endTime ?: dateUtil.now()).coerceAtMost(dateUtil.now())
        autoIsfHistoryJob = viewModelScope.launch {
            autoIsfHistoryRows = loadAutoIsfHistory(selectedEnd)
            autoIsfHistoryEndTime = selectedEnd
            autoIsfHistoryOpen = true
            // The table is already open. A slow cloud upload must not hold it back.
            // Browsing an old window reads stored rows only; it must not export today's data.
            if (endTime == null) runCatching { maintenance.exportCoordinated("ISF_LONG_PRESS") }
        }
    }

    fun closeAutoIsfHistory() {
        autoIsfHistoryJob?.cancel()
        autoIsfHistoryOpen = false
    }

    /** Rows for the history table, newest first. Glucose and deltas are in the user's units. */
    suspend fun loadAutoIsfHistory(endTime: Long = dateUtil.now()): List<AutoIsfHistoryRow> =
        loadAutoIsfHistoryRows(endTime, persistenceLayer, profileFunction, profileUtil, dateUtil, decimalFormatter, config, preferences, activePlugin)

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
        if (!autoIsfLists()) return
        list2Open = true
    }

    // The AutoISF lists fit the loop: this phone's own loop, or the master's last loop result on a client (2026-10-19,
    // per explicit request). On AMA or SMB the codes would change AutoISF settings that loop does not use.
    private fun autoIsfLists(): Boolean =
        autoIsfListsFor(if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.algorithm else activePlugin.activeAPS?.algorithm)

    fun closeList2() {
        list2Open = false
    }

    // List 3 (2026-10-10, per explicit request): opened by a double-tap on the ISF chip. One row so far, Clean graph, no text. Like Lists 1
    // and 2: a client relays the coded TT (and note) to the loop phone, the loop phone and the virtual phone apply it on this phone.
    var list3Open by mutableStateOf(false)
        private set

    fun openList3() {
        // On a loop phone that runs AMA or SMB nothing is left in List 3
        if (list3Rows().isEmpty()) return
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
        // The loop phone rows are AutoISF codes: only when the loop runs AutoISF (or is not known). The "this phone" rows
        // change only this phone's own display and stay.
        val autoIsf = autoIsfLists()
        return numberedListRows(
            if (config.AAPSCLIENT) listOfNotNull(
                List1Row("Clean graph, no text (this phone)", clean, LOCAL_CLEAN_GRAPH, null),
                List1Row("Graph back to normal (this phone)", normal, LOCAL_GRAPH_RESET, null),
                List1Row("Clean graph, no text (loop phone)", clean, 5.244, null).takeIf { autoIsf },
                List1Row("Graph back to normal (loop phone)", normal, 5.246, null).takeIf { autoIsf },
            ) else if (autoIsf) listOf(
                List1Row("Clean graph, no text", clean, 5.244, null),
                List1Row("Graph back to normal", normal, 5.246, null),
            ) else emptyList()
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

    // A read-only ("v3 style only") client sends the codes like a 3426 client does (2026-10-23, per explicit request): the coded
    // 5-minute TT and its note go to the master through Nightscout. Only when the master's last loop result is AutoISF, because
    // the coded TT is a real TT there: a master on another loop would not read the code and would loop to its odd target for
    // 5 minutes (why Apply sent nothing from 19 Oct). Unknown counts as not AutoISF here.
    private val readOnlyClient: Boolean
        get() = config.AAPSCLIENT && preferences.get(BooleanKey.NsClientReadOnlyClient)

    private fun masterRunsAutoIsf(): Boolean = processedDeviceStatusData.getAPSResult()?.algorithm == APSResult.Algorithm.AUTO_ISF

    /** True after a read-only client pressed Apply while the master's last loop result is not AutoISF: nothing was sent. */
    var notAutoIsfNotice by mutableStateOf(false)
        private set

    fun closeNotAutoIsfNotice() {
        notAutoIsfNotice = false
    }

    // List 1 / List 2 "This phone" on a Client (List 1 2026-11-05, List 2 2026-11-06, per explicit request): only the settings
    // rows in ClientLocalListCodes, which change this phone's own preference (no SMS, note or TT; no automatic settings sync
    // either way, so it stays). Every other row is relayed to the loop phone only.
    fun canApplyOnThisPhone(row: List1Row): Boolean =
        config.AAPSCLIENT && !row.readOnly && !row.pickProfiles && ClientLocalListCodes.supports(*listOfNotNull(row.downMmol, row.upMmol).toDoubleArray())

    fun applyList1OnThisPhone(mmol: Double) {
        val changed = ClientLocalListCodes.apply(preferences, mmol) ?: return
        aapsLogger.info(LTag.CORE, "List code $mmol applied on this phone only: $changed")
        list1Generation++
        list2Generation++
    }

    fun applyList1(mmol: Double) {
        if (readOnlyClient && !masterRunsAutoIsf()) {
            notAutoIsfNotice = true
            return
        }
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

    private val listSettings = AutoIsfListSettings(preferences, decimalFormatter)

    val loopListSnapshot get() = processedDeviceStatusData.openAPSData.autoIsfListSnapshot

    val isClient get() = config.AAPSCLIENT

    fun loopListTime(timestamp: Long): String = dateUtil.dateAndTimeString(timestamp)

    fun list1Rows(): List<List1Row> =
        if (autoIsfLists()) listSettings.list1Rows(insulinPeakText, runningProfileForTier) else listSettings.profilePickerRows()

    fun list2Rows(): List<List1Row> = listSettings.list2Rows()

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
            // The SetRole note is a command to the master as well: a read-only client sends it only to an AutoISF master
            if (readOnlyClient && !masterRunsAutoIsf()) {
                notAutoIsfNotice = true
                return ""
            }
            viewModelScope.launch { sendSetRoleNote(role.key, name) }
            return ""
        }
        if (role.key == StringKey.ApsAutoIsfStandardProfileName || role.key == StringKey.ApsAutoIsfLowProfileName) {
            (activePlugin.activeAPS as? ProfileRoleSynchronizer)?.alignEffectiveProfileRoles(name)
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
            val master = masterIob()
            // The master reports only its total, so a client showing it does not show a bolus/basal split of its own
            val lines = if (master != null) listOf(CoreUiStrings.iob to master)
            else listOf(
                CoreUiStrings.bolus_iob_label to bolusIob.iob,
                CoreUiStrings.treatments_wizard_basaliob_label to basalIob.basaliob,
                CoreUiStrings.iob to bolusIob.iob + basalIob.basaliob
            )
            val message = lines.joinToString("\n") { (label, insulin) ->
                rh.gs(InterfacesStrings.confirmation_line, rh.gs(label), rh.gs(InterfacesStrings.format_insulin_units, insulin))
            }
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}

/** How old the master's loop result may be before a client shows its own IOB sum again: three missed 5-minute cycles */
private const val MASTER_IOB_MAX_AGE_MS = 15 * 60 * 1000L
