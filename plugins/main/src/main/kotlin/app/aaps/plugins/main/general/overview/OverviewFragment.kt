package app.aaps.plugins.main.general.overview

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.graph.data.GraphViewWithCleanup
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.Overview
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventAutoIsfDirectTtCode
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventMjUserAction
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventSteroidUserAction
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.NoteTimestampAllocator
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.elements.SingleClickButton
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.extensions.toVisibilityKeepSpace
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.OverviewFragmentBinding
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.keys.OverviewStringKey
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import app.aaps.plugins.main.general.overview.ui.StatusLightHandler
import app.aaps.plugins.main.skins.SkinProvider
import com.jjoe64.graphview.GraphView
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverviewFragment : DaggerFragment(), View.OnClickListener, OnLongClickListener {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var nsSettingsStatus: NSSettingsStatus
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var dexcomBoyda: DexcomBoyda
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var skinProvider: SkinProvider
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overview: Overview
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var automation: Automation
    @Inject lateinit var automationStateService: AutomationStateInterface
    @Inject lateinit var bgQualityCheck: BgQualityCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var graphDataProvider: Provider<GraphData>
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var importExportPrefs: ImportExportPrefs

    private val disposable = CompositeDisposable()

    private var smallWidth = false
    private var smallHeight = false
    private var axisWidth: Int = 0
    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val secondaryGraphs = ArrayList<GraphView>()
    private val secondaryGraphsLabel = ArrayList<TextView>()

    private var carbAnimation: AnimationDrawable? = null
    private var lastUserAction = ""

    private var _binding: OverviewFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    //@SuppressLint("NewApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        OverviewFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // pre-process landscape mode
        //check screen width
        val wm = requireActivity().windowManager.currentWindowMetrics
        val screenWidth = wm.bounds.width()
        val screenHeight = wm.bounds.height()
        smallWidth = screenWidth <= Constants.SMALL_WIDTH
        smallHeight = screenHeight <= Constants.SMALL_HEIGHT
        val landscape = screenHeight < screenWidth

        if (config.AAPSCLIENT1)
            binding.nsclientCard.setBackgroundColor(Color.argb(80, 0xE8, 0xC5, 0x0C))
        if (config.AAPSCLIENT2)
            binding.nsclientCard.setBackgroundColor(Color.argb(80, 0x0F, 0xBB, 0xE0))

        overview.setVersionView(binding.infoLayout.version)

        skinProvider.activeSkin().preProcessLandscapeOverviewLayout(binding, landscape, rh.gb(app.aaps.core.ui.R.bool.isTablet), smallHeight)
        binding.nsclientCard.visibility = config.AAPSCLIENT.toVisibility()

        binding.notifications.setHasFixedSize(false)
        binding.notifications.layoutManager = LinearLayoutManager(view.context)
        axisWidth = when {
            resources.displayMetrics.densityDpi <= 120 -> 3
            resources.displayMetrics.densityDpi <= 160 -> 10
            resources.displayMetrics.densityDpi <= 320 -> 35
            resources.displayMetrics.densityDpi <= 420 -> 50
            resources.displayMetrics.densityDpi <= 560 -> 70
            else                                       -> 80
        }
        binding.graphsLayout.bgGraph.gridLabelRenderer?.gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
        binding.graphsLayout.bgGraph.gridLabelRenderer?.reloadStyles()
        binding.graphsLayout.bgGraph.gridLabelRenderer?.labelVerticalWidth = axisWidth
        binding.graphsLayout.bgGraph.layoutParams?.height = rh.dpToPx(skinProvider.activeSkin().mainGraphHeight)

        // Graph5 sized the same as the main graph (skin's mainGraphHeight, not secondaryGraphHeight) —
        // it's a main-graph-like panel, not one of the small secondary graphs.
        binding.graphsLayout.graph5.gridLabelRenderer?.gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
        binding.graphsLayout.graph5.gridLabelRenderer?.reloadStyles()
        binding.graphsLayout.graph5.gridLabelRenderer?.labelVerticalWidth = axisWidth
        binding.graphsLayout.graph5.layoutParams?.height = rh.dpToPx(skinProvider.activeSkin().mainGraphHeight)

        carbAnimation = binding.infoLayout.carbsIcon.background as AnimationDrawable?
        carbAnimation?.setEnterFadeDuration(1200)
        carbAnimation?.setExitFadeDuration(1200)

        binding.graphsLayout.bgGraph.setOnLongClickListener {
            overviewData.rangeToDisplay += 6
            overviewData.rangeToDisplay = if (overviewData.rangeToDisplay > 24) 6 else overviewData.rangeToDisplay
            preferences.put(IntNonKey.RangeToDisplay, overviewData.rangeToDisplay)
            rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
            preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
            false
        }
        prepareGraphsIfNeeded(overviewMenus.setting.size)
        overviewMenus.setupChartMenu(binding.graphsLayout.chartMenuButton, binding.graphsLayout.scaleButton)
        binding.graphsLayout.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)

        binding.graphsLayout.chartMenuButton.visibility = preferences.simpleMode.not().toVisibility()

        binding.activeProfile.setOnClickListener(this)
        binding.activeProfile.setOnLongClickListener(this)
        binding.tempTarget.setOnClickListener(this)
        binding.tempTarget.setOnLongClickListener(this)
        binding.pumpStatusLayout.setOnClickListener(this)
        binding.buttonsLayout.acceptTempButton.setOnClickListener(this)
        binding.buttonsLayout.treatmentButton.setOnClickListener(this)
        binding.buttonsLayout.wizardButton.setOnClickListener(this)
        binding.buttonsLayout.calibrationButton.setOnClickListener(this)
        binding.buttonsLayout.cgmButton.setOnClickListener(this)
        binding.buttonsLayout.insulinButton.setOnClickListener(this)
        binding.buttonsLayout.carbsButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnLongClickListener(this)
        binding.infoLayout.apsMode.setOnClickListener(this)
        binding.infoLayout.apsMode.setOnLongClickListener(this)

        // Mod exercise mode toggle icon
        binding.exerciseModeCheckboxIcon.setOnClickListener {
            if (preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)) {
                binding.exerciseModeCheckboxIcon.setImageResource(app.aaps.core.objects.R.drawable.ic_cp_activity_inactive)
                //binding.exerciseModeCheckboxIcon.setBackgroundResource(app.aaps.core.ui.R.color.ribbonDefault)
                binding.exerciseModeCheckboxIcon.setBackgroundColor(rh.gac(context, app.aaps.core.ui.R.attr.ribbonDefaultColor))
                preferences.put(BooleanKey.ApsAutoIsfHighTtRaisesSens, false)
            } else {
                binding.exerciseModeCheckboxIcon.setImageResource(app.aaps.core.objects.R.drawable.ic_cp_activity_active)
                //binding.exerciseModeCheckboxIcon.setBackgroundResource(app.aaps.core.ui.R.color.ribbonWarning)
                binding.exerciseModeCheckboxIcon.setBackgroundColor(rh.gac(context, app.aaps.core.ui.R.attr.ribbonWarningColor))
                preferences.put(BooleanKey.ApsAutoIsfHighTtRaisesSens, true)
            }
        }
        // End mod

    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        checkOnUpdatePopups()
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateCalcProgress() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateSensitivity() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGraph() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           overviewData.rangeToDisplay = it.hours
                           preferences.put(IntNonKey.RangeToDisplay, it.hours)
                           rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
                           preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateBg() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(requireContext())
                           updatePumpStatus()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ processButtonsVisibility() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateTemporaryTarget() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateExtendedBolus() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateTemporaryBasal() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ processAps() }, fabricPrivacy::logException)

        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)

        handler.post { refreshAll() }
        updatePumpStatus()
        updateCalcProgress()

        // Mod check color of exercise mode toggle icon
        if (preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)) {
            binding.exerciseModeCheckboxIcon.setImageResource(app.aaps.core.objects.R.drawable.ic_cp_activity_active)
            //binding.exerciseModeCheckboxIcon.setBackgroundResource(app.aaps.core.ui.R.color.ribbonWarning)
            binding.exerciseModeCheckboxIcon.setBackgroundColor(rh.gac(context, app.aaps.core.ui.R.attr.ribbonWarningColor))
        } else {
            binding.exerciseModeCheckboxIcon.setImageResource(app.aaps.core.objects.R.drawable.ic_cp_activity_inactive)
            //binding.exerciseModeCheckboxIcon.setBackgroundResource(app.aaps.core.ui.R.color.ribbonDefault)
            binding.exerciseModeCheckboxIcon.setBackgroundColor(rh.gac(context, app.aaps.core.ui.R.attr.ribbonDefaultColor))
        }
        // End mod

        popupBolusDialogIfRunning(onClick = false)
    }

    fun refreshAll() {
        if (!config.appInitialized) return
        runOnUiThread {
            _binding ?: return@runOnUiThread
            updateTime()
            updateSensitivity()
            updateGraph()
            updateNotification()
        }
        updateBg()
        updateTemporaryBasal()
        updateExtendedBolus()
        updateIobCob()
        processButtonsVisibility()
        processAps()
        updateProfile()
        updateTemporaryTarget()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        // Remove listeners and detach series to prevent memory leaks
        _binding?.graphsLayout?.bgGraph?.let { graph ->
            graph.setOnLongClickListener(null)
            graph.removeAllSeries()
        }
        for (graph in secondaryGraphs) {
            graph.setOnLongClickListener(null)
            graph.removeAllSeries()
        }
        _binding = null
        carbAnimation?.stop()
        carbAnimation = null
        secondaryGraphs.clear()
        secondaryGraphsLabel.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onClick(v: View) {
        // try to fix  https://fabric.io/nightscout3/android/apps/info.nightscout.androidaps/issues/5aca7a1536c7b23527eb4be7?time=last-seven-days
        // https://stackoverflow.com/questions/14860239/checking-if-state-is-saved-before-committing-a-fragmenttransaction
        if (childFragmentManager.isStateSaved) return
        activity?.let { activity ->
            when (v.id) {
                R.id.treatment_button    -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runTreatmentDialog(childFragmentManager) })

                R.id.wizard_button       -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runWizardDialog(childFragmentManager) })

                R.id.insulin_button      -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runInsulinDialog(childFragmentManager) })

                R.id.quick_wizard_button -> protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable { if (isAdded) onClickQuickWizard() })
                R.id.carbs_button        -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runCarbsDialog(childFragmentManager) })

                R.id.temp_target         -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) uiInteraction.runTempTargetDialog(childFragmentManager) })

                R.id.active_profile      -> {
                    uiInteraction.runProfileViewerDialog(
                        childFragmentManager,
                        dateUtil.now(),
                        UiInteraction.Mode.RUNNING_PROFILE
                    )
                }

                R.id.cgm_button          -> {
                    if (xDripSource.isEnabled()) openCgmApp("com.eveningoutpost.dexdrip")
                    else if (dexcomBoyda.isEnabled()) dexcomBoyda.dexcomPackages().forEach { openCgmApp(it) }
                }

                R.id.calibration_button  -> {
                    if (xDripSource.isEnabled()) {
                        uiInteraction.runCalibrationDialog(childFragmentManager)
                    }
                }

                R.id.accept_temp_button  -> {
                    profileFunction.getProfile() ?: return
                    if ((loop as PluginBase).isEnabled()) {
                        handler.post {
                            val lastRun = loop.lastRun
                            loop.invoke("Accept temp button", false)
                            if (lastRun?.lastAPSRun != null && lastRun.constraintsProcessed?.isChangeRequested == true) {
                                runOnUiThread {
                                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                                        if (isAdded)
                                            OKDialog.showConfirmation(
                                                activity, rh.gs(app.aaps.core.ui.R.string.tempbasal_label), lastRun.constraintsProcessed?.resultAsSpanned()
                                                    ?: "".toSpanned(), {
                                                    uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Overview)
                                                    (context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(Constants.notificationID)
                                                    rxBus.send(EventMobileToWear(EventData.CancelNotification(dateUtil.now())))
                                                    handler.post { loop.acceptChangeRequest() }
                                                    binding.buttonsLayout.acceptTempButton.visibility = View.GONE
                                                })
                                    })
                                }
                            }
                        }
                    }
                }

                R.id.aps_mode            -> {
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded) uiInteraction.runLoopDialog(childFragmentManager, 1)
                    })
                }

                R.id.pump_status_layout  -> {
                    // Check if there is a bolus in progress
                    popupBolusDialogIfRunning(onClick = true)
                }
            }
        }
    }

    private fun openCgmApp(packageName: String) {
        context?.let {
            val packageManager = it.packageManager
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName) ?: throw ActivityNotFoundException()
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                it.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                aapsLogger.debug(LTag.CORE, "Error opening CGM app")
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (v.id) {
            R.id.quick_wizard_button -> {
                startActivity(Intent(v.context, uiInteraction.quickWizardListActivity))
                return true
            }

            R.id.aps_mode            -> {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        uiInteraction.runLoopDialog(childFragmentManager, 0)
                    })
                }
            }

            R.id.temp_target         -> v.performClick()
            R.id.active_profile      -> activity?.let { activity ->
                if (loop.runningMode == RM.Mode.DISCONNECTED_PUMP) OKDialog.show(activity, rh.gs(R.string.not_available_full), rh.gs(R.string.smscommunicator_pump_disconnected))
                else
                    protectionCheck.queryProtection(
                        activity,
                        ProtectionCheck.Protection.BOLUS,
                        UIRunnable { uiInteraction.runProfileSwitchDialog(childFragmentManager) })
            }

        }
        return false
    }

    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val pump = activePlugin.activePump
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints) || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    wizard.confirmAndExecute(it, quickWizardEntry)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processButtonsVisibility() {
        // Landscape: hide the whole buttons row (quick-wizard, treatment/wizard/carbs/insulin/etc.,
        // and the automation "user action" buttons in userButtonsLayout) rather than evaluating each
        // one's own visibility rule — screen space is tight sideways and none of them are needed to
        // just view the graph/status. Portrait falls through to the normal per-button logic below,
        // which restores each button to whatever its own condition dictates.
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            runOnUiThread {
                _binding ?: return@runOnUiThread
                binding.buttonsLayout.root.visibility = View.GONE
            }
            return
        } else {
            runOnUiThread {
                _binding ?: return@runOnUiThread
                binding.buttonsLayout.root.visibility = View.VISIBLE
            }
        }

        val lastBG = iobCobCalculator.ads.lastBg()
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val actualBG = iobCobCalculator.ads.actualBg()
        var list = ""

        // QuickWizard button
        val quickWizardEntry = quickWizard.getActive()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            if (quickWizardEntry != null && lastBG != null && profile != null && pump.isInitialized() && loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended()) {
                binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
                val wizard = quickWizardEntry.doCalc(profile, profileName, lastBG)
                binding.buttonsLayout.quickWizardButton.text = quickWizardEntry.buttonText() + "\n" + rh.gs(app.aaps.core.objects.R.string.format_carbs, quickWizardEntry.carbs()) +
                    " " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.calculatedTotalInsulin)
                if (wizard.calculatedTotalInsulin <= 0) binding.buttonsLayout.quickWizardButton.visibility = View.GONE
            } else binding.buttonsLayout.quickWizardButton.visibility = View.GONE
        }

        // **** Temp button ****
        val lastRun = loop.lastRun
        val resultAvailable =
            lastRun != null &&
            (lastRun.lastOpenModeAccept == 0L || lastRun.lastOpenModeAccept < lastRun.lastAPSRun) &&// never accepted or before last result
            lastRun.constraintsProcessed?.isChangeRequested == true // change is requested

        runOnUiThread {
            _binding ?: return@runOnUiThread
            if (resultAvailable && pump.isInitialized() && loop.runningMode == RM.Mode.OPEN_LOOP && (loop as PluginBase).isEnabled()) {
                binding.buttonsLayout.acceptTempButton.visibility = View.VISIBLE
                binding.buttonsLayout.acceptTempButton.text = "${rh.gs(R.string.set_basal_question)}\n${lastRun.constraintsProcessed?.resultAsString()}"
            } else {
                binding.buttonsLayout.acceptTempButton.visibility = View.GONE
            }

            // **** Various treatment buttons ****
            binding.buttonsLayout.carbsButton.visibility =
                (profile != null && preferences.get(BooleanKey.OverviewShowCarbsButton)).toVisibility()
            binding.buttonsLayout.treatmentButton.visibility = (loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized() && profile != null
                && preferences.get(BooleanKey.OverviewShowTreatmentButton)).toVisibility()
            binding.buttonsLayout.wizardButton.visibility = (loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized() && profile != null
                && preferences.get(BooleanKey.OverviewShowWizardButton)).toVisibility()
            binding.buttonsLayout.insulinButton.visibility = (profile != null && preferences.get(BooleanKey.OverviewShowInsulinButton)).toVisibility()
            if (loop.runningMode == RM.Mode.DISCONNECTED_PUMP || pump.isSuspended() || !pump.isInitialized()) {
                setRibbon(
                    binding.buttonsLayout.insulinButton,
                    app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                    app.aaps.core.ui.R.attr.ribbonWarningColor,
                    rh.gs(app.aaps.core.ui.R.string.overview_insulin_label)
                )
            } else {
                setRibbon(
                    binding.buttonsLayout.insulinButton,
                    app.aaps.core.ui.R.attr.icBolusColor,
                    app.aaps.core.ui.R.attr.ribbonDefaultColor,
                    rh.gs(app.aaps.core.ui.R.string.overview_insulin_label)
                )
            }

            // **** Calibration & CGM buttons ****
            val xDripIsBgSource = xDripSource.isEnabled()
            val dexcomIsSource = dexcomBoyda.isEnabled()
            binding.buttonsLayout.calibrationButton.visibility = (xDripIsBgSource && actualBG != null && preferences.get(BooleanKey.OverviewShowCalibrationButton)).toVisibility()
            if (dexcomIsSource) {
                binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(R.drawable.ic_byoda), null, null)
                for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                    drawable?.mutate()
                    drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, app.aaps.core.ui.R.attr.cgmDexColor), PorterDuff.Mode.SRC_IN)
                }
                binding.buttonsLayout.cgmButton.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.cgmDexColor))
            } else if (xDripIsBgSource) {
                binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(app.aaps.core.objects.R.drawable.ic_xdrip), null, null)
                for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                    drawable?.mutate()
                    drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, app.aaps.core.ui.R.attr.cgmXdripColor), PorterDuff.Mode.SRC_IN)
                }
                binding.buttonsLayout.cgmButton.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.cgmXdripColor))
            }
            binding.buttonsLayout.cgmButton.visibility = (preferences.get(BooleanKey.OverviewShowCgmButton) && (xDripIsBgSource || dexcomIsSource)).toVisibility()

            // Automation buttons
            binding.buttonsLayout.userButtonsLayout.removeAllViews()

            // Direct Kotlin MJ button: no AutomationEvent is created or executed. The state controls
            // which of the mutually-exclusive buttons is visible; AutoISF checks it again on execution.
            val mjButtonBaseAllowed =
                preferences.get(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF"
                    } catch (_: Exception) {
                        false
                    }
            val mjState = if (mjButtonBaseAllowed) {
                try {
                    automationStateService.getState("MJ")
                } catch (_: IllegalStateException) {
                    null
                }
            } else null
            val mjAction = when (mjState) {
                "NOMJremains" -> EventMjUserAction.Action.START
                null -> null
                else -> EventMjUserAction.Action.RESTORE
            }
            if (mjAction != null) {
                val title = if (mjAction == EventMjUserAction.Action.START)
                    "Press if MJ or WANT 3 days of 80% as hypo concern"
                else
                    "Press if MJ dose 4+ days old (Or want to restore 100% Profile)"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable { rxBus.send(EventMjUserAction(mjAction)) }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            // Direct Kotlin port of the native "Steroids are Off? Turn Steroids ON" user action.
            // This deliberately has no live/virtual-pump restriction, so it is also available on
            // aapsVirtual. The exact native conditions are reproduced here and checked again by AutoISF.
            val steroidButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 6 &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("MJ") == "NOMJremains" &&
                            automationStateService.getState("Steroids") == "Steroids Off"
                    } catch (_: Exception) {
                        false
                    }
            if (steroidButtonAllowed) {
                val title = "Steroids are Off? Turn Steroids ON"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable { rxBus.send(EventSteroidUserAction()) }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val steroid130ButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    (profile as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                    profileName == "Steroid Profile110" &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("Steroids") == "SteroidsON"
                    } catch (_: Exception) {
                        false
                    }
            if (steroid130ButtonAllowed) {
                val title = "Steroids 110% are ON.. press to increase? to 130"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable {
                                    rxBus.send(
                                        EventSteroidUserAction(
                                            action = EventSteroidUserAction.Action.INCREASE_130
                                        )
                                    )
                                }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val steroid150ButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    (profile as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                    profileName == "Steroid Profile130" &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("Steroids") == "SteroidsON" &&
                            automationStateService.getState("MJ") == "NOMJremains"
                    } catch (_: Exception) {
                        false
                    }
            if (steroid150ButtonAllowed) {
                val title = "Steroids 130% are ON.. press to increase? to 150"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable {
                                    rxBus.send(
                                        EventSteroidUserAction(
                                            action = EventSteroidUserAction.Action.INCREASE_150
                                        )
                                    )
                                }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val steroid190ButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    (profile as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                    profileName == "Steroid Profile150" &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("Steroids") == "SteroidsON" &&
                            automationStateService.getState("MJ") == "NOMJremains"
                    } catch (_: Exception) {
                        false
                    }
            if (steroid190ButtonAllowed) {
                val title = "Steroids 150% are ON.. press to increase? to 190"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable {
                                    rxBus.send(
                                        EventSteroidUserAction(
                                            action = EventSteroidUserAction.Action.INCREASE_190
                                        )
                                    )
                                }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val steroid250ButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    (profile as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                    profileName == "Current Profile190Real" &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("Steroids") == "SteroidsON" &&
                            automationStateService.getState("MJ") == "NOMJremains"
                    } catch (_: Exception) {
                        false
                    }
            if (steroid250ButtonAllowed) {
                val title = "Steroids 190% are ON.. press to increase? to 250"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable {
                                    rxBus.send(
                                        EventSteroidUserAction(
                                            action = EventSteroidUserAction.Action.INCREASE_250
                                        )
                                    )
                                }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val steroidOffButtonAllowed =
                preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                    preferences.get(BooleanKey.AutomationStatesEnabled) &&
                    !config.AAPSCLIENT &&
                    (profile as? ProfileSealed.EPS)?.value?.originalPercentage == 100 &&
                    try {
                        activePlugin.activeAPS.algorithm.name == "AUTO_ISF" &&
                            automationStateService.getState("Steroids") == "SteroidsON" &&
                            automationStateService.getState("MJ") == "NOMJremains"
                    } catch (_: Exception) {
                        false
                    }
            if (steroidOffButtonAllowed) {
                val title = "Steroids are ON..or restart over 8.0 .turn OFF?"
                context?.let { buttonContext ->
                    SingleClickButton(buttonContext, null, app.aaps.core.ui.R.attr.customBtnStyle).also { button ->
                        button.setTextColor(rh.gac(buttonContext, app.aaps.core.ui.R.attr.userOptionColor))
                        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        button.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { params ->
                            params.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                        }
                        button.setPadding(rh.dpToPx(1), button.paddingTop, rh.dpToPx(1), button.paddingBottom)
                        button.text = title
                        button.setOnClickListener {
                            OKDialog.showConfirmation(
                                buttonContext,
                                rh.gs(R.string.run_question, title),
                                Runnable {
                                    rxBus.send(
                                        EventSteroidUserAction(
                                            action = EventSteroidUserAction.Action.TURN_OFF
                                        )
                                    )
                                }
                            )
                        }
                        binding.buttonsLayout.userButtonsLayout.addView(button)
                    }
                }
            }
            val events = automation.userEvents()
            if (!loop.runningMode.isSuspended() && pump.isInitialized() && profile != null && !config.showUserActionsOnWatchOnly())
                for (event in events)
                    if (event.isEnabled && event.canRun() && !(
                            preferences.get(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled) &&
                                event.title.lowercase(Locale.ROOT).replace(" ", "").let { title ->
                                    (title.contains("steroidsareoff") && title.contains("turnsteroidson")) ||
                                        (title.contains("steroids110") && title.contains("increase") && title.contains("130")) ||
                                        (title.contains("steroids130") && title.contains("increase") && title.contains("150")) ||
                                        (title.contains("steroids150") && title.contains("increase") && title.contains("190")) ||
                                        (title.contains("steroids190") && title.contains("increase") && title.contains("250")) ||
                                        (title.contains("steroidsareon") && title.contains("turnoff"))
                                }
                            )) {
                        context?.let { context ->
                            SingleClickButton(context, null, app.aaps.core.ui.R.attr.customBtnStyle).also {
                                it.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.userOptionColor))
                                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { l ->
                                    l.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                                }
                                it.setPadding(rh.dpToPx(1), it.paddingTop, rh.dpToPx(1), it.paddingBottom)
                                it.compoundDrawablePadding = rh.dpToPx(-4)
                                it.setCompoundDrawablesWithIntrinsicBounds(
                                    null,
                                    rh.gd(event.firstActionIcon() ?: app.aaps.core.ui.R.drawable.ic_user_options_24dp).also { icon ->
                                        icon?.setBounds(rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20))
                                    }, null, null
                                )
                                it.text = event.title
                                it.setOnClickListener {
                                    OKDialog.showConfirmation(context, rh.gs(R.string.run_question, event.title), { handler.post { automation.processEvent(event) } })
                                }
                                binding.buttonsLayout.userButtonsLayout.addView(it)
                                for (drawable in it.compoundDrawables) {
                                    drawable?.mutate()
                                    drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, app.aaps.core.ui.R.attr.userOptionColor), PorterDuff.Mode.SRC_IN)
                                }
                            }
                        }
                        list += event.hashCode()
                    }
            binding.buttonsLayout.userButtonsLayout.visibility = (binding.buttonsLayout.userButtonsLayout.childCount > 0).toVisibility()
        }
        if (list != lastUserAction) {
            // Synchronize Watch Tiles with overview
            lastUserAction = list
            rxBus.send(EventWearUpdateTiles())
        }
    }

    private fun processAps() {
        val pump = activePlugin.activePump

        // aps mode
        fun apsModeSetA11yLabel(stringRes: Int) {
            binding.infoLayout.apsMode.stateDescription = rh.gs(stringRes)
        }

        runOnUiThread {
            _binding ?: return@runOnUiThread
            if (pump.pumpDescription.isTempBasalCapable) {
                binding.infoLayout.apsMode.visibility = View.VISIBLE
                binding.infoLayout.apsModeText.visibility = View.VISIBLE
                when (loop.runningMode) {
                    RM.Mode.SUPER_BOLUS       -> {
                        binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_superbolus)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.superbolus)
                        binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.infoLayout.apsModeText.visibility = View.VISIBLE
                    }

                    RM.Mode.DISCONNECTED_PUMP -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_disconnected)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.disconnected)
                        binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.infoLayout.apsModeText.visibility = View.VISIBLE
                    }

                    RM.Mode.SUSPENDED_BY_PUMP -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.pumpsuspended)
                        binding.infoLayout.apsModeText.text = rh.gs(app.aaps.core.ui.R.string.pumpsuspended)
                        binding.infoLayout.apsModeText.visibility = View.GONE
                    }

                    RM.Mode.SUSPENDED_BY_USER -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.loopsuspended)
                        binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.infoLayout.apsModeText.visibility = View.VISIBLE
                    }

                    RM.Mode.SUSPENDED_BY_DST  -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.loop_suspended_by_dst)
                        binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                        binding.infoLayout.apsModeText.visibility = View.VISIBLE
                    }

                    RM.Mode.CLOSED_LOOP_LGS   -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_lgs)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.uel_lgs_loop_mode)
                        binding.infoLayout.apsModeText.visibility = View.GONE
                    }

                    RM.Mode.CLOSED_LOOP       -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.objects.R.drawable.ic_loop_closed)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.closedloop)
                        binding.infoLayout.apsModeText.visibility = View.GONE
                    }

                    RM.Mode.OPEN_LOOP         -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_open)
                        apsModeSetA11yLabel(app.aaps.core.ui.R.string.openloop)
                        binding.infoLayout.apsModeText.visibility = View.GONE
                    }

                    RM.Mode.DISABLED_LOOP     -> {
                        binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_disabled)
                        apsModeSetA11yLabel(R.string.disabled_loop)
                        binding.infoLayout.apsModeText.visibility = View.GONE
                    }

                    RM.Mode.RESUME            -> error("Invalid mode")
                }
            } else {
                // loop not supported by pump, hide aps mode
                binding.infoLayout.apsMode.visibility = View.GONE
                binding.infoLayout.apsModeText.visibility = View.GONE
            }

            // pump status from ns
            binding.pump.text = processedDeviceStatusData.pumpStatus(nsSettingsStatus)
            binding.pump.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.pump), processedDeviceStatusData.extendedPumpStatus) } }

            // OpenAPS status from ns
            binding.openaps.text = processedDeviceStatusData.openApsStatus
            binding.openaps.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.openaps), processedDeviceStatusData.extendedOpenApsStatus) } }

            // Uploader status from ns
            binding.uploader.text = processedDeviceStatusData.uploaderStatusSpanned
            binding.uploader.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.uploader), processedDeviceStatusData.extendedUploaderStatus) } }

            // AISF status from ns
            binding.aisfdebug.text = processedDeviceStatusData.aisfStatus
            binding.aisfdebug.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.aisfdebug), processedDeviceStatusData.extendedAisfStatus) } }
        }
    }

    private fun prepareGraphsIfNeeded(numOfGraphs: Int) {
        if (numOfGraphs != secondaryGraphs.size - 1) {
            //aapsLogger.debug("New secondary graph count ${numOfGraphs-1}")
            // rebuild needed
            secondaryGraphs.clear()
            secondaryGraphsLabel.clear()
            binding.graphsLayout.secondaryGraphs.removeAllViews()
            (1 until numOfGraphs).forEach { _ ->
                val relativeLayout = RelativeLayout(context)
                relativeLayout.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val graph = GraphViewWithCleanup(requireContext())
                graph.layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh.dpToPx(skinProvider.activeSkin().secondaryGraphHeight)).also { it.setMargins(0, rh.dpToPx(0), 0, rh.dpToPx(0)) }
                    //LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh.dpToPx(skinProvider.activeSkin().secondaryGraphHeight)).also { it.setMargins(0, rh.dpToPx(15), 0, rh.dpToPx(10)) }
                graph.gridLabelRenderer?.gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
                graph.gridLabelRenderer?.reloadStyles()
                graph.gridLabelRenderer?.isHorizontalLabelsVisible = false
                graph.gridLabelRenderer?.labelVerticalWidth = axisWidth
                graph.gridLabelRenderer?.numVerticalLabels = 3
                graph.viewport.backgroundColor = rh.gac(context, app.aaps.core.ui.R.attr.viewPortBackgroundColor)
                relativeLayout.addView(graph)

                val label = TextView(context)
                //val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(rh.dpToPx(30), rh.dpToPx(25), 0, 0) }
                val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(rh.dpToPx(35), rh.dpToPx(5), 0, 0) }
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                label.layoutParams = layoutParams
                relativeLayout.addView(label)
                secondaryGraphsLabel.add(label)

                binding.graphsLayout.secondaryGraphs.addView(relativeLayout)
                secondaryGraphs.add(graph)
            }
        }
    }

    var task: Runnable? = null

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {

            override fun run() {
                refreshAll()
                task = null
            }
        }
        task?.let { handler.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler.postDelayed(it, 500) }
    }

    // Throttle for the green-line/steps-row position auto-refresh below — independent of the IOB
    // long-press, which still refreshes it immediately/unthrottled on demand.
    private var lastAnnotationPositionAutoRefresh = 0L

    // Latest IOB dialog text, refreshed every updateIobCob() cycle — read by iobGestureDetector below
    // at tap time, so the detector itself doesn't need to be rebuilt each refresh just to close over a
    // fresh value.
    private var iobDialogTextCached = ""
    private var basalDialogTextCached = ""

    private enum class BasalDirectAction(val label: String, val clientRelayMmol: Double) {
        MJ_START("MJ start", 5.158),
        MJ_RESTORE("MJ restore", 5.160),
        STEROID_START("Steroid start", 5.162),
        MJ_BUTTONS_TOGGLE("MJ Kotlin buttons on/off", 5.164),
        STEROID_BUTTON_TOGGLE("Steroid Kotlin button on/off", 5.166),
        STEROID_INCREASE_130("Steroid increase 110 to 130", 5.168),
        STEROID_INCREASE_150("Steroid increase 130 to 150", 5.170),
        STEROID_INCREASE_190("Steroid increase 150 to 190", 5.172),
        STEROID_INCREASE_250("Steroid increase 190 to 250", 5.174),
        STEROID_TURN_OFF("Steroids OFF", 5.176),
        ANYDESK_RESTART("Send AnyDesk restart", 5.178),
        // Local-test-only companion: records the same local "ADesk" click Note, then queues a fresh
        // command revision without depending on an NS round-trip or TT. The receiving handler writes
        // a route/device-specific AcLTx Note when it accepts that queued trigger.
        ANYDESK_LOCAL_TEST("Send AnyDesk restart (local test)", 5.180)
    }

    // Local display-only graph settings for the three raw/noise-derived UKF comparison lines (UKF1 =
    // rawBgSmoothedSeries, UKF2 = libreSpecialPreUkfSeries, UKF3 = libreSpecialFromUkf1Series -- see
    // PrepareBgDataWorker.kt). Appended to double-tap list2 (basal rate icon area, see
    // showBasalDirectActionListDialog() below), not list1 (IOB icon, showTtCodesListDialog()) -- moved
    // here per explicit request. Never relayed via TT the way BasalDirectAction's mmol codes are -- reads/writes
    // BooleanKey preferences directly on whichever device the double-tap happens on, since graph
    // rendering is inherently per-device. calibKey null (UKF2) means that line's underlying value is
    // already calibrated upstream and has no real toggle to offer -- shown as a disabled, checked box
    // instead of omitted, so all three popups keep the same two-checkbox shape.
    // syncedLiveKey removed 2026-08-16 (UKF3426 branch): it used to write FslUseUkfLibreSpecialSmoothing
    // (a real, standalone dosing-engine-selection preference -- LibreSpecial vs UKFset1) whenever this
    // entry's "Graph on" box was checked, as a workaround for the UKF2 graph history going stale
    // whenever UKFset1 was the live engine -- checking a display checkbox silently switched what was
    // dosing you. That root cause is now fixed directly (smoothLibreSpecialRealtime() is called
    // unconditionally in both live branches of XdripSourcePlugin.kt/NsIncomingDataProcessor.kt, not
    // gated on this preference), so "Graph on" is purely a display toggle again for all three entries,
    // same as UKF1/UKF3 always were. FslUseUkfLibreSpecialSmoothing itself is unchanged/untouched here.
    private data class GraphToggleEntry(
        val label: String, val showKey: BooleanKey, val calibKey: BooleanKey?, val calibLabel: String
    )

    // Order matters: appended after every BasalDirectAction entry (including ANYDESK_RESTART when
    // present), so UKF3 is always the last row in the combined list regardless of build type.
    private val graphToggleEntries = listOf(
        GraphToggleEntry("Graph: UKF1 raw-smoothed", BooleanKey.ShowUkf1Graph, BooleanKey.Ukf1ApplyLibreCalibration, "Use libre slope & offset"),
        GraphToggleEntry(
            "Graph: UKF2 LibreSpecial+UKF", BooleanKey.ShowUkf2Graph, null, "Use libre slope & offset (always on -- already calibrated upstream)"
        ),
        GraphToggleEntry("Graph: UKF3 LibreSpecial-from-UKF1", BooleanKey.ShowUkf3Graph, BooleanKey.Ukf3ApplyLibreCalibration, "Use libre slope & offset"),
        // Second checkbox here is repurposed (not calibration): ON = hide insulin activity + all 3
        // carb-related series, BGL/basal/annotation rows stay. Independent of the other entries above.
        GraphToggleEntry("Graph: Graph5 panel", BooleanKey.ApsAutoIsfShowGraph5, BooleanKey.ApsAutoIsfGraph5BglOnly, "BGL only (hide IA/carbs x3)")
    )

    // Every List2 AnyDesk click records the same "ADesk" command Note on the device where it was
    // pressed. Client clicks can upload it to NS; local-test execution does not depend on that sync.
    // Has no repeat-interval guard of its own. Client also uses relay TT 5.178 only when no temporary
    // target is active; otherwise this Note is deliberately the sole transport so the real TT is
    // preserved.
    private fun saveAnyDeskRestartCommandNote(
        allowLocalTest: Boolean = false,
        onSaved: () -> Unit
    ) {
        if (!allowLocalTest && !config.AAPSCLIENT) return
        val note = "ADesk"
        val therapyEvent = TE(
            timestamp = NoteTimestampAllocator.next(dateUtil.now()),
            type = TE.Type.NOTE,
            glucoseUnit = profileFunction.getUnits()
        ).apply {
            this.note = note
            duration = TimeUnit.MINUTES.toMillis(1)
        }
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = if (allowLocalTest) "AnyDesk restart command (local test)" else "AnyDesk restart command",
            listValues = listOf(ValueWithUnit.SimpleString(note))
        ).subscribe(
            {
                rxBus.send(EventRefreshOverview("AnyDesk restart Note", true))
                onSaved()
            },
            { error -> aapsLogger.error(LTag.CORE, "Failed to save ADesk command Note", error) }
        )
    }

    // Queue the local test through the real receiving-side handler instead of bypassing it with a
    // direct broadcast. AcLTx means the trigger was accepted/dispatched, not that Tasker or AnyDesk
    // completed. Making the revision newer than both stored cursors also survives restarts.
    private fun queueAnyDeskRestartLocalTest() {
        val commandRevision = preferences.get(LongKey.ApsAutoIsfAnyDeskSecondaryCommandAt)
        val handledRevision = preferences.get(LongKey.ApsAutoIsfAnyDeskTaskerHandledAt)
        val nextRevision = maxOf(dateUtil.now(), commandRevision + 1L, handledRevision + 1L)
        preferences.put(LongKey.ApsAutoIsfAnyDeskSecondaryCommandAt, nextRevision)
        preferences.put(LongKey.ApsAutoIsfAnyDeskLocalCommandAt, nextRevision)
        aapsLogger.info(LTag.CORE, "ADesk local-test command queued for local Tasker dispatch")
        rxBus.send(EventRefreshOverview("AnyDesk local trigger queued", true))
    }

    private fun runBasalDirectAction(action: BasalDirectAction) {
        if (action == BasalDirectAction.ANYDESK_LOCAL_TEST) {
            saveAnyDeskRestartCommandNote(allowLocalTest = true) {
                queueAnyDeskRestartLocalTest()
            }
            return
        }
        if (action == BasalDirectAction.ANYDESK_RESTART) {
            // Never cancel/replace a real temporary target merely to carry this command. If a TT is
            // active after the ADesk Note has been saved, secondary NS is the only transport. Re-check
            // here (not only in the confirmation text) because the active TT can change while the
            // confirmation dialog or asynchronous Note insert is in progress.
            val relayAllowed = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) == null
            saveAnyDeskRestartCommandNote {
                if (relayAllowed && persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) == null) {
                    setRelayTt(action.clientRelayMmol, "AnyDesk restart double-tap action")
                } else {
                    aapsLogger.info(LTag.CORE, "ADesk sent by NS Note only; existing temporary target preserved")
                    rxBus.send(EventRefreshOverview("AnyDesk NS-only; active TT preserved", true))
                }
            }
            return
        }
        if (config.AAPSCLIENT) {
            setRelayTt(action.clientRelayMmol, "basal double-tap action")
        } else {
            when (action) {
                BasalDirectAction.MJ_START ->
                    rxBus.send(EventMjUserAction(EventMjUserAction.Action.START, directMenu = true))

                BasalDirectAction.MJ_RESTORE ->
                    rxBus.send(EventMjUserAction(EventMjUserAction.Action.RESTORE, directMenu = true))

                BasalDirectAction.STEROID_START ->
                    rxBus.send(EventSteroidUserAction(directMenu = true))

                BasalDirectAction.STEROID_INCREASE_130 ->
                    rxBus.send(
                        EventSteroidUserAction(
                            action = EventSteroidUserAction.Action.INCREASE_130,
                            directMenu = true
                        )
                    )

                BasalDirectAction.STEROID_INCREASE_150 ->
                    rxBus.send(
                        EventSteroidUserAction(
                            action = EventSteroidUserAction.Action.INCREASE_150,
                            directMenu = true
                        )
                    )

                BasalDirectAction.STEROID_INCREASE_190 ->
                    rxBus.send(
                        EventSteroidUserAction(
                            action = EventSteroidUserAction.Action.INCREASE_190,
                            directMenu = true
                        )
                    )

                BasalDirectAction.STEROID_INCREASE_250 ->
                    rxBus.send(
                        EventSteroidUserAction(
                            action = EventSteroidUserAction.Action.INCREASE_250,
                            directMenu = true
                        )
                    )

                BasalDirectAction.STEROID_TURN_OFF ->
                    rxBus.send(
                        EventSteroidUserAction(
                            action = EventSteroidUserAction.Action.TURN_OFF,
                            directMenu = true
                        )
                    )

                BasalDirectAction.MJ_BUTTONS_TOGGLE,
                BasalDirectAction.STEROID_BUTTON_TOGGLE ->
                    rxBus.send(EventAutoIsfDirectTtCode(action.clientRelayMmol))

                // Unreachable here -- the early-return guards above (action == ANYDESK_RESTART /
                // ANYDESK_LOCAL_TEST) always exit before this when is reached for either case. Listed
                // explicitly (not folded into an else) so a genuinely new BasalDirectAction value still
                // fails to compile until handled.
                BasalDirectAction.ANYDESK_RESTART -> Unit
                BasalDirectAction.ANYDESK_LOCAL_TEST -> Unit
            }
        }
    }

    // Entry point for double-tap list2 (basal rate icon area): BasalDirectAction's real actions,
    // followed by the 3 GraphToggleEntry rows appended at the end (UKF3 last -- see graphToggleEntries'
    // doc comment). Extracted out of basalGestureDetector.onDoubleTap so that cancelling an INNER
    // confirmation/popup can re-show this same list instead of dismissing out to the plain Overview
    // screen behind it -- same pattern/rationale as showTtCodesListDialog() on list1 (IOB icon area).
    // OKDialog.showConfirmation's 5-arg (title, message, ok, cancel) overload is used instead of the
    // simpler 3-arg one specifically so a cancel callback can be supplied.
    private fun basalDirectActionConfirmation(action: BasalDirectAction): String {
        if (action != BasalDirectAction.ANYDESK_RESTART || !config.AAPSCLIENT) {
            return rh.gs(R.string.run_question, action.label)
        }
        return if (persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null) {
            "An active temporary target exists and will be preserved.\n\n" +
                "Send the AnyDesk restart by ADesk NS Note only? No relay TT will be created."
        } else {
            "No active temporary target exists.\n\n" +
                "Send the AnyDesk restart by ADesk NS Note and a 5-minute relay TT?\n\n" +
                "Warning: the relay TT will temporarily become the active target."
        }
    }

    private fun showBasalDirectActionListDialog() {
        activity?.let { act ->
            val actionEntries = BasalDirectAction.values().filter {
                it != BasalDirectAction.ANYDESK_RESTART || config.AAPSCLIENT
            }
            val labels = actionEntries.map { it.label } + graphToggleEntries.map { it.label }
            val adapter = object : ArrayAdapter<String>(act, 0, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = convertView as? TextView ?: TextView(act).apply {
                        setPadding(24, 8, 24, 8)
                        textSize = 15f
                    }
                    tv.text = getItem(position)
                    return tv
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(if (config.AAPSCLIENT) "Actions - relay to pump" else "Direct actions")
                .setAdapter(adapter) { _, which ->
                    if (which < actionEntries.size) {
                        val action = actionEntries[which]
                        OKDialog.showConfirmation(
                            act,
                            act.getString(app.aaps.core.ui.R.string.confirmation),
                            basalDirectActionConfirmation(action),
                            Runnable { runBasalDirectAction(action) },
                            Runnable { showBasalDirectActionListDialog() }
                        )
                    } else {
                        val entry = graphToggleEntries[which - actionEntries.size]
                        val calibBox = CheckBox(act).apply {
                            text = entry.calibLabel
                            isChecked = entry.calibKey?.let { preferences.get(it) } ?: true
                            isEnabled = entry.calibKey != null
                        }
                        val showBox = CheckBox(act).apply {
                            text = "Graph on"
                            isChecked = preferences.get(entry.showKey)
                        }
                        val container = LinearLayout(act).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(48, 24, 48, 0)
                            addView(calibBox)
                            addView(showBox)
                        }
                        androidx.appcompat.app.AlertDialog.Builder(act)
                            .setTitle(entry.label)
                            .setView(container)
                            .setPositiveButton(rh.gs(app.aaps.core.ui.R.string.ok)) { _, _ ->
                                preferences.put(entry.showKey, showBox.isChecked)
                                entry.calibKey?.let { preferences.put(it, calibBox.isChecked) }
                                rxBus.send(EventRefreshOverview("UKF graph setting changed", true))
                            }
                            .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel)) { _, _ -> showBasalDirectActionListDialog() }
                            .setOnCancelListener { showBasalDirectActionListDialog() }
                            .show()
                    }
                }
                .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel), null)
                .show()
        }
    }

    private val basalGestureDetector by lazy {
        android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.basal), basalDialogTextCached) }
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                showBasalDirectActionListDialog()
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                PointsWithLabelGraphSeries.basalToggleIndex += 1
                PointsWithLabelGraphSeries.carbLine1QuickShow = PointsWithLabelGraphSeries.basalToggleIndex != 0
                rxBus.send(EventRefreshOverview("toggleBglArrowheads", now = true))
            }
        })
    }

    // Created ONCE (not per-refresh) — double-tap detection needs the same detector instance to see
    // both taps of a pair. Rebuilding it inside updateIobCob()'s periodic refresh meant a refresh
    // landing between the two taps of a double-tap swapped in a brand-new detector that never saw the
    // first tap, so every double-tap attempt was silently evaluated as two isolated single-taps
    // instead (each firing immediately once ITS OWN detector's timeout passed).
    private val iobGestureDetector by lazy {
        android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.iob), iobDialogTextCached) }
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                showTtCodesListDialog()
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                PointsWithLabelGraphSeries.showSmbLabels = !PointsWithLabelGraphSeries.showSmbLabels
                // Always reset the basal-toggle preset back to 0 (normal ISF colors, transparent noisy
                // line) regardless of which direction showSmbLabels just went — a "reset to normal" for
                // the other display settings, independent of the SMB-label state.
                PointsWithLabelGraphSeries.basalToggleIndex = 0
                // A direct IOB-icon long-press forces line1 (Carbs Absorption) ON, grouped with
                // push1/push2's behavior on the basal icon -- this is deliberate despite this same press
                // ALSO resetting basalToggleIndex to 0 above: that reset is this action's own unrelated
                // side effect, not a push0 press, so it must not turn line1 off (see carbLine1QuickShow's
                // doc comment).
                PointsWithLabelGraphSeries.carbLine1QuickShow = true
                // Re-decide the green-line annotation's top/under-target position from the current BGL,
                // right now — this is the only place that decision gets refreshed (see
                // refreshAnnotationPosition() doc comment); draw() no longer recomputes it every redraw.
                PointsWithLabelGraphSeries.refreshAnnotationPosition()
                rxBus.send(EventRefreshOverview("toggleSmbLabels", now = true))
            }
        })
    }

    @SuppressLint("SetTextI18n")
    fun updateBg() {
        val lastBg = lastBgData.lastBg()
        val lastBgColor = lastBgData.lastBgColor(context)
        val isActualBg = lastBgData.isActualBg()
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads)
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val lastBgDescription = lastBgData.lastBgDescription()
        // Feed the graph's live-position green-line annotation (see PointsWithLabelGraphSeries):
        // current BGL (raw mg/dL — only ever compared against mg/dL literal thresholds there) and the
        // active profile's target (low==high target, per user's own profile setup). The target MUST be
        // converted to the user's display units here, via the same profileUtil.fromMgdlToUnits() the
        // graph's own data points use for their Y value — the target is compared against the graph's
        // Y-axis/viewport, which is scaled in display units, not mg/dL. (A prior version stored raw
        // mg/dL there and the label rendered off-canvas as a result — this fixes that.)
        lastBg?.recalculated?.let { PointsWithLabelGraphSeries.currentBgMgdl = it }
        profileFunction.getProfile()?.getTargetLowMgdl()?.let {
            PointsWithLabelGraphSeries.currentTargetInDisplayUnits = profileUtil.fromMgdlToUnits(it)
        }
        // Also re-check the green-line/steps-row HIGH/LOW position every 15 min on its own, not just on
        // the IOB long-press — same refreshAnnotationPosition() the long-press calls.
        if (dateUtil.now() - lastAnnotationPositionAutoRefresh >= 15 * 60_000L) {
            PointsWithLabelGraphSeries.refreshAnnotationPosition()
            lastAnnotationPositionAutoRefresh = dateUtil.now()
        }
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.bg.text = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
            binding.infoLayout.bg.setTextColor(lastBgColor)
            trendArrow?.let { binding.infoLayout.arrow.setImageResource(it.directionToIcon()) }
            binding.infoLayout.arrow.visibility = (trendArrow != null).toVisibilityKeepSpace()
            binding.infoLayout.arrow.setColorFilter(lastBgColor)
            binding.infoLayout.arrow.contentDescription = lastBgDescription + " " + rh.gs(app.aaps.core.ui.R.string.and) + " " + trendDescription

            if (glucoseStatus != null) {
                binding.infoLayout.deltaLarge.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                binding.infoLayout.deltaLarge.setTextColor(lastBgColor)
                binding.infoLayout.delta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                binding.infoLayout.avgDelta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.shortAvgDelta)
                binding.infoLayout.longAvgDelta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.longAvgDelta)
            } else {
                binding.infoLayout.deltaLarge.text = ""
                binding.infoLayout.delta.text = "Δ " + rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
                binding.infoLayout.avgDelta.text = ""
                binding.infoLayout.longAvgDelta.text = ""
            }

            // strike through if BG is old
            binding.infoLayout.bg.paintFlags =
                if (!isActualBg) binding.infoLayout.bg.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else binding.infoLayout.bg.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            val outDate = (if (!isActualBg) rh.gs(R.string.a11y_bg_outdated) else "")
            binding.infoLayout.bg.contentDescription = rh.gs(R.string.a11y_blood_glucose) + " " + binding.infoLayout.bg.text.toString() + " " + lastBgDescription + " " + outDate

            binding.infoLayout.timeAgo.text = dateUtil.minOrSecAgo(rh, lastBg?.timestamp)
            binding.infoLayout.timeAgo.contentDescription = dateUtil.minAgoLong(rh, lastBg?.timestamp)
            binding.infoLayout.timeAgoShort.text = dateUtil.minAgoShort(lastBg?.timestamp)

            val qualityIcon = bgQualityCheck.icon()
            if (qualityIcon != 0) {
                binding.infoLayout.bgQuality.visibility = View.VISIBLE
                binding.infoLayout.bgQuality.setImageResource(qualityIcon)
                binding.infoLayout.bgQuality.contentDescription = rh.gs(R.string.a11y_bg_quality) + " " + bgQualityCheck.stateDescription()
                binding.infoLayout.bgQuality.setOnClickListener {
                    context?.let { context -> OKDialog.show(context, rh.gs(R.string.data_status), bgQualityCheck.message) }
                }
            } else {
                binding.infoLayout.bgQuality.visibility = View.GONE
            }
            binding.infoLayout.simpleMode.visibility = preferences.simpleMode.toVisibility()
        }
    }

    private fun updateProfile() {
        val profile = profileFunction.getProfile()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            val profileBackgroundColor = profile?.let {
                if (it is ProfileSealed.EPS) {
                    if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                        app.aaps.core.ui.R.attr.ribbonWarningColor
                    else app.aaps.core.ui.R.attr.ribbonDefaultColor
                } else app.aaps.core.ui.R.attr.ribbonDefaultColor
            } ?: app.aaps.core.ui.R.attr.ribbonCriticalColor

            val profileTextColor = profile?.let {
                if (it is ProfileSealed.EPS) {
                    if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                        app.aaps.core.ui.R.attr.ribbonTextWarningColor
                    else app.aaps.core.ui.R.attr.ribbonTextDefaultColor
                } else app.aaps.core.ui.R.attr.ribbonTextDefaultColor
            } ?: app.aaps.core.ui.R.attr.ribbonTextDefaultColor
            setRibbon(binding.activeProfile, profileTextColor, profileBackgroundColor, profileFunction.getProfileNameWithRemainingTime())
        }
    }

    private fun updateTemporaryBasal() {
        val temporaryBasalText = overviewData.temporaryBasalText()
        val temporaryBasalColor = overviewData.temporaryBasalColor(context)
        val temporaryBasalIcon = overviewData.temporaryBasalIcon()
        val temporaryBasalDialogText = overviewData.temporaryBasalDialogText()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.baseBasal.text = temporaryBasalText
            binding.infoLayout.baseBasal.setTextColor(temporaryBasalColor)
            binding.infoLayout.baseBasalIcon.setImageResource(temporaryBasalIcon)
            basalDialogTextCached = temporaryBasalDialogText
            binding.infoLayout.basalLayout.setOnTouchListener { _, event ->
                basalGestureDetector.onTouchEvent(event)
                true
            }
        }
    }

    private fun updateExtendedBolus() {
        val pump = activePlugin.activePump
        val extendedBolus = persistenceLayer.getExtendedBolusActiveAt(dateUtil.now())
        val extendedBolusText = overviewData.extendedBolusText()
        val extendedBolusDialogText = overviewData.extendedBolusDialogText()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.extendedBolus.text = extendedBolusText
            binding.infoLayout.extendedLayout.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.extended_bolus), extendedBolusDialogText) } }
            binding.infoLayout.extendedLayout.visibility = (extendedBolus != null && !pump.isFakingTempsByExtendedBoluses).toVisibility()
        }
    }

    private fun updateTime() {
        _binding ?: return
        binding.graphsLayout.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)
        binding.infoLayout.time.text = dateUtil.timeString(dateUtil.now())
        // Status lights
        val pump = activePlugin.activePump
        val isPatchPump = pump.pumpDescription.isPatchPump
        binding.statusLightsLayout.apply {
            cannulaOrPatch.setImageResource(if (isPatchPump) app.aaps.core.objects.R.drawable.ic_patch_pump_outline else R.drawable.ic_cp_age_cannula)
            cannulaOrPatch.contentDescription = rh.gs(if (isPatchPump) R.string.statuslights_patch_pump_age else R.string.statuslights_cannula_age)
            insulinAge.visibility = isPatchPump.not().toVisibility()
            batteryLayout.visibility = (!isPatchPump || pump.pumpDescription.useHardwareLink).toVisibility()
            pbAge.visibility = (pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()).toVisibility()
            val useBatteryLevel = (pump.model() == PumpType.OMNIPOD_EROS)
                || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)
            pbLevel.visibility = useBatteryLevel.toVisibility()
            statusLightsLayout.visibility = (preferences.get(BooleanKey.OverviewShowStatusLights) || config.AAPSCLIENT).toVisibility()
        }
        statusLightHandler.updateStatusLights(
            binding.statusLightsLayout.cannulaAge,
            null,
            binding.statusLightsLayout.insulinAge,
            binding.statusLightsLayout.reservoirLevel,
            binding.statusLightsLayout.sensorAge,
            null,
            binding.statusLightsLayout.pbAge,
            binding.statusLightsLayout.pbLevel
        )
    }

    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()
    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
    private fun iobText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob)

    private fun iobDialogText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.basal) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob().basaliob)

    // Entry point for double-tap list1 (IOB graph area / TT-nudge settings, ttCodesList() below).
    // Extracted out of iobGestureDetector.onDoubleTap so that cancelling an INNER confirmation dialog
    // (a TtCode.Single "Set <name>?" popup, or a TtCode.Stepped -/+ picker) can re-show this same list
    // instead of just dismissing out to the plain Overview screen behind it -- see the two inner
    // .setNegativeButton(cancel) callbacks below, which call this function again instead of passing
    // null (plain no-op dismiss). The OUTER list's own Cancel stays a no-op dismiss on purpose --
    // cancelling the list itself is meant to close out to the main screen, only cancelling a
    // confirmation popup should return to the list. See showBasalDirectActionListDialog() for list2
    // (basal rate icon area).
    private fun showTtCodesListDialog() {
        activity?.let { act ->
            val ukf1LiveComparisonAllowed = activePlugin.activePump is VirtualPump && !config.AAPSCLIENT
            val entries = ttCodesList().filter { entry ->
                entry !is TtCode.Single || entry.value != 5.152 || ukf1LiveComparisonAllowed
            }
            // Compact adapter (small text, minimal padding) instead of the default select_dialog_item
            // row — that one reserves ~48dp min height per row plus its own vertical padding, which
            // wastes a lot of space on a narrow phone with this many entries. No functional difference,
            // just tighter rows with no gap between them.
            val adapter = object : ArrayAdapter<String>(act, 0, entries.map { it.label }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = convertView as? TextView ?: TextView(act).apply {
                        setPadding(24, 2, 24, 2)
                        textSize = 13f
                    }
                    tv.text = getItem(position)
                    return tv
                }
            }
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(if (config.AAPSCLIENT) "Settings - relay to pump" else "Direct AutoISF settings")
                .setAdapter(adapter) { _, which ->
                    // The mmol number (and, for Stepped, which direction) only ever surface here, never
                    // in the outer list -- picking a button both chooses and confirms.
                    when (val entry = entries[which]) {
                        is TtCode.Single  ->
                            androidx.appcompat.app.AlertDialog.Builder(act)
                                .setTitle(entry.label)
                                .also { builder -> entry.currentValue?.let { reader -> builder.setMessage(displayedTtCurrentValue(entry, reader)) } }
                                .setPositiveButton(rh.gs(app.aaps.core.ui.R.string.ok)) { _, _ -> applyTtControl(entry.value) }
                                .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel)) { _, _ -> showTtCodesListDialog() }
                                // setNegativeButton alone only catches an explicit tap on the Cancel
                                // button -- back-press or tapping outside the dialog goes through
                                // onCancel() instead, a separate callback that bypasses it entirely.
                                // Without this, those two dismiss paths fell straight through to the
                                // main screen exactly as before this feature existed.
                                .setOnCancelListener { showTtCodesListDialog() }
                                .show()

                        // Selection (checkbox) is decoupled from confirmation (OK/Cancel) here, unlike
                        // Single above -- picking a direction no longer immediately applies it, so a
                        // stray tap on the wrong direction can be corrected before confirming.
                        // Checkboxes behave as a mutually-exclusive pair (same idiom as
                        // OverviewMenusImpl.createCustomMenuItemView's per-row checkboxes) — ticking one
                        // clears the other. Labeled with the real magnitude each direction applies
                        // (entry.downLabel/upLabel, e.g. "-0.1"/"+0.1"), pulled from the matching
                        // *DownTT/*UpTT block in OpenAPSAutoISFPlugin.kt — see the comment on
                        // TtCode.Stepped below.
                        is TtCode.Stepped -> {
                            val downBox = CheckBox(act).apply { text = entry.downLabel }
                            val upBox = CheckBox(act).apply { text = entry.upLabel }
                            downBox.setOnCheckedChangeListener { _, checked -> if (checked) upBox.isChecked = false }
                            upBox.setOnCheckedChangeListener { _, checked -> if (checked) downBox.isChecked = false }
                            val container = LinearLayout(act).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(48, 24, 48, 0)
                                entry.currentValue?.let { reader ->
                                    addView(TextView(act).apply {
                                        text = displayedTtCurrentValue(entry, reader)
                                        setPadding(0, 0, 0, 16)
                                    })
                                }
                                addView(downBox)
                                addView(upBox)
                            }
                            androidx.appcompat.app.AlertDialog.Builder(act)
                                .setTitle(entry.label)
                                .setView(container)
                                .setPositiveButton(rh.gs(app.aaps.core.ui.R.string.ok)) { _, _ ->
                                    when {
                                        downBox.isChecked -> applyTtControl(entry.down)
                                        upBox.isChecked   -> applyTtControl(entry.up)
                                        // Neither checked: OK with no selection is a no-op, not an error.
                                    }
                                }
                                .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel)) { _, _ -> showTtCodesListDialog() }
                                .setOnCancelListener { showTtCodesListDialog() } // see TtCode.Single's comment on this above
                                .show()
                        }
                    }
                }
                .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel), null)
                .show()
        }
    }

    // One row per SETTING (not per TT code) — the outer double-tap list now shows only the setting
    // name, never the raw mmol number or which direction it nudges. Single: one-shot/toggle action,
    // tapping opens a plain "Set <name>?" confirm. Stepped: a -/+ pair, tapping opens a confirm with
    // both directions offered as buttons (pick one = confirm), so the mmol number/direction only ever
    // surface at that final step, never in the scannable list itself.
    private sealed class TtCode(val label: String) {
        // currentValue: same "what is it right now" mechanism as Stepped's below, for the boolean-toggle
        // rows (shown as the popup's plain message text, since Single has no checkbox container to add
        // a line above). For the two genuinely stateless one-shot triggers (clean grph view, Cloud logs
        // upload), there's nothing live to read, so this instead holds a fixed "Effect: ..." description
        // of what OK actually does -- same field, same display spot, just a constant string instead of a
        // preference reader, since the mechanism (show one line of context before confirming) is the
        // same need either way.
        class Single(label: String, val value: Double, val currentValue: (() -> String)? = null) : TtCode(label)
        // downLabel/upLabel: the actual magnitude each direction applies, pulled from the matching
        // *DownTT/*UpTT block in OpenAPSAutoISFPlugin.kt (not derivable from down/up themselves — those
        // are just this TT-signal's own mmol trigger values, unrelated in magnitude to the real setting
        // delta). Shown on the checkboxes in the confirm dialog below instead of a generic "down"/"up".
        // currentValue: optional "what is it right now" reader, shown as a plain text line above the
        // checkboxes in the confirm popup (see showTtCodesListDialog()) -- null for rows that don't map
        // to one single readable preference (a dual-key nudge like SMBdel base + mild-Bst, or a named
        // automation/profile state like MJ state or Profile override), so those popups look exactly as
        // before rather than show something misleading or half-right.
        class Stepped(label: String, val down: Double, val up: Double, val downLabel: String, val upLabel: String, val currentValue: (() -> String)? = null) : TtCode(label)
    }

    private fun mirroredAutoIsfSettings(): Map<String, String> =
        preferences.get(StringNonKey.MirroredAutoIsfSettings)
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(" = ")
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 3)
            }
            .toMap()

    private fun mirroredSettingsAge(): String {
        val timestamp = preferences.get(LongNonKey.MirroredAutoIsfSettingsTimestamp)
        return if (timestamp > 0L) " (${dateUtil.minOrSecAgo(rh, timestamp)})" else ""
    }

    /**
     * Client reads only the latest pump snapshot received with NS device status. Pump/Virtual still
     * uses each row's existing local reader and never comes through these mirror helpers.
     */
    private fun mirroredListSetting(
        key: String,
        remoteValue: (String) -> String = { it }
    ): String {
        val value = mirroredAutoIsfSettings()[key] ?: return "Pump current: unavailable"
        return "Pump current: ${remoteValue(value)}${mirroredSettingsAge()}"
    }

    private fun mirroredListSettingPair(
        firstLabel: String,
        firstKey: String,
        secondLabel: String,
        secondKey: String
    ): String {
        val mirrored = mirroredAutoIsfSettings()
        val first = mirrored[firstKey]
        val second = mirrored[secondKey]
        if (first == null && second == null) return "Pump current: unavailable"
        return "Pump current: $firstLabel=${first ?: "?"}, $secondLabel=${second ?: "?"}${mirroredSettingsAge()}"
    }

    private fun mirroredBoolean(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "true"  -> "ON"
        "false" -> "OFF"
        else    -> value
    }

    private fun mirroredProfileSetting(): String {
        val mirrored = mirroredAutoIsfSettings()
        val name = mirrored["configuration_profile"] ?: return "Pump current: unavailable"
        val roleLabel = when (name) {
            mirrored[StringKey.ApsAutoIsfStandardProfileName.key] -> "Standard"
            mirrored[StringKey.ApsAutoIsfLowProfileName.key]      -> "Low"
            else                                                   -> "neither"
        }
        return "Pump current: $roleLabel ($name)${mirroredSettingsAge()}"
    }

    private fun displayedTtCurrentValue(entry: TtCode, localValue: () -> String): String {
        if (!config.AAPSCLIENT) return localValue()
        val code = when (entry) {
            is TtCode.Single  -> entry.value
            is TtCode.Stepped -> entry.down
        }
        return when (code) {
            5.002 -> mirroredListSettingPair(
                "base", DoubleKey.ApsAutoIsfSmbDeliveryBaseline.key,
                "mildBst", DoubleKey.ApsAutoIsfMildBoostRatio.key
            )
            5.006 -> mirroredListSetting(BooleanKey.ApsAutoIsfOldSensorAdjEnabled.key) { mirroredBoolean(it) }
            5.008 -> mirroredListSetting(BooleanKey.ApsAutoIsfBoostAutomationsEnabled.key) { mirroredBoolean(it) }
            5.012 -> mirroredListSetting(DoubleKey.ApsAutoIsfPpWeightNormal.key)
            5.016 -> mirroredListSetting(DoubleKey.ApsAutoIsfBgAccelWeightNormal.key)
            5.022 -> mirroredListSetting(DoubleKey.ApsAutoIsfDuraWeightNormal.key)
            5.026 -> mirroredListSetting(DoubleKey.ApsAutoIsfLibreSlopeOrig.key)
            5.032 -> mirroredListSetting(DoubleKey.ApsAutoIsfLibreOffsetOrig.key)
            5.036 -> mirroredListSetting(DoubleKey.ApsAutoIsfSmbOffsetOverride.key)
            5.046 -> mirroredListSetting(IntKey.OverviewBolusPercentage.key) { "$it%" }
            5.052 -> mirroredListSetting(DoubleKey.ApsAutoIsfMildBoostRatio.key)
            5.056 -> mirroredListSetting(DoubleKey.ApsAutoIsfPpWeightHigh.key)
            5.062 -> mirroredListSetting(DoubleKey.ApsAutoIsfBgAccelWeightHigh.key)
            5.068 -> mirroredListSetting(DoubleKey.ApsAutoIsfHighBgWeight.key)
            5.074 -> mirroredListSetting(IntKey.InsulinOrefPeak.key) { "$it min" }
            5.080 -> mirroredListSetting(DoubleKey.ApsAutoIsfMaxLow.key)
            5.086 -> mirroredListSetting(DoubleKey.ApsAutoIsfMax.key)
            5.092 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset0002.key)
            5.098 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset0204.key)
            5.104 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset0406.key)
            5.110 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset0609.key)
            5.116 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset0912.key)
            5.122 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset1218.key)
            5.128 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset1822.key)
            5.134 -> mirroredListSetting(DoubleKey.ApsAutoIsfTodOffset2200.key)
            5.138 -> mirroredListSetting(BooleanKey.ApsAutoIsfShowCarbModelCurve.key) { mirroredBoolean(it) }
            5.142 -> mirroredListSetting(BooleanKey.ApsAutoIsfShowGraph5.key) { mirroredBoolean(it) }
            5.144 -> mirroredListSetting("automation_state_MJ")
            5.148 -> mirroredProfileSetting()
            5.152 -> mirroredListSetting(BooleanKey.FslUseUkfSmoothing.key) { mirroredBoolean(it) }
            5.156 -> mirroredListSetting(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled.key) { mirroredBoolean(it) }
            else  -> localValue()
        }
    }

    // Matches the *TT blocks in OpenAPSAutoISFPlugin.kt exactly. On a pump or Virtual build the chosen
    // handler runs locally without a TT and without its two-minute repeat guard. AAPSClient alone
    // creates the matching TT because preferences do not sync; the pump receives that relay, applies
    // the setting, cancels the TT, and retains the two-minute relay guard.
    // Abbreviated for narrow-phone display: weight->Wt, SMB delivery->SMBdel, boost->Bst, normal->N,
    // (orig)->(Or), baseline->base, override->HARD, (high/boosted)->(High).
    private fun ttCodesList(): List<TtCode> = listOf(
        // SmbDeliveryDownTT/UpTT nudges BOTH ApsAutoIsfSmbDeliveryBaseline and ApsAutoIsfMildBoostRatio, both by the same ±0.01.
        // Dual-key: SmbDeliveryDownTT/UpTT nudges BOTH keys by the same amount, so unlike the
        // single-key rows above, both current values are shown rather than picking just one.
        TtCode.Stepped(
            "SMBdel base + mild-Bst", 5.002, 5.004, "-0.01", "+0.01",
            currentValue = {
                "Current: base=${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfSmbDeliveryBaseline))}" +
                    ", mildBst=${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}"
            }
        ),
        TtCode.Single("Tog Libre sens on/off", 5.006, currentValue = { "Current: ${if (preferences.get(BooleanKey.ApsAutoIsfOldSensorAdjEnabled)) "ON" else "OFF"}" }),
        TtCode.Single("Tog Bst autos(all) on/off", 5.008, currentValue = { "Current: ${if (preferences.get(BooleanKey.ApsAutoIsfBoostAutomationsEnabled)) "ON" else "OFF"}" }),
        TtCode.Stepped("pp ISF Wt (Or)", 5.012, 5.014, "-0.01", "+0.01", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfPpWeightNormal))}" }),
        TtCode.Stepped("acce ISF Wt (Or)", 5.016, 5.018, "-0.05", "+0.05", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightNormal))}" }),
        TtCode.Stepped("dura ISF Wt (Or)", 5.022, 5.024, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfDuraWeightNormal))}" }),
        TtCode.Stepped("Libre slope (Or)", 5.026, 5.028, "-0.01", "+0.01", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfLibreSlopeOrig))}" }),
        TtCode.Stepped("Libre Offset (Or)", 5.032, 5.034, "-0.05", "+0.05", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfLibreOffsetOrig))}" }),
        TtCode.Stepped("SMB offset", 5.036, 5.038, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride))}" }),
        TtCode.Single(
            "clean grph view", 5.042,
            // Matches CleanGraphTT's own sendSms text in OpenAPSAutoISFPlugin.kt.
            currentValue = { "Effect: hides SMB dose labels/arrows, shows a plain solid green line only" }
        ),
        TtCode.Stepped("Wizard bolus %", 5.046, 5.048, "-5%", "+5%", currentValue = { "Current: ${preferences.get(IntKey.OverviewBolusPercentage)}%" }),
        // MildBoostDownTT/UpTT nudges ApsAutoIsfMildBoostRatio ALONE (unlike SMBdel base + mild-Bst above, which nudges it together with the baseline).
        TtCode.Stepped("MildBst ratio", 5.052, 5.054, "-0.01", "+0.01", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfMildBoostRatio))}" }),
        TtCode.Stepped("pp ISF Wt (High)", 5.056, 5.058, "-0.01", "+0.01", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfPpWeightHigh))}" }),
        TtCode.Stepped("acce ISF Wt (High)", 5.062, 5.064, "-0.01", "+0.01", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeightHigh))}" }),
        TtCode.Stepped("higher ISF range Wt", 5.068, 5.070, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfHighBgWeight))}" }),
        TtCode.Stepped("peak insulin time", 5.074, 5.076, "-5 min", "+5 min", currentValue = { "Current: ${preferences.get(IntKey.InsulinOrefPeak)} min" }),
        TtCode.Stepped("autoISF max (lowBG)", 5.080, 5.082, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfMaxLow))}" }),
        TtCode.Stepped("autoISF max (N)", 5.086, 5.088, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfMax))}" }),
        TtCode.Stepped("T1 tod offset 00-02h", 5.092, 5.094, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset0002))}" }),
        TtCode.Stepped("T2 tod offset 02-04h", 5.098, 5.100, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset0204))}" }),
        TtCode.Stepped("T3 tod offset 04-06h", 5.104, 5.106, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset0406))}" }),
        TtCode.Stepped("T4 tod offset 06-09h", 5.110, 5.112, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset0609))}" }),
        TtCode.Stepped("T5 tod offset 09-12h", 5.116, 5.118, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset0912))}" }),
        TtCode.Stepped("T6 tod offset 12-18h", 5.122, 5.124, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset1218))}" }),
        TtCode.Stepped("T7 tod offset 18-22h", 5.128, 5.130, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset1822))}" }),
        TtCode.Stepped("T8 tod offset 22-00h", 5.134, 5.136, "-0.1", "+0.1", currentValue = { "Current: ${"%.2f".format(preferences.get(DoubleKey.ApsAutoIsfTodOffset2200))}" }),
        TtCode.Single("Tog Graph2 (carb model curve) on/off", 5.138, currentValue = { "Current: ${if (preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)) "ON" else "OFF"}" }),
        TtCode.Single(
            "Cloud logs upload", 5.140,
            // Matches CloudLogsUploadTT's own doc comment in OpenAPSAutoISFPlugin.kt.
            currentValue = { "Effect: zips logs and sends to cloud storage if configured, else email (same as Maintenance screen's Send logs button)" }
        ),
        TtCode.Single("Tog Graph5 (main clone) on/off", 5.142, currentValue = { "Current: ${if (preferences.get(BooleanKey.ApsAutoIsfShowGraph5)) "ON" else "OFF"}" }),
        // Stepped rather than two Single rows: the two codes are alternative values of one setting (the
        // MJ state), so they belong behind one label as a mutually-exclusive checkbox pair — same shape
        // as the -0.1/+0.1 rows above, just with named states instead of a numeric delta. downLabel/
        // upLabel are the states themselves, matching MjStateNoMjTT/MjStateMj3TT in OpenAPSAutoISFPlugin.kt.
        TtCode.Stepped(
            "MJ state (manual override)", 5.144, 5.146, "NOMJremains", "MJ3",
            // Real current state value (not just an equality check against one candidate) -- MJ has more
            // values than just the two this row toggles between (e.g. "MJ active", "MJ2"), so this can
            // show something other than either checkbox label.
            currentValue = { "Current: ${automationStateService.getState("MJ").ifEmpty { "unset" }}" }
        ),
        // Same Stepped shape as the MJ row above: one label, the two profiles as a mutually-exclusive
        // checkbox pair. Labels are the two ROLES (Standard/Low), not the profiles' actual configured
        // names — those are user-picked via StringKey.ApsAutoIsfStandardProfileName/...LowProfileName,
        // so naming them here would go stale the moment either is repointed.
        TtCode.Stepped(
            "Profile (manual override)", 5.148, 5.150, "Standard", "Low",
            // Compares the actual active profile name against the two configured role names, same
            // resolution logic used throughout OpenAPSAutoISFPlugin.kt (e.g. onNormalProfile in
            // BolusGiven) -- shows the real name too since neither role name is fixed text.
            currentValue = {
                val name = profileFunction.getProfileName()
                val roleLabel = when (name) {
                    preferences.get(StringKey.ApsAutoIsfStandardProfileName) -> "Standard"
                    preferences.get(StringKey.ApsAutoIsfLowProfileName)      -> "Low"
                    else                                                     -> "neither"
                }
                "Current: $roleLabel ($name)"
            }
        ),
        // UKFset2 removed from here 2026-08-15: it no longer has any live dosing effect on the graph
        // history itself (its function call in XdripSourcePlugin.kt/NsIncomingDataProcessor.kt discards
        // its own return value) -- it only feeds a persisted graph history now. Toggling it moved to
        // list2's "Graph: UKF2" entry (GraphToggleEntry.syncedLiveKey) at the time, freeing up 5.154
        // (LibreUkf2ToggleTT). UKFset1 stays here since it's still a real, live dosing-BGL setting.
        //
        // 2026-08-16 (UKF3426 branch): syncedLiveKey itself was removed -- it was writing
        // FslUseUkfLibreSpecialSmoothing (a real dosing-engine-selection preference, not just a display
        // flag) as a side effect of checking a graph checkbox, which is no longer needed now that the
        // graph history's own staleness bug is fixed at the root (see XdripSourcePlugin.kt/
        // NsIncomingDataProcessor.kt). That means FslUseUkfLibreSpecialSmoothing currently has NO
        // toggle path left anywhere in this custom TT-code/graph-checkbox system -- 5.154 was freed up,
        // not reused. If it ever needs to be toggled again, either restore a TT code at 5.154 or a
        // plain (non-syncing) preference toggle.
        TtCode.Single(
            "Tog UKFset1 (Virtual live comparison) on/off", 5.152,
            currentValue = { "Current: ${if (preferences.get(BooleanKey.FslUseUkfSmoothing)) "ON" else "OFF"}" }
        ),
        TtCode.Single("Run SensorAge code on/off", 5.156, currentValue = { "Current: ${if (preferences.get(BooleanKey.ApsAutoIsfSensorAgeCodeEnabled)) "ON" else "OFF"}" })
        // Graph toggle entries (UKF1/UKF2/UKF3) live in list2 (basal rate icon,
        // BasalDirectAction/showBasalDirectActionListDialog() below), not list1 (this one, IOB icon)
        // -- moved there per explicit request. See GraphToggleEntry's doc comment.
    )

    // Pump/Virtual selection: enqueue the matching local handler without making a TT. Client selection:
    // create the relay TT; Client does not alter its own local setting.
    private fun applyTtControl(mmol: Double) {
        if (config.AAPSCLIENT) {
            setRelayTt(mmol, "IOB double-tap settings")
        } else {
            rxBus.send(EventAutoIsfDirectTtCode(mmol))
        }
    }

    private fun setRelayTt(mmol: Double, origin: String) {
        val mgdl = mmol * app.aaps.core.data.configuration.Constants.MMOLL_TO_MGDL
        val tt = TT(
            timestamp = dateUtil.now(),
            duration = TimeUnit.MINUTES.toMillis(5),
            reason = TT.Reason.CUSTOM,
            lowTarget = mgdl,
            highTarget = mgdl
        )
        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = tt,
            action = Action.TT,
            source = Sources.TTDialog,
            note = "TT code $mmol ($origin)",
            listValues = listOf(
                ValueWithUnit.TETTReason(TT.Reason.CUSTOM),
                ValueWithUnit.Mgdl(mgdl),
                ValueWithUnit.Minute(5)
            )
        ).subscribe()
    }

    private fun updateIobCob() {
        val iobText = iobText()
        val iobDialogText = iobDialogText()
        val displayText = iobCobCalculator.getCobInfo("Overview COB").displayText(rh, decimalFormatter)
        val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.iob.text = iobText
            iobDialogTextCached = iobDialogText
            // Single tap / long-press / double-tap all live on one persistent GestureDetector (see
            // iobGestureDetector field) — re-attaching the listener here each refresh is fine/cheap, but
            // the detector instance itself must stay the same one across refreshes for double-tap
            // detection to work (see that field's own comment).
            binding.infoLayout.iobLayout.setOnTouchListener { _, event -> iobGestureDetector.onTouchEvent(event); true }
            // cob
            var cobText = displayText ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

            val constraintsProcessed = loop.lastRun?.constraintsProcessed
            val lastRun = loop.lastRun
            if (config.APS && constraintsProcessed != null && lastRun != null) {
                if (constraintsProcessed.carbsReq > 0) {
                    //only display carbsreq when carbs have not been entered recently
                    if (lastCarbsTime < lastRun.lastAPSRun) {
                        cobText += "\n" + constraintsProcessed.carbsReq + " " + rh.gs(app.aaps.core.ui.R.string.required)
                    }
                    if (carbAnimation?.isRunning == false)
                        carbAnimation?.start()
                } else {
                    carbAnimation?.stop()
                    carbAnimation?.selectDrawable(0)
                }
            }
            binding.infoLayout.cob.text = cobText
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateTemporaryTarget() {
        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        runOnUiThread {
            _binding ?: return@runOnUiThread
            if (tempTarget != null) {
                setRibbon(
                    binding.tempTarget,
                    app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                    app.aaps.core.ui.R.attr.ribbonWarningColor,
                    profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh)
                )
            } else {
                profileFunction.getProfile()?.let { profile ->
                    // If the target is not the same as set in the profile then oref has overridden it
                    val targetUsed =
                        if (config.APS) loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0
                        else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.targetBG ?: 0.0
                        else 0.0

                    if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                        aapsLogger.debug("Adjusted target. Profile: ${profile.getTargetMgdl()} APS: $targetUsed")
                        setRibbon(
                            binding.tempTarget,
                            app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                            app.aaps.core.ui.R.attr.tempTargetBackgroundColor,
                            profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units)
                        )
                    } else {
                        setRibbon(
                            binding.tempTarget,
                            app.aaps.core.ui.R.attr.ribbonTextDefaultColor,
                            app.aaps.core.ui.R.attr.ribbonDefaultColor,
                            profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // On-update popups: coded-profile-name selection + native-automation close-match review. Neither
    // is gated on an actual app-version comparison -- both just check "is there pending work" on each
    // Overview resume, which naturally covers "on update" (new coded automations/keys introduced by an
    // update show up as new pending reviews) without needing separate version-tracking plumbing.
    // popupsShownThisSession avoids re-prompting every single fragment resume within one process
    // lifetime (e.g. switching tabs and back); a fresh app launch re-checks, so a dismissed-without-
    // deciding popup naturally reappears next full start.
    // ---------------------------------------------------------------------------------------------

    private var popupsShownThisSession = false

    private fun checkOnUpdatePopups() {
        if (popupsShownThisSession) return
        popupsShownThisSession = true
        val act = activity ?: return
        if (preferences.get(OverviewStringKey.ApsAutoIsfProfileNamesReviewed).isEmpty())
            showProfileNamesPopup(act) { checkCodedAutomationReviewPopup(act) }
        else
            checkCodedAutomationReviewPopup(act)
    }

    private fun checkCodedAutomationReviewPopup(act: androidx.fragment.app.FragmentActivity) {
        val pending = automation.pendingCodedAutomationReviews()
        if (pending.isNotEmpty()) showCodedAutomationReviewPopup(act, pending)
    }

    // Lets the user pick which of their actual configured profiles fills each of the two coded roles
    // (StandardProfile/LowProfile) that OpenAPSAutoISFPlugin.kt's ~36 switchProfileIfNeeded() call sites
    // read via StringKey.ApsAutoIsfStandardProfileName/LowProfileName, instead of the original hardcoded
    // "Current ProfileReal"/"Current Profile" literals. Not cancelable (no tap-outside-to-dismiss) since
    // Cancel/OK are both handled explicitly below and either one marks the flag reviewed.
    private fun showProfileNamesPopup(act: androidx.fragment.app.FragmentActivity, onDone: () -> Unit) {
        val profileNames = activePlugin.activeProfileSource.profile?.getProfileList()?.map { it.toString() } ?: emptyList()
        if (profileNames.isEmpty()) {
            // No profiles configured yet (e.g. very first run) -- nothing to pick from; skip silently
            // and let it re-check next resume rather than marking reviewed on incomplete data.
            onDone()
            return
        }
        val currentStandard = preferences.get(StringKey.ApsAutoIsfStandardProfileName)
        val currentLow = preferences.get(StringKey.ApsAutoIsfLowProfileName)

        fun spinnerFor(current: String): Spinner {
            val spinner = Spinner(act)
            val adapter = ArrayAdapter(act, android.R.layout.simple_spinner_item, profileNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(profileNames.indexOf(current).let { if (it >= 0) it else 0 })
            return spinner
        }

        val standardSpinner = spinnerFor(currentStandard)
        val lowSpinner = spinnerFor(currentLow)
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            // Message inlined here rather than setMessage(), and the whole thing wrapped in a
            // WRAP_CONTENT ScrollView below -- same reasoning as showCodedAutomationReviewPopup()'s
            // layout note. This dialog previously had NO scroll container at all, so on a short screen
            // (or with large system font / display scaling, which inflates both labels and the two
            // spinners) its content could exceed the window and push the OK button out of reach, with
            // setCancelable(false) below meaning there was then no way to dismiss it either.
            addView(TextView(act).apply {
                text = "Pick which of your profiles fill the two roles the AutoISF ported automations switch between."
                setPadding(0, 0, 0, 24)
            })
            addView(TextView(act).apply { text = "Standard profile (stronger — used for corrections/highs):" })
            addView(standardSpinner)
            addView(TextView(act).apply { text = "Low profile (weaker — used to back off/reduce insulin):"; setPadding(0, 32, 0, 0) })
            addView(lowSpinner)
        }
        val scrollView = ScrollView(act).apply {
            addView(container)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle("Select coded profiles")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton(rh.gs(app.aaps.core.ui.R.string.ok)) { _, _ ->
                preferences.put(StringKey.ApsAutoIsfStandardProfileName, profileNames[standardSpinner.selectedItemPosition])
                preferences.put(StringKey.ApsAutoIsfLowProfileName, profileNames[lowSpinner.selectedItemPosition])
                preferences.put(OverviewStringKey.ApsAutoIsfProfileNamesReviewed, dateUtil.now().toString())
                onDone()
            }
            .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel)) { _, _ ->
                // Skip: leave the current (possibly still-default) preference values untouched, but
                // still mark reviewed so this doesn't nag on every single launch -- change later via
                // Preferences if needed.
                preferences.put(OverviewStringKey.ApsAutoIsfProfileNamesReviewed, dateUtil.now().toString())
                onDone()
            }
            .show()
    }

    // Checklist of native Automation-tab events whose titles are CLOSE (but not EXACT) matches against
    // the coded automation registry (see CodedAutomationNames.kt) and have no stored decision yet.
    // Unchecked by default (matches the prior blanket-suppress behaviour until explicitly opted in).
    // EXACT matches never appear here — those stay auto-denied unconditionally, no prompt at all.
    private fun showCodedAutomationReviewPopup(act: androidx.fragment.app.FragmentActivity, pending: List<String>) {
        val checkBoxes = pending.map { title -> CheckBox(act).apply { text = title; isChecked = false } }
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            // Explanatory text lives INSIDE the scrolling content rather than in setMessage() -- see
            // the layout note below; setMessage() adds a second, separately-measured block competing
            // with the list for vertical space, which is part of what pushed the buttons off-screen.
            addView(TextView(act).apply {
                text = "Names close to a coded automation, currently suppressed. Check any to allow."
                setPadding(0, 0, 0, 24)
            })
            checkBoxes.forEach { addView(it) }
        }
        // WRAP_CONTENT, deliberately NOT a fixed pixel height. Earlier attempts capped this at a
        // fraction of screen height (50%, then 30%) via ViewGroup.LayoutParams(_, maxHeightPx) -- but
        // that sets an EXACT height, not a maximum, so the ScrollView claimed that slab unconditionally
        // and "title + message + slab + button row" could still exceed the window, pushing the OK/Cancel
        // row off-screen with no way to reach it (a real lockout, not a scroll inconvenience). Tuning
        // the fraction only moves the item count at which it breaks; it never removes the failure mode,
        // which is why 30% still reproduced it. With WRAP_CONTENT the AlertDialog measures this view
        // against the space remaining AFTER reserving title and buttons, and a ScrollView shrinks to
        // whatever it is given -- so the buttons are structurally guaranteed to stay visible at any item
        // count, on any screen size, and the list simply scrolls internally when it doesn't all fit.
        val scrollView = ScrollView(act).apply {
            addView(container)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle("Review native automations")
            .setView(scrollView)
            .setPositiveButton(rh.gs(app.aaps.core.ui.R.string.ok)) { _, _ ->
                automation.saveCodedAutomationDecisions(pending.indices.associate { pending[it] to checkBoxes[it].isChecked })
            }
            // Cancel: no decisions saved at all -- these stay pending and the checklist reappears next launch.
            .setNegativeButton(rh.gs(app.aaps.core.ui.R.string.cancel), null)
            .show()
    }

    private fun setRibbon(view: TextView, attrResText: Int, attrResBack: Int, text: String) {
        with(view) {
            setText(text)
            setBackgroundColor(rh.gac(context, attrResBack))
            setTextColor(rh.gac(context, attrResText))
            compoundDrawables[0]?.setTint(rh.gac(context, attrResText))
        }
    }

    private fun updateGraph() {
        _binding ?: return
        // One-shot cross-module signal from CleanGraphTT (OpenAPSAutoISFPlugin.kt, TT=5.042): applies the
        // same "no SMB labels, no BGL arrowheads, solid uniform-green line" combo as long-pressing IOB
        // then Basal, then clears itself so it doesn't keep re-applying and block manual long-presses
        // afterward.
        if (preferences.get(BooleanKey.ApsAutoIsfCleanGraphRequested)) {
            PointsWithLabelGraphSeries.showSmbLabels = false
            PointsWithLabelGraphSeries.basalToggleIndex = 2
            preferences.put(BooleanKey.ApsAutoIsfCleanGraphRequested, false)
        }
        val pump = activePlugin.activePump
        val graphData = graphDataProvider.get().with(binding.graphsLayout.bgGraph, overviewData)
        val menuChartSettings = overviewMenus.setting
        if (menuChartSettings.isEmpty()) return
        graphData.addInRangeArea(
            overviewData.fromTime, overviewData.endTime,
            preferences.get(UnitDoubleKey.OverviewLowMark),
            preferences.get(UnitDoubleKey.OverviewHighMark)
        )
        graphData.addBgReadings(menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal], context)
        graphData.addBucketedData()
        graphData.addTreatments(context)
        graphData.addEps(context, 0.95)
        if (menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal])
            graphData.addTherapyEvents()
        if (menuChartSettings[0][OverviewMenus.CharType.ACT.ordinal])
            graphData.addActivity(0.8)
        // Line1 (empirical Carbs Absorption): checkbox is a master gate (off => always off, regardless
        // of push state). carbLine1QuickShow is set directly by the relevant long-press ACTIONS (basal
        // icon push0/push1/push2, IOB icon press) -- not derived by passively reading basalToggleIndex,
        // since that would wrongly react to the IOB icon's own incidental reset of that counter.
        if (menuChartSettings[0][OverviewMenus.CharType.CARB_ABS.ordinal] && PointsWithLabelGraphSeries.carbLine1QuickShow)
            graphData.addCarbAbsorption(0.8)
        // Line2 (carb model curve) is intentionally independent of line1's own checkbox/toggle state --
        // controlled purely by its own settings switch, never affected by the CARB_ABS checkbox above or
        // any basal-icon/IOB-icon long-press effect on line1.
        if (preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve))
            graphData.addCarbModelCurve(0.8)
        if (overviewMenus.isActiveCharTypeData(0, OverviewMenus.CharType.UAM_CARB_IMPACT.ordinal))
            graphData.addUamCarbImpact(0.8)
        if (overviewMenus.isActiveCharTypeData(0, OverviewMenus.CharType.COMBINED_CARBS.ordinal))
            graphData.addCombinedCarbs(0.8)
        if (overviewMenus.isActiveCharTypeData(0,OverviewMenus.CharType.BG_PARAB.ordinal))
            graphData.addBgParabola(menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal],1.0)
        if (overviewMenus.isActiveCharTypeData(0, OverviewMenus.CharType.RAW_BG.ordinal))
            graphData.addRawBg(false)
        if (overviewMenus.isActiveCharTypeData(0, OverviewMenus.CharType.RAW_BG_SMOOTHED.ordinal))
            graphData.addRawBgSmoothed(false)
        if ((pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT) && menuChartSettings[0][OverviewMenus.CharType.BAS.ordinal])
            graphData.addBasals()
        graphData.addTargetLine()
        graphData.addRunningModes()
        graphData.addNowLine(dateUtil.now())
        // Libre 1-min / AAPS (smoothed) 1-min delta labels attached to the actual current graph points —
        // must be on THIS graph (the real BG graph, same y-scale as the plotted points), not a secondary
        // graph panel. The hypo-prediction row is fixed-position (ignores y-scale entirely), but stays
        // here too since it wants the main graph's own basal-column area at the bottom, not graph1's.
        // Replaces the old raw-Libre 15-min delta label (Shape.L1_DELTA_POINT, removed) -- that one could
        // briefly show the "old" direction near an inflection since it was a raw two-point slope; this
        // tracks the same UKF-smoothed curve drawn as the blue dashed line instead.
        graphData.addA1DeltaAnnotation()
        graphData.addUkfDeltaAnnotation()
        graphData.addHpAnnotation()
        graphData.addIobPeakMainAnnotation()

        // set manual x bounds to have nice steps
        graphData.setNumVerticalLabels()
        graphData.formatAxis(overviewData.fromTime, overviewData.endTime)
        graphData.applyFontScale(skinProvider.activeSkin().graphFontScale)

        graphData.performUpdate()

        // ScaledDataPoints share these Scale instances across every graph. Preserve the main graph's
        // values because graph5/secondary graph setup below may otherwise overwrite them; client and
        // full AAPS often have different secondary selections, which made ComboCarbs peak at different
        // heights (about 65% on the client) despite both main-graph calls requesting scale 0.8.
        val mainActivityScaleMultiplier = overviewData.actScale.multiplier.takeIf {
            menuChartSettings[0][OverviewMenus.CharType.ACT.ordinal]
        }
        val mainCombinedCarbsScaleMultiplier = overviewData.combinedCarbsScale.multiplier.takeIf {
            overviewMenus.isActiveCharTypeData(0, OverviewMenus.CharType.COMBINED_CARBS.ordinal)
        }
        val mainBasalScaleMultiplier = overviewData.basalScale.multiplier.takeIf {
            (pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT) &&
                menuChartSettings[0][OverviewMenus.CharType.BAS.ordinal]
        }

        // 5th graph: always-on BGL/carb/insulin-activity/basal picture. Deliberately NOT gated by
        // the main graph's own checkboxes/quick-toggles (menuChartSettings[0][...], isActiveCharTypeData,
        // carbLine1QuickShow) -- graph5 shows all of these regardless of what's switched off on graph 0.
        // Not part of the CharTypeData/secondary-graphs menu system (that only lets primary types target
        // graph 0) -- one on/off switch for the whole panel (BooleanKey.ApsAutoIsfShowGraph5), plus an
        // independent BGL-only sub-toggle (BooleanKey.ApsAutoIsfGraph5BglOnly) that hides insulin activity
        // and the 3 carb-related lines while leaving BGL/basal/annotation rows alone. Both reachable from
        // list2's "Graph: Graph5 panel" GraphToggleEntry; the panel on/off is also mirrored in list1 as a
        // quick single-tap toggle (5.142) for convenience -- same underlying key, no divergence risk.
        // No treatments/therapy-events/notes/delta-annotations/hypo-prediction row -- those are
        // label/marker-bearing (bolus doses, carb grams, note text); this panel is plain data lines only.
        if (preferences.get(BooleanKey.ApsAutoIsfShowGraph5)) {
            binding.graphsLayout.graph5Container.visibility = View.VISIBLE
            val graph5Data = graphDataProvider.get().with(binding.graphsLayout.graph5, overviewData)
            graph5Data.addInRangeArea(
                overviewData.fromTime, overviewData.endTime,
                preferences.get(UnitDoubleKey.OverviewLowMark),
                preferences.get(UnitDoubleKey.OverviewHighMark)
            )
            // hideGraph5BglAndUam (basalToggleIndex==2 / uniformGreenBg) used to suppress graph5's own
            // BGL traces + UAM line whenever that basal-icon short-press state was active on the MAIN
            // graph -- removed 2026-08-15: graph5 is meant to be independent of anything switched off/on
            // via graph 0's own toggles (see the panel's top comment), and this was the one place graph5
            // still inherited a graph-0-scoped state. addBasals() below still applies its own internal
            // uniformGreenBg suppression to the ISF-weighted temp-basal overlay lines specifically (see
            // GraphData.addBasals()) -- that one's left alone, it's about the basal lines themselves, not
            // the BGL lines this block draws.
            // BooleanKey.ApsAutoIsfGraph5BglOnly, reachable from list2's "Graph: Graph5 panel" entry
            // (second checkbox, normally calibration -- repurposed here). false (default) = unchanged
            // prior behaviour, every series below stays on; true = skip insulin activity and all 3
            // carb-related lines, BGL/basal/annotation rows untouched.
            val graph5BglOnly = preferences.get(BooleanKey.ApsAutoIsfGraph5BglOnly)
            graph5Data.addBgReadings(true, context, drawSeries = true)
            graph5Data.addBucketedData()
            if (!graph5BglOnly) {
                graph5Data.addActivity(0.8)             // insulin activity
                graph5Data.addCarbModelCurve(0.8)        // theoretical carb model curve (still needs ApsAutoIsfShowCarbModelCurve
                                                          // globally on for there to be any data -- that's a data-availability
                                                          // flag, not a "switched off on graph 0" toggle, so it's left alone)
                graph5Data.addUamCarbImpact(0.8) // UAM assumed carbs
                graph5Data.addCombinedCarbs(0.8)         // absorption + UAM combined
            }
            graph5Data.addBgParabola(true, 1.0)
            graph5Data.addRawBg(false)
            // Graph5-only version: shows UKF1/2/3 comparison lines regardless of List2's own
            // ShowUkf1Graph/ShowUkf2Graph/ShowUkf3Graph toggles -- see GraphData.addRawBgSmoothedGraph5().
            graph5Data.addRawBgSmoothedGraph5(false)
            if (pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT) graph5Data.addBasals()
            // Live target offset / last dura-taper time, fixed at the top of graph5's basal-column
            // area, one line below the pp/acc/du row.
            graph5Data.addTargetOffsetDuTAnnotation()
            // "pp= acc= du=" row: moved off graph3 (which now hosts the SMB-stack-total labels) onto
            // graph5 specifically -- this is THIS graph, not bg_graph (see graph5_container in
            // overview_graphs_layout.xml). Positioned via a real value near 4.0 mmol, not a pixel
            // fraction of graph height and not the live current BG either -- see Shape.PP_ACC_DU_ROW's
            // own comment (graph5's basal bars occupy negative Y, same as bg_graph).
            graph5Data.addIsfWeightsRow()
            graph5Data.addTargetLine()
            graph5Data.addRunningModes()
            graph5Data.addNowLine(dateUtil.now())
            graph5Data.setNumVerticalLabels()
            graph5Data.formatAxis(overviewData.fromTime, overviewData.endTime)
            graph5Data.applyFontScale(skinProvider.activeSkin().graphFontScale)
            graph5Data.performUpdate()
        } else {
            binding.graphsLayout.graph5Container.visibility = View.GONE
        }

        // 2nd graphs
        prepareGraphsIfNeeded(menuChartSettings.size)
        val secondaryGraphsData: ArrayList<GraphData> = ArrayList()

        val now = System.currentTimeMillis()
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            val secondGraphData = graphDataProvider.get().with(secondaryGraphs[g], overviewData)
            var useABSForScale = false
            var useIobForScale = false
            var useCobForScale = false
            var useIobThForScale = false
            var useDevForScale = false
            var useRatioForScale = false
            var useVarSensForScale = false
            var useDSForScale = false
            var useBGIForScale = false
            var useHRForScale = false
            var useSTEPSForScale = false
            var useFINAL_ISFForScale = false
            var useACCE_ISFForScale = false
            var useBG_ISFForScale = false
            var usePP_ISFForScale = false
            var useDURA_ISFForScale = false
            var useRAWBGForScale = false
            var useUAMForScale = false
            var useCombinedCarbsForScale = false
            when {
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ABS.ordinal)        -> useABSForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB.ordinal)        -> useIobForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COB.ordinal)        -> useCobForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB_TH.ordinal)     -> useIobThForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEV.ordinal)        -> useDevForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BGI.ordinal)        -> useBGIForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.SEN.ordinal)        -> useRatioForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.VAR_SEN.ordinal)    -> useVarSensForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEVSLOPE.ordinal)   -> useDSForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.HR.ordinal)         -> useHRForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.STEPS.ordinal)      -> useSTEPSForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.FIN_ISF.ordinal)    -> useFINAL_ISFForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ACC_ISF.ordinal)    -> useACCE_ISFForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BG_ISF.ordinal)     -> useBG_ISFForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.PP_ISF.ordinal)     -> usePP_ISFForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DUR_ISF.ordinal)    -> useDURA_ISFForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG_SMOOTHED.ordinal) -> useRAWBGForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.UAM_CARB_IMPACT.ordinal) -> useUAMForScale = true
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COMBINED_CARBS.ordinal) -> useCombinedCarbsForScale = true
            }

            val alignDevBgiScale = menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] && menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]
            val alignAbsScale = menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal] && overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB_TH.ordinal)
            val alignIobScale = menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] && overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB_TH.ordinal)
            val maxCommonIob = when {
                alignAbsScale -> max(overviewData.maxIobValueFound, overviewData.maxIobThValueFound)
                alignIobScale -> max(overviewData.maxIobValueFound, overviewData.maxIobThValueFound)
                else          -> 0.0
            }
            var maxAutoIsfFactor = 1.0
            var minAutoIsfFactor = 1.0
            var commonIsfCount = 0
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.FIN_ISF.ordinal)) {
                maxAutoIsfFactor = max(maxAutoIsfFactor, overviewData.maxFinalIsfValueFound)
                //minAutoIsfFactor = min(minAutoIsfFactor, overviewData.minFinalIsfValueFound)
                commonIsfCount++
            }
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ACC_ISF.ordinal)) {
                maxAutoIsfFactor = max(maxAutoIsfFactor, overviewData.maxAcceIsfValueFound)
                //minAutoIsfFactor = min(minAutoIsfFactor, overviewData.minAcceIsfValueFound)
                commonIsfCount++
            }
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BG_ISF.ordinal)) {
                maxAutoIsfFactor = max(maxAutoIsfFactor, overviewData.maxBgIsfValueFound)
                //minAutoIsfFactor = min(minAutoIsfFactor, overviewData.minBgIsfValueFound)
                commonIsfCount++
            }
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.PP_ISF.ordinal)) {
                maxAutoIsfFactor = max(maxAutoIsfFactor, overviewData.maxPpIsfValueFound)
                //minAutoIsfFactor = min(minAutoIsfFactor, overviewData.minPpIsfValueFound)
                commonIsfCount++
            }
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DUR_ISF.ordinal)) {
                maxAutoIsfFactor = max(maxAutoIsfFactor, overviewData.maxDuraIsfValueFound)
                //minAutoIsfFactor = min(minAutoIsfFactor, overviewData.minDuraIsfValueFound)
                commonIsfCount++
            }
            val useCommonISFForScale = commonIsfCount>1
            //val maxYValueForScale = maxAutoIsfFactor    //max(maxAutoIsfFactor, 2.0 - minAutoIsfFactor)       // ensure Y=1 is in the center of the graph
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ABS.ordinal)) secondGraphData.addAbsIob(useABSForScale, 1.0, maxCommonIob)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB.ordinal)) secondGraphData.addIob(   useIobForScale,  1.0, maxCommonIob)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COB.ordinal)) secondGraphData.addCob(useCobForScale, if (useCobForScale) 1.0 else 0.5)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB_TH.ordinal)) secondGraphData.addIobTh( useIobThForScale,   if (maxCommonIob>0.0) 1.0 else 0.8, maxCommonIob)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEV.ordinal)) secondGraphData.addDeviations(useDevForScale, 1.0)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BGI.ordinal)) secondGraphData.addMinusBGI(useBGIForScale, if (alignDevBgiScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.SEN.ordinal)) secondGraphData.addRatio(useRatioForScale, if (useRatioForScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.VAR_SEN.ordinal)) secondGraphData.addVarSens(useVarSensForScale, if (useVarSensForScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.FIN_ISF.ordinal)) secondGraphData.addFinalIsf(useFINAL_ISFForScale,  1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ACC_ISF.ordinal)) secondGraphData.addAcceIsf(useACCE_ISFForScale, 1.0, useCommonISFForScale,maxAutoIsfFactor)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BG_ISF.ordinal)) secondGraphData.addBgIsf(useBG_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.PP_ISF.ordinal)) secondGraphData.addPpIsf(usePP_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DUR_ISF.ordinal)) secondGraphData.addDuraIsf(useDURA_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEVSLOPE.ordinal)) secondGraphData.addDeviationSlope(useDSForScale,if (useDSForScale) 1.0 else 0.8, useRatioForScale)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.HR.ordinal)) secondGraphData.addHeartRate(useHRForScale, if (useHRForScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.STEPS.ordinal)) secondGraphData.addSteps(useSTEPSForScale, if (useSTEPSForScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG.ordinal)) secondGraphData.addRawBg(useRAWBGForScale)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG_SMOOTHED.ordinal)) secondGraphData.addRawBgSmoothed(useRAWBGForScale)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.UAM_CARB_IMPACT.ordinal)) secondGraphData.addUamCarbImpact(if (useUAMForScale) 1.0 else 0.8)
            if (overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COMBINED_CARBS.ordinal)) secondGraphData.addCombinedCarbs(if (useCombinedCarbsForScale) 1.0 else 0.8)
            // CarePortal notes: swapped from graph2 to graph4 (g==3) — was on graph2, swapped positions
            // with the SMB stacked labels below. Same TREAT toggle source as before.
            if (g == 3 && menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal]) secondGraphData.addNoteEvents()
            // Steps row + DR/AW/LS row above it + yellow/white line + ISF adaptation indices/SMB row
            // ("f= ac= bg= pp= du= smb=") above all three of those: graph1 (g==0), fixed near the bottom.
            if (g == 0) {
                secondGraphData.addTargetOffsetDuTGraph1Annotation()
                secondGraphData.addStepsStackedAnnotation()
                secondGraphData.addStepsExtra()
                secondGraphData.addNoisyBgDeltaAnnotation()
                secondGraphData.addIsfIndices()
            }
            // Note arrowheads: moved from graph3 to graph4 (g==3), top half — see noteArrowheadPy in
            // PointsWithLabelGraphSeries.kt. Same TREAT toggle source.
            if (g == 3 && menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal]) secondGraphData.addNoteArrowheads()
            // SMB stacked labels: swapped from graph4 to graph2 (g==1) — was on graph4, swapped
            // positions with the CarePortal notes above.
            if (g == 1) secondGraphData.addSmbLabels()
            // graph3 (g==2): was the "pp= acc= du=" row (now on graph5, see graph5Data.addIsfWeightsRow()
            // above) -- replaced with the SMB-stack-total labels (was g==0/IOB-COB panel, moved here).
            if (g == 2) secondGraphData.addSmbStackTotalLabels()

            //if (menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]) secondGraphData.addAbsIob(useABSForScale, 1.0, maxCommonIob)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]) secondGraphData.addIob(   useIobForScale,  1.0, maxCommonIob)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]) secondGraphData.addCob(useCobForScale, if (useCobForScale) 1.0 else 0.5)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal] && masterAutoIsf) secondGraphData.addIobTh( useIobThForScale,   if (maxCommonIob>0.0) 1.0 else 0.8, maxCommonIob)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]) secondGraphData.addDeviations(useDevForScale, 1.0)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]) secondGraphData.addMinusBGI(useBGIForScale, if (alignDevBgiScale) 1.0 else 0.8)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]) secondGraphData.addRatio(useRatioForScale, if (useRatioForScale) 1.0 else 0.8)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal]) secondGraphData.addVarSens(useVarSensForScale, if (useVarSensForScale) 1.0 else 0.8)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.FIN_ISF.ordinal] && masterAutoIsf) secondGraphData.addFinalIsf(useFINAL_ISFForScale,  1.0, useCommonISFForScale, maxAutoIsfFactor)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.ACC_ISF.ordinal] && masterAutoIsf) secondGraphData.addAcceIsf(useACCE_ISFForScale, 1.0, useCommonISFForScale,maxAutoIsfFactor)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal] && masterAutoIsf) secondGraphData.addBgIsf(useBG_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal] && masterAutoIsf) secondGraphData.addPpIsf(usePP_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.DUR_ISF.ordinal] && masterAutoIsf) secondGraphData.addDuraIsf(useDURA_ISFForScale, 1.0, useCommonISFForScale, maxAutoIsfFactor)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] && config.isDev()) secondGraphData.addDeviationSlope(useDSForScale,if (useDSForScale) 1.0 else 0.8, useRatioForScale)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal]) secondGraphData.addHeartRate(useHRForScale, if (useHRForScale) 1.0 else 0.8)
            //if (menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]) secondGraphData.addSteps(useSTEPSForScale, if (useSTEPSForScale) 1.0 else 0.8)

            // set manual x bounds to have nice steps
            secondGraphData.formatAxis(overviewData.fromTime, overviewData.endTime)
            secondGraphData.addNowLine(now)
            secondaryGraphsData.add(secondGraphData)
        }
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            secondaryGraphsLabel[g].text = overviewMenus.enabledTypes(g + 1)
            secondaryGraphs[g].visibility = (
                overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ABS.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COB.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.IOB_TH.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEV.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BGI.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.SEN.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.VAR_SEN.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DEVSLOPE.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.HR.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.STEPS.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.FIN_ISF.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.ACC_ISF.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.BG_ISF.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.PP_ISF.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.DUR_ISF.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.RAW_BG_SMOOTHED.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.UAM_CARB_IMPACT.ordinal) ||
                    overviewMenus.isActiveCharTypeData(g+1,OverviewMenus.CharType.COMBINED_CARBS.ordinal)
                    //menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.IOB_TH.ordinal] && masterAutoIsf ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal] ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.FIN_ISF.ordinal] && masterAutoIsf ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.ACC_ISF.ordinal] && masterAutoIsf ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.BG_ISF.ordinal] && masterAutoIsf ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.PP_ISF.ordinal] && masterAutoIsf ||
                    //menuChartSettings[g + 1][OverviewMenus.CharType.DUR_ISF.ordinal] && masterAutoIsf
                ).toVisibility()
            secondaryGraphsData[g].applyFontScale(skinProvider.activeSkin().graphFontScale)
            secondaryGraphsData[g].performUpdate()
        }
        // Restore the main-graph normalization after all shared scale mutations above. Rendering reads
        // Scale dynamically, so this final value is what keeps IA and ComboCarbs aligned on graph 0.
        mainActivityScaleMultiplier?.let { overviewData.actScale.multiplier = it }
        mainCombinedCarbsScaleMultiplier?.let { overviewData.combinedCarbsScale.multiplier = it }
        mainBasalScaleMultiplier?.let { overviewData.basalScale.multiplier = it }
    }

    private fun updateCalcProgress() {
        _binding ?: return
        binding.progressBar.visibility = (overviewData.calcProgressPct != 100).toVisibility()
        binding.progressBar.progress = overviewData.calcProgressPct
    }

    private fun updateSensitivity() {
        _binding ?: return
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)
        val lastAutosensRatio = lastAutosensData?.let { it.autosensResult.ratio * 100 }
        if (config.AAPSCLIENT && preferences.get(BooleanNonKey.AutosensUsedOnMainPhone) ||
            !config.AAPSCLIENT && constraintChecker.isAutosensModeEnabled().value()
        ) {
            binding.infoLayout.sensitivityIcon.setImageResource(
                lastAutosensRatio?.let {
                    when {
                        it > 100.0 -> app.aaps.core.objects.R.drawable.ic_as_above
                        it < 100.0 -> app.aaps.core.objects.R.drawable.ic_as_below
                        else       -> app.aaps.core.objects.R.drawable.ic_swap_vert_black_48dp_green
                    }
                }
                    ?: app.aaps.core.objects.R.drawable.ic_swap_vert_black_48dp_green
            )
        } else {
            binding.infoLayout.sensitivityIcon.setImageResource(
                lastAutosensRatio?.let {
                    when {
                        it > 100.0 -> app.aaps.core.objects.R.drawable.ic_x_as_above
                        it < 100.0 -> app.aaps.core.objects.R.drawable.ic_x_as_below
                        else       -> app.aaps.core.objects.R.drawable.ic_x_swap_vert
                    }
                }
                    ?: app.aaps.core.objects.R.drawable.ic_x_swap_vert
            )
        }

        // Show variable sensitivity
        val profile = profileFunction.getProfile()
        val request = loop.lastRun?.request
        val isfMgdl = profile?.getProfileIsfMgdl()
        val isfForCarbs = profile?.getIsfMgdlForCarbs(dateUtil.now(), "Overview", config, processedDeviceStatusData)
        val variableSens =
            if (config.APS) request?.variableSens ?: 0.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        val ratioUsed = request?.autosensResult?.ratio ?: 1.0

        if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null) {
            val okDialogText: ArrayList<String> = ArrayList()
            val overViewText: ArrayList<String> = ArrayList()
            val autoSensHiddenRange = 0.0             //Hide Autosens value if equals 100%
            val autoSensMax = 100.0 + (preferences.get(DoubleKey.AutosensMax) - 1.0) * autoSensHiddenRange * 100.0
            val autoSensMin = 100.0 + (preferences.get(DoubleKey.AutosensMin) - 1.0) * autoSensHiddenRange * 100.0
            lastAutosensRatio?.let {
                if (it < autoSensMin || it > autoSensMax)
                    overViewText.add(rh.gs(app.aaps.core.ui.R.string.autosens_short, it))
                okDialogText.add(rh.gs(app.aaps.core.ui.R.string.autosens_long, it))
            }
            if (activePlugin.activeAPS.algorithm.name == "AUTO_ISF") {
                val aiRatio = 100.0  * profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()) / profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits())
                overViewText.add(
                    String.format(
                        Locale.getDefault(), rh.gs(app.aaps.core.ui.R.string.autoisf_short), aiRatio
                    )
                )
                okDialogText.add(rh.gs(app.aaps.core.ui.R.string.autoisf_long, aiRatio))
            } else {
                overViewText.add(
                    String.format(
                        Locale.getDefault(), "%1$.1f→%2$.1f",
                        profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()),
                        profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits())
                    )
                )
            }
            binding.infoLayout.sensitivity.text = overViewText.joinToString("\n")
            binding.infoLayout.sensitivity.visibility = View.VISIBLE
            binding.infoLayout.variableSensitivity.visibility = View.GONE
            if (ratioUsed != 1.0 && ratioUsed != lastAutosensData?.autosensResult?.ratio)
                okDialogText.add(rh.gs(app.aaps.core.ui.R.string.algorithm_long, ratioUsed * 100))
            okDialogText.add(rh.gs(app.aaps.core.ui.R.string.isf_for_carbs, profileUtil.fromMgdlToUnits(isfForCarbs ?: 0.0, profileFunction.getUnits())))
            if (config.APS) {
                val aps = activePlugin.activeAPS
                aps.getSensitivityOverviewString()?.let {
                    okDialogText.add(it)
                }
            }
            binding.infoLayout.asLayout.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.sensitivity), okDialogText.joinToString("\n")) } }

        } else {
            binding.infoLayout.sensitivity.text =
                lastAutosensData?.let {
                    rh.gs(app.aaps.core.ui.R.string.autosens_short, it.autosensResult.ratio * 100)
                } ?: ""
            binding.infoLayout.variableSensitivity.visibility = View.GONE
            binding.infoLayout.sensitivity.visibility = View.VISIBLE
        }
        if (activePlugin.activeAPS.algorithm.name == "AUTO_ISF") {
            binding.infoLayout.asLayout.setOnLongClickListener {
                aapsLogger.info(LTag.CORE, "EXPORT_STATUS trigger=ISF_LONG_PRESS component=REQUEST result=STARTED")
                // The dialog writes and uploads AIV first, then starts logs from its cloud-completion
                // callback. This guarantees AVLs/AVLf -> AVCs/AVCf -> LGsP ordering.
                uiInteraction.runAutoISFHistoryDialog(childFragmentManager)
                true
            }
        }
    }

    private fun updatePumpStatus() {
        _binding ?: return
        val status = overviewData.pumpStatus
        binding.pumpStatus.text = status
        binding.pumpStatusLayout.visibility = (status != "").toVisibility()
    }

    private fun updateNotification() {
        _binding ?: return
        binding.notifications.let { notificationStore.updateNotifications(it) }
    }

    fun popupBolusDialogIfRunning(onClick: Boolean) {
        // Check if bolus is in progress and show dialog if needed
        // Only show for manual bolus (not SMB) with progress > 0
        if (commandQueue.bolusInQueue()) {

            // Show bolus progress dialog automatically only for manual bolus with progress
            if (!BolusProgressData.bolusEnded && (!BolusProgressData.isSMB || onClick)) {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded)
                            uiInteraction.runBolusProgressDialog(childFragmentManager)
                    })
                }
            }
        }
    }
}
