package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrioIobStoreTest {

    @Test
    fun `reloading keeps reported values and changing site cannot show the old source`() {
        val preferences = mock<Preferences>()
        var saved = ""
        whenever(preferences.get(StringNonKey.NsClientTrioIobHistory)).thenAnswer { saved }
        doAnswer { saved = it.arguments[1] as String; null }
            .whenever(preferences).put(eq(StringNonKey.NsClientTrioIobHistory), any<String>())
        val store = ProcessedDeviceStatusDataImpl({ error("Display history must not request a dosing result") }, preferences)
        val point = GraphDataPoint(1_800_000_000_000L, 0.0)

        store.storeTrioIob("one", listOf(point), point.timestamp)
        val reloaded = ProcessedDeviceStatusDataImpl({ error("No dosing result") }, preferences)

        assertEquals(listOf(point), reloaded.trioIobHistory("one", point.timestamp, point.timestamp))
        assertNull(reloaded.trioIobHistory("two", 0L, Long.MAX_VALUE))
        assertEquals(emptyList(), reloaded.trioIobHistory("one", 0L, point.timestamp - 1))
        reloaded.storeTrioIob("two", emptyList(), point.timestamp)
        assertNull(reloaded.trioIobHistory("one", 0L, Long.MAX_VALUE))
        assertEquals(emptyList(), reloaded.trioIobHistory("two", 0L, Long.MAX_VALUE))
    }
}
