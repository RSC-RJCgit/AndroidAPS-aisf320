package app.aaps.core.interfaces.nsclient

import android.text.Spanned
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

interface ProcessedDeviceStatusData {

    enum class Levels(val level: Int) {

        URGENT(2),
        WARN(1),
        INFO(0);
    }

    class PumpData {

        var clock = 0L
        var isPercent = false
        var percent = 0
        var voltage = 0.0
        var status = "N/A"
        var reservoir = 0.0
        var reservoirDisplayOverride = ""
        var extended: Spanned? = null
        var activeProfileName: String? = null
    }

    var pumpData: PumpData?

    data class Device(
        val createdAt: Long,
        val device: String?
    )

    var device: Device?

    class Uploader {

        var clock = 0L
        var battery = 0
        var isCharging: Boolean? = null
    }

    val uploaderMap: HashMap<String, Uploader>

    class OpenAPSData {

        // @Volatile added 2026-09-17: suggested/clockSuggested are written exclusively by
        // NSDeviceStatusHandler.updateOpenApsData() on the async NS event thread, and read from
        // invoke()'s own thread by any consumer wanting the loop phone's live-mirrored steps/state
        // (see OpenAPSAutoISFPlugin.recentStepsXMinutes). Without a memory barrier, a plain var gives
        // no guarantee the reading thread ever sees the writer's update promptly or consistently --
        // real device logs showed exactly that symptom: the loop phone's steps value (confirmed
        // stable and re-uploaded every ~60-90s at the source) intermittently read back as stale/absent
        // on the very next invoke() cycle, with no other writer and no data gap to explain it.
        @Volatile var clockSuggested = 0L
        @Volatile var clockEnacted = 0L
        @Volatile var suggested: RT? = null
        @Volatile var enacted: RT? = null
    }

    var openAPSData: OpenAPSData

    // test warning level // color
    fun pumpStatus(nsSettingsStatus: NSSettingsStatus): Spanned
    val extendedPumpStatus: Spanned
    val extendedOpenApsStatus: Spanned
    val openApsStatus: Spanned
    val openApsTimestamp: Long
    fun getAPSResult(): APSResult?
    val uploaderStatus: String
    val uploaderStatusSpanned: Spanned
    val extendedUploaderStatus: Spanned
    val aisfStatus: Spanned
    val extendedAisfStatus: Spanned
}