package app.aaps.plugins.aps.openAPSAutoISF

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** An Android APK cannot be staged or installed on this phone. */
@ContributesBinding(AppScope::class)
@Inject
class IosApkInstallFront : ApkInstallFront {

    override fun stageNewest(): StageOutcome = ABSENT
    override fun newestStaged(): StagedApk? = null
    override fun runningVersionName(): String? = null
    override fun shizukuRunning(): Boolean = false
    override fun shizukuGranted(): Boolean = false
    override fun requestShizukuPermission() = Unit
    override fun installStaged(): Pair<Boolean, String> = false to ABSENT.detail
    override fun attemptAdbStart(port: Int): Pair<Boolean, String> = false to ABSENT.detail
    override fun listenForShizukuGrant(onGranted: () -> Unit) = Unit

    private companion object {
        val ABSENT = StageOutcome(ok = false, copiedByApp = false, detail = "APK install is not on this phone", featureNumber = null)
    }
}
