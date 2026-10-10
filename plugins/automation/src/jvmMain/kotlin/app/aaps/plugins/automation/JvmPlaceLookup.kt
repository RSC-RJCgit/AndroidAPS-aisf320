package app.aaps.plugins.automation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@ContributesBinding(AppScope::class)
@Inject
class JvmPlaceLookup : PlaceLookup {
    override fun find(query: String): Pair<Double, Double>? = null
    override fun model(): String = ""
}
