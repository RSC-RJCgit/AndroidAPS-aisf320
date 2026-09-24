package app.aaps.plugins.sync.nsShared

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump

/**
 * Upload brake (2026-09-24, per explicit request): a full AAPS (not the AAPSClient app) running on VirtualPump must never
 * upload to Nightscout, whatever the "Upload data to NS" switch says. Its boluses, carbs, targets and device status are
 * simulated; on the shared Nightscout site they would be indistinguishable from the real loop phone's. AAPSClient is exempt:
 * its uploads carry the relayed List 1 / List 2 commands and notes, and its pump also reports as virtual.
 */
fun ActivePlugin.uploadBlockedOnVirtualPump(config: Config): Boolean = !config.AAPSCLIENT && activePump is VirtualPump
