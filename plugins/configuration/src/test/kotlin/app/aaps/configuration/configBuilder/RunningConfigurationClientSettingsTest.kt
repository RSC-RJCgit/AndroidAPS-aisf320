package app.aaps.configuration.configBuilder

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.plugins.configuration.configBuilder.RunningConfigurationImpl
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class RunningConfigurationClientSettingsTest {

    @Test
    fun masterBroadcastDoesNotSelectPluginsOrApplyTheirSettings() {
        val activePlugin = mock<ActivePlugin>()
        val config = mock<Config>()
        whenever(config.AAPSCLIENT).thenReturn(true)
        val settings = mock<JSONObject>()
        val sut = RunningConfigurationImpl(activePlugin, mock<AAPSLogger>(), config, mock<RxBus>(), mock<PumpSync>())

        sut.apply(NSDeviceStatus.Configuration(
            pump = null, version = null, insulin = 1, aps = "AUTO_ISF", sensitivity = 1, smoothing = "SmoothingPlugin",
            insulinConfiguration = settings, apsConfiguration = settings, sensitivityConfiguration = settings,
            overviewConfiguration = settings, safetyConfiguration = settings
        ))

        verifyNoInteractions(activePlugin, settings)
    }
}
