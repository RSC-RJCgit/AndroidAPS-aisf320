package app.aaps.plugins.automation.triggers

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.automation.elements.Comparator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

/** AutoISF fork: the two AutoISF triggers, ported from AutoISF 3.2.1 with the same JSON. */
class TriggerAutoIsfTest : TriggerTestBase() {

    @Test fun `iobTH trigger compares the current iobTH percent`() = runTest {
        whenever(preferences.get(IntKey.ApsAutoIsfIobThPercent)).thenReturn(70)
        val t = TriggerIobTH(triggerDeps).also { it.percent = 80; it.comparator.value = Comparator.Compare.IS_LESSER }
        assertThat(t.shouldRun()).isTrue()
        t.comparator.value = Comparator.Compare.IS_GREATER
        assertThat(t.shouldRun()).isFalse()
    }

    @Test fun `acce weight trigger compares the current acce weight`() = runTest {
        whenever(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)).thenReturn(0.2)
        val t = TriggerBgAcceWeight(triggerDeps).also { it.acceWeight = 0.2; it.comparator.value = Comparator.Compare.IS_EQUAL }
        assertThat(t.shouldRun()).isTrue()
    }

    @Test fun `JSON is the one of AutoISF 3_2_1`() {
        val t = TriggerIobTH(triggerDeps).also { it.percent = 60; it.comparator.value = Comparator.Compare.IS_EQUAL }
        JSONAssert.assertEquals("""{"iobTH_percent":60,"comparator":"IS_EQUAL"}""", t.dataJSON().toString(), true)
        val back = TriggerIobTH(triggerDeps).fromJSON(t.dataJSON().toString()) as TriggerIobTH
        assertThat(back.percent).isEqualTo(60)
        assertThat(back.comparator.value).isEqualTo(Comparator.Compare.IS_EQUAL)
    }
}
