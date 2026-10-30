package app.aaps.desktop.shell.config

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.configuration.InitProgress
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.implementation.maintenance.DesktopFolders
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * What kind of build this is, answered for desktop.
 *
 * On Android these values come from the Gradle flavour: `full`, `pumpcontrol`, `aapsclient`. Desktop
 * builds only the client, so the answers are fixed rather than generated - it has no pump drivers and
 * does not run the loop, which is exactly what `AAPSCLIENT` means.
 *
 * Except client 0 (2026-10-30, `-Pclient=0`): the "AAPS Virtual" master, Android's `virtual` flavour. It runs
 * the loop, on the Virtual pump only - [PUMPDRIVERS] stays false, so no real pump can be chosen.
 *
 * This is a real implementation, not a stand-in. Getting [APS] or [PUMPDRIVERS] wrong here would make
 * shared code offer features the app cannot perform.
 */
class DesktopClientConfig(
    override val VERSION_NAME: String = GeneratedBuildInfo.VERSION,
    /** Which client this build is, from `-Pclient=N`. Overridden in a test. */
    private val client: Int = GeneratedBuildInfo.CLIENT,
    /** Overridden in a test, so it neither reads nor creates markers in the real AAPS folder. */
    private val extraDir: File = DesktopFolders.extra
) : Config {

    /**
     * The same ids the Android clients use, so a client keeps its identity across platforms.
     *
     * Nightscout and the client-control channel both read this, and two clients pointed at one site
     * must not claim to be the same app.
     */
    override val APPLICATION_ID: String = when (client) {
        0    -> "info.nightscout.aapsvirtual"
        2    -> "info.nightscout.aapsclient2"
        3    -> "info.nightscout.aapsclient3"
        else -> "info.nightscout.aapsclient"
    }

    override val SUPPORTED_NS_VERSION: Int = 150000

    // No pump drivers on desktop. The clients are followers; client 0 is the Virtual master and runs the loop.
    override val APS: Boolean = client == 0
    override val PUMPCONTROL: Boolean = false
    override val PUMPDRIVERS: Boolean = false

    override val AAPSCLIENT: Boolean = !APS
    override val AAPSCLIENT1: Boolean = client == 1
    override val AAPSCLIENT2: Boolean = client == 2
    override val AAPSCLIENT3: Boolean = client == 3

    override val FLAVOR: String = if (APS) "virtual" else "aapsclient"
    override val BUILD_TYPE: String = "debug"
    override val PLATFORM: String = GeneratedBuildInfo.PLATFORM
    override val platform: AppPlatform = AppPlatform.Desktop
    override val VERSION: String = VERSION_NAME
    override val BUILD_VERSION: String = GeneratedBuildInfo.BUILD
    override val DEBUG: Boolean = true

    // The source commit, from the build (git rev-parse HEAD), as on iOS. "unknown" when git was not available there.
    override val HEAD: String = GeneratedBuildInfo.HEAD
    override val COMMITTED: Boolean = true
    override val REMOTE: String = "unknown"

    override val currentDeviceModelString: String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"

    /** Nightscout stores this verbatim, so keep it to the machine identity - no version. */
    override val deviceModelForUpload: String = System.getProperty("os.name") ?: "Desktop"
    override val deviceManufacturer: String = System.getProperty("os.name") ?: "Desktop"

    /**
     * What the app calls itself, by the same rule as the Android flavours.
     *
     * Android sets `app_name` per flavour in `app/build.gradle.kts`, and this build is a client, so
     * it is "AAPSClient" - not "AAPS", which is the name of the master. The About dialog and the
     * window title both show it, and a follower claiming to be the master is exactly the kind of
     * confusion the flavour names exist to prevent.
     *
     * Plain text as well as [appName], because the window title is set before the graph, and with it
     * any text resolver, exists.
     */
    val appNameText: String = when {
        AAPSCLIENT3 -> "AAPSClient3"
        AAPSCLIENT2 -> "AAPSClient2"
        AAPSCLIENT1 -> "AAPSClient"
        PUMPCONTROL -> "Pumpcontrol"
        APS         -> "AAPS Virtual"
        else        -> "AAPS"
    }

    /** [appNameText]. A literal rather than a resource: desktop has no resource table, and the name is not translated on Android either. */
    override val appName: TextRef = TextRef.Literal(appNameText)

    private val _initProgressFlow = MutableStateFlow(InitProgress(done = true))
    override val initProgressFlow: StateFlow<InitProgress> = _initProgressFlow.asStateFlow()

    private val _initSnackbarFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val initSnackbarFlow: SharedFlow<String> = _initSnackbarFlow.asSharedFlow()

    // See IosClientConfig: these replace the whole value, and the reconfigure depth must survive it -
    // it belongs to whoever opened the window, not to init progress. Clobbering it would reopen the
    // app to readers while an import still believes the window is held.
    override fun updateInitProgress(step: String, current: Int, total: Int) {
        _initProgressFlow.update { InitProgress(step = step, current = current, total = total, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun initCompleted() {
        _initProgressFlow.update { InitProgress(done = true, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun initFailed(error: String) {
        _initProgressFlow.update { InitProgress(done = true, error = error, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun beginReconfiguring() {
        _initProgressFlow.update { it.enteringReconfigure() }
    }

    override fun endReconfiguring() {
        _initProgressFlow.update { it.leavingReconfigure() }
    }

    override fun showInitSnackbar(message: String) {
        _initSnackbarFlow.tryEmit(message)
    }

    override fun isDev(): Boolean = DEBUG

    /**
     * Read from the file, the same as `ConfigImpl` does on Android.
     *
     * This returned a flat `false` before, which quietly contradicted [isEnabled] below: the marker
     * file could be created exactly as documented and the one option most likely to be turned on was
     * the one that never looked.
     */
    override fun isEngineeringMode(): Boolean = isEnabled(ExternalOptions.ENGINEERING_MODE)

    /**
     * The same expression as Android. On a client [APS] is false, so it is always true. The Virtual master is
     * always a debug build ([DEBUG]), so it needs `~/AAPS/extra/engineering_mode`, as a debug master does on Android.
     */
    override fun isEngineeringModeOrRelease(): Boolean = if (!APS) true else isEngineeringMode() || !isDev()

    /**
     * Read the same way Android does - by the presence of a file in the `extra` folder.
     *
     * Android looks in `AAPS/extra` through `FileListProvider.ensureExtraDirExists`, so this reads
     * `~/AAPS/extra` and an option is turned on in the same relative place on both:
     * `touch ~/AAPS/extra/engineering_mode`.
     *
     * It used to read `~/.aaps`, the data directory, which is the folder holding the database and
     * the keys. Nothing pointed that out, because a marker file that is never found and an option
     * that is switched off look exactly alike.
     */
    override fun isEnabled(option: ExternalOptions): Boolean =
        File(extraDir, option.filename).exists()
}
