package app.aaps.core.objects.utils

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.WizardActivitySteps
import app.aaps.core.utils.LiveStepsMirror
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import javax.inject.Provider

class StepCountSourceTest {
    private val preferences: Preferences = mock()
    private val config: Config = mock()
    private val activePlugin: ActivePlugin = mock()
    private val mirror: LiveStepsMirror = mock()
    private val db: PersistenceLayer = mock()
    private val now = 1_789_666_000_000L
    private val source = StepCountSource(preferences, config, Provider { activePlugin }, mirror, db)

    private fun enableMirror() {
        whenever(preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(Mockito.mock(Pump::class.java, Mockito.withSettings().extraInterfaces(VirtualPump::class.java)))
    }

    @Test fun `mirror mode never queries local steps even when remote is absent`() {
        enableMirror()
        assertThat(source.latest(now, LiveStepsMirror.MAX_AGE_MS)).isNull()
        assertThat(WizardActivitySteps.stillMovingNow(source, now)).isNull()
        verifyNoInteractions(db)
    }

    @Test fun `wizard and automation use remote counts without smartphone recording`() {
        enableMirror()
        whenever(mirror.at(now)).thenReturn(LiveStepsMirror.Sample(now - 1000, "openaps://Live", mapOf(5 to 10, 10 to 20, 15 to 30, 30 to 250)))
        assertThat(source.latest(now, LiveStepsMirror.MAX_AGE_MS)?.get(15)).isEqualTo(60)
        assertThat(WizardActivitySteps.stillMovingNow(source, now)).isTrue()
        verifyNoInteractions(db)
    }

    @Test fun `known remote zero means stationary and differs from missing`() {
        enableMirror()
        whenever(mirror.at(now)).thenReturn(LiveStepsMirror.Sample(now, "openaps://Live", mapOf(5 to 0, 30 to 0)))
        assertThat(WizardActivitySteps.stillMovingNow(source, now)).isFalse()
        whenever(mirror.at(now)).thenReturn(LiveStepsMirror.Sample(now, "openaps://Live", mapOf(5 to 0)))
        assertThat(WizardActivitySteps.stillMovingNow(source, now)).isNull()
    }

    @Test fun `mirror off retains local source`() {
        val sample = SC(duration = 0, timestamp = now, steps5min = 5, steps10min = 10, steps15min = 15,
            steps30min = 30, steps60min = 60, steps180min = 180, device = "local")
        whenever(db.getStepsCountFromTimeToTime(now - 900_000, now)).thenReturn(listOf(sample))
        assertThat(source.latest(now, 900_000)?.get(60)).isEqualTo(60)
        verifyNoInteractions(mirror)
    }

    // 2026-11-20: Live keeps no SC rows, so the newest loop result's step lines count (fpu2 of 10 went in whole at S5 199)
    @Test fun `without SC rows the newest loop result's steps count`() {
        whenever(db.getStepsCountFromTimeToTime(now - 1_200_000, now)).thenReturn(emptyList())
        val older: APSResult = mock()
        whenever(older.date).thenReturn(now - 300_000)
        whenever(older.reason).thenReturn("Steps5M: 0; Steps10M: 0; Steps15M: 0; Steps30M: 0; Steps60M: 0")
        val newest: APSResult = mock()
        whenever(newest.date).thenReturn(now - 60_000)
        whenever(newest.reason).thenReturn("Steps5M: 199; Steps10M: 0; Steps15M: 0; Steps30M: 495; Steps60M: 495")
        whenever(db.getApsResults(now - 1_200_000, now)).thenReturn(listOf(older, newest))
        val steps = source.latest(now, 1_200_000)
        assertThat(steps?.get(5)).isEqualTo(199)
        assertThat(steps?.get(30)).isEqualTo(495)
        assertThat(steps?.get(60)).isEqualTo(495)
    }

    @Test fun `no SC rows and no loop result steps means no steps`() {
        whenever(db.getStepsCountFromTimeToTime(now - 1_200_000, now)).thenReturn(emptyList())
        whenever(db.getApsResults(now - 1_200_000, now)).thenReturn(emptyList())
        assertThat(source.latest(now, 1_200_000)).isNull()
    }

    @Test fun `client mode does not use Virtual mirror`() {
        enableMirror()
        whenever(config.AAPSCLIENT).thenReturn(true)
        assertThat(source.isMirroring()).isFalse()
        verifyNoInteractions(mirror)
    }
}
