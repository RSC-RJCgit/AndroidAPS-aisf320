package app.aaps.ui.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.widget.TextView
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.stats.DexcomTirCalculator
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.R
import app.aaps.ui.activityMonitor.ActivityMonitor
import app.aaps.ui.databinding.ActivityStatsBinding
import app.aaps.ui.stats.StatsTabs
import com.google.android.material.tabs.TabLayout
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class StatsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dexcomTirCalculator: DexcomTirCalculator
    @Inject lateinit var activityMonitor: ActivityMonitor
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aapsLogger: AAPSLogger

    private lateinit var binding: ActivityStatsBinding
    private lateinit var statsTabs: StatsTabs
    private val disposable = CompositeDisposable()
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private var selectedTab = 0
    private var resumed = false
    private val detailTabs = StatsTabs.Tab.entries
    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (selectedTab != tab.position) binding.tabPage.scrollTo(0, 0)
            selectedTab = tab.position
            showSelectedTab()
        }
        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = showSelectedTab()
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = rh.gs(R.string.statistics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        statsTabs = StatsTabs(this, binding.tabContent, persistenceLayer, tddCalculator, dexcomTirCalculator, profileUtil, dateUtil, rh, preferences, aapsSchedulers, aapsLogger)
        statsTabs.restoreState(savedInstanceState)
        listOf(R.string.stats_tab_overview, R.string.stats_tab_glucose, R.string.stats_tab_cycle, R.string.stats_tab_sensor, R.string.stats_tab_pods).forEach { label ->
            binding.tabs.addTab(binding.tabs.newTab().setText(label))
        }
        selectedTab = (savedInstanceState?.getInt(SELECTED_TAB) ?: 0).coerceIn(0, detailTabs.size)
        binding.tabs.addOnTabSelectedListener(tabListener)
        binding.tabs.getTabAt(selectedTab)?.select()
        showSelectedTab()

        binding.resetActivity.setOnClickListener {
            OKDialog.showConfirmation(this, rh.gs(R.string.do_you_want_reset_stats)) {
                uel.log(Action.STAT_RESET, Sources.Stats)
                activityMonitor.reset()
                recreate()
            }
        }
        binding.resetTdd.setOnClickListener {
            OKDialog.showConfirmation(this, rh.gs(R.string.do_you_want_recalculate_tdd_stats)) {
                handler.post {
                    uel.log(Action.STAT_RESET, Sources.Stats)
                    persistenceLayer.clearCachedTddData(0)
                    runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
                }
            }
        }
    }

    private fun showSelectedTab() {
        binding.overviewPage.visibility = if (selectedTab == 0) View.VISIBLE else View.GONE
        binding.tabPage.visibility = if (selectedTab == 0) View.GONE else View.VISIBLE
        if (!resumed) return
        disposable.clear()
        statsTabs.hide()
        if (selectedTab == 0) loadOverview() else statsTabs.show(detailTabs[selectedTab - 1])
    }

    @SuppressLint("SetTextI18n")
    private fun loadOverview() {
        binding.tdds.removeAllViews()
        binding.tir.removeAllViews()
        binding.dexcomTir.removeAllViews()
        binding.activity.removeAllViews()
        binding.tdds.addView(TextView(this).apply { text = getString(app.aaps.core.ui.R.string.tdd) + ": " + rh.gs(R.string.calculation_in_progress) })
        binding.tir.addView(TextView(this).apply { text = getString(app.aaps.core.ui.R.string.tir) + ": " + rh.gs(R.string.calculation_in_progress) })
        binding.dexcomTir.addView(TextView(this).apply { text = rh.gs(R.string.calculation_in_progress) })
        binding.activity.addView(TextView(this).apply { text = getString(R.string.activity_monitor) + ": " + rh.gs(R.string.calculation_in_progress) })

        disposable += Single.fromCallable { tddCalculator.stats(this) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           binding.tdds.removeAllViews()
                           binding.tdds.addView(it)
                       }, fabricPrivacy::logException)
        disposable += Single.fromCallable { tirCalculator.stats(this) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           binding.tir.removeAllViews()
                           binding.tir.addView(it)
                       }, fabricPrivacy::logException)
        disposable += Single.fromCallable { dexcomTirCalculator.stats(this) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           binding.dexcomTir.removeAllViews()
                           binding.dexcomTir.addView(it)
                       }, fabricPrivacy::logException)
        disposable += Single.fromCallable { activityMonitor.stats(this) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           binding.activity.removeAllViews()
                           binding.activity.addView(it)
                       }, fabricPrivacy::logException)

    }

    override fun onResume() {
        super.onResume()
        resumed = true
        showSelectedTab()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_TAB, selectedTab)
        statsTabs.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        resumed = false
        statsTabs.hide()
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.resetActivity.setOnClickListener(null)
        binding.resetTdd.setOnClickListener(null)
        binding.tabs.removeOnTabSelectedListener(tabListener)
        statsTabs.dispose()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    private companion object {
        const val SELECTED_TAB = "stats.selectedTab"
    }
}
