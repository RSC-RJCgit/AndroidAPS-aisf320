package app.aaps.core.graph.data

// Graph4 stacked notes are ≤5 characters. A raw take(5) made every HiBrk* family
// note draw as "HiBrk". Map only the collisions; everything else stays first-5.
// Full CarePortal / NS text is unchanged.
object Graph4NoteLabel {

    private val exact = mapOf(
        "HiBrk" to "HiBrk",
        "HiBrkCut" to "HBCut",
        "HiBrkDay" to "HBDay",
        "HiBrkDayMid" to "HBMid",
        "HiBrkDayCut" to "HBDCt"
    )

    fun display(full: String): String {
        exact[full]?.let { return it }
        if (full.contains('|')) {
            full.split('|').map { it.trim() }.forEach { part ->
                exact[part]?.let { return it }
            }
        }
        return full.take(5)
    }
}
