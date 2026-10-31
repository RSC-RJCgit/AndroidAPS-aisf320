package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.configuration.Config
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Where AutoISF may run, and so where it is the default APS (2026-11-01). */
class AutoIsfAvailabilityTest {

    private fun config(engineering: Boolean = false, client: Boolean = false, pumpDrivers: Boolean = true): Config = mock<Config>().also {
        whenever(it.isEngineeringMode()).thenReturn(engineering)
        whenever(it.AAPSCLIENT).thenReturn(client)
        whenever(it.PUMPDRIVERS).thenReturn(pumpDrivers)
    }

    /** The real-pump master keeps upstream's engineering-mode safeguard */
    @Test
    fun aMasterWithPumpDriversNeedsEngineeringMode() {
        assertThat(autoIsfAllowed(config())).isFalse()
        assertThat(autoIsfAllowed(config(engineering = true))).isTrue()
    }

    @Test
    fun aClientMayAlwaysRunIt() {
        assertThat(autoIsfAllowed(config(client = true))).isTrue()
    }

    /** iOS and desktop masters: Virtual pump only. The iOS master could never run AutoISF before */
    @Test
    fun aVirtualOnlyMasterMayRunItWithoutEngineeringMode() {
        assertThat(autoIsfAllowed(config(pumpDrivers = false))).isTrue()
    }
}
