package app.aaps.core.interfaces.source

/**
 * Marks the BG source that takes its glucose from the second Nightscout, so sync code can tell it apart without
 * knowing the plugin. While it is the active BG source the phone's own CGM is not in use.
 */
interface SecondaryNsSource : BgSource
