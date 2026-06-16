package app.aaps.ui.compose.afrezzaDialog

object AfrezzaMaxBasalState {
    @Volatile var endTime: Long = 0L
    @Volatile var rate: Double = 2.0
    @Volatile var cobZeroSince: Long = 0L
    val isActive: Boolean get() = endTime > System.currentTimeMillis()
    val remainingMinutes: Int get() = if (isActive) ((endTime - System.currentTimeMillis()) / 60_000L).toInt() else 0

    fun activate(rateUh: Double, durationMinutes: Int) {
        rate = rateUh
        cobZeroSince = 0L
        endTime = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    fun cancel() {
        endTime = 0L
        cobZeroSince = 0L
    }
}
