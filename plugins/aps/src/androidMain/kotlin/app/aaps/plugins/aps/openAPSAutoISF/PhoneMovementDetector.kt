package app.aaps.plugins.aps.openAPSAutoISF

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Writes the last phone movement time. The loop reads it when judging step activity. */
class PhoneMovementDetector : SensorEventListener {

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return
        val gravity = SensorManager.GRAVITY_EARTH
        val acceleration = (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]) / (gravity * gravity)
        if (acceleration > 1.05) PhoneMotion.markMoved(System.currentTimeMillis())
    }
}
