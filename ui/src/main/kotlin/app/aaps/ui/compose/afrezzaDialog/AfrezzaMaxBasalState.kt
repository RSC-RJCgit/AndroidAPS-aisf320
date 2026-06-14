package app.aaps.ui.compose.afrezzaDialog

object AfrezzaMaxBasalState {
    @Volatile var endTime: Long = 0L
    val isActive: Boolean get() = endTime > System.currentTimeMillis()
}
