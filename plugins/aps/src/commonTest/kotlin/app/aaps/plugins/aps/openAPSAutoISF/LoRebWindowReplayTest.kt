package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class LoRebWindowReplayTest {

    private val now = 1_700_000_000_000L

    @Test
    fun risingRecoveryWithCarbsInsideThirtyMinutesIsActive() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 28,
            cob = 5.0,
        )
        assertEquals(30, window.lookbackMinutes)
        assertEquals(true, window.recentAlarm)
        assertEquals(true, window.carbRebound)
        assertEquals(false, window.artifactRebound)
        assertEquals(true, window.active)
    }

    @Test
    fun risingRecoveryJustOutsideThirtyMinutesIsInactive() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 31,
            cob = 5.0,
        )
        assertEquals(30, window.lookbackMinutes)
        assertEquals(false, window.recentAlarm)
        assertEquals(false, window.active)
    }

    @Test
    fun exactThirtyMinuteBoundaryStaysRecent() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 30,
            cob = 5.0,
        )
        assertEquals(true, window.recentAlarm)
        assertEquals(true, window.active)
    }

    @Test
    fun slowingRecoveryKeepsAnOlderAlarmInsideSixtyMinutes() {
        val window = window(
            bg = 126.0,
            delta = -1.0,
            shortDelta = 3.6,
            alarmMinutesAgo = 45,
            cob = 5.0,
        )
        assertEquals(60, window.lookbackMinutes)
        assertEquals(true, window.recentAlarm)
        assertEquals(true, window.active)
    }

    @Test
    fun zeroCobSharpRiseIsAnArtifactRebound() {
        val window = window(
            bg = 144.0,
            delta = 0.42 * 18.0,
            shortDelta = 0.31 * 18.0,
            alarmMinutesAgo = 28,
            cob = 0.0,
        )
        assertEquals(true, window.artifactRebound)
        assertEquals(false, window.carbRebound)
        assertEquals(true, window.active)
    }

    @Test
    fun exactArtifactThresholdsStayOff() {
        val window = window(
            bg = 9.4 * 18.0,
            delta = 0.40 * 18.0,
            shortDelta = 0.30 * 18.0,
            alarmMinutesAgo = 28,
            cob = 0.0,
        )
        assertEquals(false, window.artifactRebound)
        assertEquals(false, window.active)
    }

    @Test
    fun unabsorbedCarbsAtOrAbovePointThreeGramsCount() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 10,
            cob = 0.0,
            uci = 1.5,
            csf = 5.0,
        )
        assertEquals(true, window.carbRebound)
        assertEquals(true, window.active)
    }

    @Test
    fun missingCarbFactorDoesNotInventCarbs() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 10,
            cob = 0.0,
            uci = 2.0,
            csf = 0.0,
        )
        assertEquals(false, window.carbRebound)
        assertEquals(false, window.active)
    }

    @Test
    fun guardOffLeavesTheReboundInactive() {
        val window = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = 10,
            cob = 5.0,
            guardEnabled = false,
        )
        assertEquals(true, window.carbRebound)
        assertEquals(false, window.active)
    }

    @Test
    fun missingOrFutureAlarmIsNotRecent() {
        val missing = window(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            alarmMinutesAgo = null,
            cob = 5.0,
        )
        val future = loRebWindow(
            bg = 126.0,
            delta = 3.6,
            shortDelta = 3.6,
            systemTimeMs = now,
            lastAlarmHypoAtMs = now + 60_000L,
            cob = 5.0,
            uci = 0.0,
            csf = 1.0,
            recentLowReboundGuardEnabled = true,
        )
        assertEquals(false, missing.recentAlarm)
        assertEquals(false, missing.active)
        assertEquals(false, future.recentAlarm)
        assertEquals(false, future.active)
    }

    private fun window(
        bg: Double,
        delta: Double,
        shortDelta: Double,
        alarmMinutesAgo: Int?,
        cob: Double,
        uci: Double = 0.0,
        csf: Double = 1.0,
        guardEnabled: Boolean = true,
    ): LoRebWindow = loRebWindow(
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        systemTimeMs = now,
        lastAlarmHypoAtMs = if (alarmMinutesAgo == null) 0L else now - alarmMinutesAgo * 60_000L,
        cob = cob,
        uci = uci,
        csf = csf,
        recentLowReboundGuardEnabled = guardEnabled,
    )
}
