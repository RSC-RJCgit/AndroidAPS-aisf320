package app.aaps.implementation.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.aaps.core.interfaces.aps.PhoneSteps
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Reads the phone step counter and the accelerometer, as AutoISF 3.2.1 does in `MainApp`. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidPhoneSteps(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) : PhoneSteps {

    private val blocks = PhoneStepBlocks()
    private var started = false

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            synchronized(blocks) { blocks.onStepCounter(event.values[0].toInt(), System.currentTimeMillis()) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val movementListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            synchronized(blocks) { blocks.onAcceleration(event.values[0], event.values[1], event.values[2], System.currentTimeMillis()) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Without the activity permission the step counter sends nothing; the counts then stay 0.
        if (stepSensor == null) aapsLogger.warn(LTag.APS, "Phone steps: this phone has no step counter")
        else sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (accelerometer == null) aapsLogger.warn(LTag.APS, "Phone steps: this phone has no accelerometer")
        else sensorManager.registerListener(movementListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun <T> read(block: PhoneStepBlocks.(Long) -> T): T = synchronized(blocks) { blocks.block(System.currentTimeMillis()) }

    override fun steps5Min(): Int = read { steps5Min(it) }
    override fun steps10Min(): Int = read { steps10Min(it) }
    override fun steps15Min(): Int = read { steps15Min(it) }
    override fun steps30Min(): Int = read { steps30Min(it) }
    override fun steps60Min(): Int = read { steps60Min(it) }
    override fun steps180Min(): Int = read { steps180Min(it) }
    override fun phoneMoved(): Boolean = read { phoneMoved(it) }
}
