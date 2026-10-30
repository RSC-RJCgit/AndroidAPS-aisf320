package app.aaps.plugins.aps.openAPSAutoISF

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/** Feeds the phone's step counter total to [PhoneSteps] (2026-10-30). Needs "Physical activity" on Android 10 and later. */
class PhoneStepListener : SensorEventListener {

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        val total = event?.values?.firstOrNull() ?: return
        PhoneSteps.onTotal(total.toInt(), System.currentTimeMillis())
    }
}
