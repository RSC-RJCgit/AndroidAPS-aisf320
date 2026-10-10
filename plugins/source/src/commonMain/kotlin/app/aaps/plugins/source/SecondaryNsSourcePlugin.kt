package app.aaps.plugins.source

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.ui.compose.icons.IcPluginNsClientBg
import app.aaps.plugins.source.compose.BgSourceComposeContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * BG source for the second Nightscout.
 *
 * Config Builder keeps one BG source. While this one is selected, xDrip is off, so its
 * packets are discarded and its Libre slope does not run. Glucose is the download from
 * the secondary Nightscout screen.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(411)
@SingleIn(AppScope::class)
@Inject
class SecondaryNsSourcePlugin(
    override val rh: TextResolver,
    aapsLogger: AAPSLogger,
    notificationManager: NotificationManager
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(SourceStrings.secondary_ns_bg)
            )
        }
        .icon(IcPluginNsClientBg)
        .pluginName(SourceStrings.secondary_ns_bg)
        .description(SourceStrings.description_source_secondary_ns),
    aapsLogger, rh, notificationManager
), BgSource
