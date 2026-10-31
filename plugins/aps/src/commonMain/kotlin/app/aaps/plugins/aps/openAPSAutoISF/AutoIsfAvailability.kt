package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.interfaces.configuration.Config

/**
 * Whether this build may run AutoISF, and so whether it is the APS picked when none is stored (2026-11-01, per explicit
 * request: this fork is all AutoISF).
 *
 * - Engineering mode, as upstream.
 * - A client (Android, iOS, desktop): it never doses.
 * - A build with no pump drivers, the Virtual-pump masters on iOS and the desktop: the Virtual pump delivers nothing,
 *   and an iPhone has no practical way to make the engineering_mode file, so the iOS master could never run AutoISF -
 *   every settings import briefly started it and the gate stopped it again, leaving OpenAPS SMB.
 *
 * A master with real pump drivers still needs engineering mode. Read from the build only, so it is the same for the
 * whole process, as an enforcement must be.
 */
internal fun autoIsfAllowed(config: Config): Boolean =
    config.isEngineeringMode() || config.AAPSCLIENT || !config.PUMPDRIVERS
