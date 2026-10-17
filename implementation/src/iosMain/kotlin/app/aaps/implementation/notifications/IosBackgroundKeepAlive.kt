package app.aaps.implementation.notifications

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.create
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * Keeps the app running in the background on iOS by playing silent sound, the way LoopFollow does.
 *
 * iOS suspends an app a few seconds after it leaves the screen, and the Nightscout websocket stops with
 * it, so a client stopped updating until it was opened again. An app that plays audio is not
 * suspended. `UIBackgroundModes = audio` in `Info.plist` allows this; this class plays a silent loop.
 *
 * The session is "playback, mix with others", so music and other apps keep playing normally.
 *
 * Turned on and off by [BooleanKey.IosKeepRunningInBackground] (on by default). It does not help after
 * the user swipes the app away, and iOS can still end it when memory is very low.
 *
 * The alarm player changes the same audio session. It sets [alarmPlaying] while an alarm sounds, so
 * this class leaves the session alone, and calls [resume] after the alarm.
 *
 * Everything runs on the main thread.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@SingleIn(AppScope::class)
@Inject
class IosBackgroundKeepAlive(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var player: AVAudioPlayer? = null
    private var started = false

    /** Set by the alarm player while an alarm sounds. */
    var alarmPlaying = false

    /** Called once at app start. Follows the setting from then on. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            preferences.observe(BooleanKey.IosKeepRunningInBackground).collect { on -> if (on) resume() else stopPlaying() }
        }
        val center = NSNotificationCenter.defaultCenter
        // A phone call or another app's audio stops our player. Start again when it ends.
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeEnded) resume()
        }
        // The audio system restarted: the old player is dead, make a new one.
        center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, NSOperationQueue.mainQueue) { _ ->
            player = null
            resume()
        }
        // Backstop for anything missed above.
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ -> resume() }
    }

    /** Sets the session to "playback, mix with others" and makes sure the silent loop plays. */
    fun resume() {
        if (!preferences.get(BooleanKey.IosKeepRunningInBackground) || alarmPlaying) return
        memScoped {
            val session = AVAudioSession.sharedInstance()
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!session.setCategory(AVAudioSessionCategoryPlayback, withOptions = AVAudioSessionCategoryOptionMixWithOthers, error = error.ptr)) {
                aapsLogger.error(LTag.CORE, "Keep alive: cannot set the audio category: ${error.value?.localizedDescription}")
            }
            if (!session.setActive(true, error = error.ptr)) {
                aapsLogger.error(LTag.CORE, "Keep alive: cannot activate the audio session: ${error.value?.localizedDescription}")
            }
        }
        val current = player ?: createPlayer() ?: return
        player = current
        if (!current.playing) {
            if (current.play()) aapsLogger.debug(LTag.CORE, "Keep alive: silent audio playing")
            else aapsLogger.error(LTag.CORE, "Keep alive: silent audio did not start")
        }
    }

    private fun stopPlaying() {
        player?.stop()
        player = null
        aapsLogger.debug(LTag.CORE, "Keep alive: off")
    }

    private fun createPlayer(): AVAudioPlayer? = memScoped {
        val wav = silentWav()
        val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
        val error = alloc<ObjCObjectVar<NSError?>>()
        val created = AVAudioPlayer(data = data, error = error.ptr)
        error.value?.let {
            aapsLogger.error(LTag.CORE, "Keep alive: cannot create the silent player: ${it.localizedDescription}")
            return@memScoped null
        }
        created.numberOfLoops = -1 // for ever
        created.prepareToPlay()
        created
    }
}

/** One second of silence as a WAV file: 8000 Hz, mono, 16 bit. Made in code, so no sound file is needed. */
internal fun silentWav(): ByteArray {
    val sampleRate = 8000
    val dataSize = sampleRate * 2
    val out = ByteArray(44 + dataSize) // samples stay 0 = silence
    fun text(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
    fun int32(at: Int, v: Int) = repeat(4) { out[at + it] = (v shr (8 * it)).toByte() }
    fun int16(at: Int, v: Int) = repeat(2) { out[at + it] = (v shr (8 * it)).toByte() }
    text(0, "RIFF"); int32(4, 36 + dataSize); text(8, "WAVE")
    text(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
    int32(24, sampleRate); int32(28, sampleRate * 2); int16(32, 2); int16(34, 16)
    text(36, "data"); int32(40, dataSize)
    return out
}
