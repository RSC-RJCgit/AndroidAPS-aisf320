package app.aaps.plugins.automation

import android.content.Context
import android.location.Geocoder
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.util.Locale

@ContributesBinding(AppScope::class)
@Inject
class AndroidPlaceLookup(
    private val context: Context,
) : PlaceLookup {

    override fun model(): String = Build.MODEL.orEmpty().trim()

    override fun find(query: String): Pair<Double, Double>? = try {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
            ?.firstOrNull()
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }
}
