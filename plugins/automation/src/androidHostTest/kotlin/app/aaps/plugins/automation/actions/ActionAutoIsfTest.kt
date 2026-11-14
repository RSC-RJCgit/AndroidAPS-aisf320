package app.aaps.plugins.automation.actions

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

/** AutoISF fork: the four AutoISF actions, ported from AutoISF 3.2.1 with the same JSON. */
class ActionAutoIsfTest : ActionsTestBase() {

    @Test fun `enable switches the AutoISF weights on, once`() = runTest {
        val sut = ActionAutoisfEnable(aapsLogger, text, { pumpEnactResultProvider() }, preferences)
        whenever(preferences.get(BooleanKey.ApsUseAutoIsfWeights)).thenReturn(false)
        assertThat(sut.doAction().success).isTrue()
        verify(preferences).put(BooleanKey.ApsUseAutoIsfWeights, true)
    }

    @Test fun `disable switches the AutoISF weights off, and does nothing when already off`() = runTest {
        val sut = ActionAutoisfDisable(aapsLogger, text, { pumpEnactResultProvider() }, preferences)
        whenever(preferences.get(BooleanKey.ApsUseAutoIsfWeights)).thenReturn(false)
        assertThat(sut.doAction().success).isTrue()
        verify(preferences, never()).put(BooleanKey.ApsUseAutoIsfWeights, false)
    }

    @Test fun `acce weight is set and kept within 0 to 1`() = runTest {
        val sut = ActionSetAcceWeight(aapsLogger, text, { pumpEnactResultProvider() }, preferences)
        whenever(preferences.get(DoubleKey.ApsAutoIsfBgAccelWeight)).thenReturn(0.0)
        sut.weight = 0.25
        assertThat(sut.doAction().success).isTrue()
        verify(preferences).put(DoubleKey.ApsAutoIsfBgAccelWeight, 0.25)
        sut.fromJSON("""{"weight":7.0}""")
        assertThat(sut.weight).isEqualTo(1.0)
    }

    @Test fun `iobTH is set and kept within 10 to 100 percent`() = runTest {
        val sut = ActionSetIobTH(aapsLogger, text, { pumpEnactResultProvider() }, preferences)
        whenever(preferences.get(IntKey.ApsAutoIsfIobThPercent)).thenReturn(100)
        sut.percent = 70
        assertThat(sut.doAction().success).isTrue()
        verify(preferences).put(IntKey.ApsAutoIsfIobThPercent, 70)
        sut.fromJSON("""{"percentage":5}""")
        assertThat(sut.percent).isEqualTo(10)
    }

    @Test fun `JSON is the one of AutoISF 3_2_1`() = runTest {
        val acce = ActionSetAcceWeight(aapsLogger, text, { pumpEnactResultProvider() }, preferences).also { it.weight = 0.2 }
        JSONAssert.assertEquals("""{"type":"ActionSetAcceWeight","data":{"weight":0.2}}""", acce.toJSON(), true)
        val iobTh = ActionSetIobTH(aapsLogger, text, { pumpEnactResultProvider() }, preferences).also { it.percent = 60 }
        JSONAssert.assertEquals("""{"type":"ActionSetIobTH","data":{"percentage":60}}""", iobTh.toJSON(), true)
    }
}
