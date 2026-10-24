package app.aaps.ui.compose.overview

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Landscape layout setting (2026-10-24): auto keeps the old rule, wide and split force one. */
class OverviewLayoutTest {

    @Test
    fun portraitIsAlwaysWide() {
        assertEquals(OverviewLayout.WIDE, overviewLayout("split", landscape = false, tabletScreen = true, splitFits = true, wideDesktopWindow = false))
    }

    @Test
    fun autoKeepsTheOldRule() {
        // A phone in landscape: wide (since 26 Sep). A tablet: its split. A wide desktop window: wide.
        assertEquals(OverviewLayout.WIDE, overviewLayout("auto", landscape = true, tabletScreen = false, splitFits = true, wideDesktopWindow = false))
        assertEquals(OverviewLayout.TABLET, overviewLayout("auto", landscape = true, tabletScreen = true, splitFits = true, wideDesktopWindow = false))
        assertEquals(OverviewLayout.WIDE, overviewLayout("auto", landscape = true, tabletScreen = true, splitFits = true, wideDesktopWindow = true))
    }

    @Test
    fun splitForcesTheSplitWhereItFits() {
        assertEquals(OverviewLayout.SPLIT, overviewLayout("split", landscape = true, tabletScreen = false, splitFits = true, wideDesktopWindow = false))
        assertEquals(OverviewLayout.TABLET, overviewLayout("split", landscape = true, tabletScreen = true, splitFits = true, wideDesktopWindow = true))
        assertEquals(OverviewLayout.WIDE, overviewLayout("split", landscape = true, tabletScreen = false, splitFits = false, wideDesktopWindow = false))
    }

    @Test
    fun wideForcesWide() {
        assertEquals(OverviewLayout.WIDE, overviewLayout("wide", landscape = true, tabletScreen = true, splitFits = true, wideDesktopWindow = false))
    }
}
