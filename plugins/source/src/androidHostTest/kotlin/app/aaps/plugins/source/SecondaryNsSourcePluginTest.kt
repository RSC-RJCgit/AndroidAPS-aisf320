package app.aaps.plugins.source

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock

class SecondaryNsSourcePluginTest : TestBase() {

    private lateinit var plugin: SecondaryNsSourcePlugin

    @Mock lateinit var rh: ResourceHelper

    @BeforeEach
    fun setup() {
        plugin = SecondaryNsSourcePlugin(rh, aapsLogger, mock())
    }

    @Test
    fun `it is a BG source and it is not the default`() {
        assertThat(plugin.getType()).isEqualTo(PluginType.BGSOURCE)
        assertThat(plugin.isDefault()).isFalse()
        assertThat(plugin.pluginDescription.pluginName).isEqualTo(SourceStrings.secondary_ns_bg)
    }
}
