package app.aaps.core.graph.data

// Graph4 stacked notes are ≤5 characters. A raw take(5) made several families
// draw as the same tag (HiBrk*, Steroids*, Sub75*, EvCap/EvCapR, …).
// Map only the collisions; everything else stays first-5.
// Full CarePortal / NS text is unchanged.
object Graph4NoteLabel {

    private val exact = mapOf(
        "HiBrk" to "HiBrk",
        "HiBrkCut" to "HBCut",
        "HiBrkDay" to "HBDay",
        "HiBrkDayMid" to "HBMid",
        "HiBrkDayCut" to "HBDCt",
        "ActTToff1" to "AcTf1",
        "ActTToff2" to "AcTf2",
        "BMildFS" to "BmFS",
        "EvCapR" to "EvCaR",
        "NtCapR" to "NtCaR",
        "LocPhOn" to "LPhOn",
        "LocPhOff" to "LPhOf",
        "SaAutoOn" to "SaAOn",
        "SaAutoOff" to "SaAOf",
        "UKF1VOn" to "U1VOn",
        "UKF1VOff" to "U1VOf",
        "MJ active" to "MJact",
        "SteroidsON" to "StON",
        "SteroidsOff" to "StOf",
        "Steroids130" to "St130",
        "Steroids150" to "St150",
        "Steroids190" to "St190",
        "Steroids250" to "St250",
        "OldSensorOff" to "OSOff",
        "OldSensorNewDay1" to "OSNd1",
        "OldSensorNewDay2" to "OSNd2",
        "OldSensorNewDay3" to "OSNd3",
        "OldSensor1" to "OS1",
        "OldSensor2" to "OS2",
        "OldSensor3" to "OS3",
        // Added 2026-09-12: OldPodInsReqBoost's fire/revert pair both take(5) to "OldPo" -- same
        // collision class as HiBrk*/Steroids* above.
        "OldPodBst" to "OPBst",
        "OldPodBstOff" to "OPBOf",
        // Added 2026-09-12: AdbWirelessStarter's outcome pair both take(5) to "AdbSt".
        "AdbStOk" to "AdSOk",
        "AdbStNg" to "AdSNg"
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
