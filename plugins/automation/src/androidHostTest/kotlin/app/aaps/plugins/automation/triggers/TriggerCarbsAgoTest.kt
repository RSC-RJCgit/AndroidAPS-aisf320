package app.aaps.plugins.automation.triggers

import app.aaps.core.data.model.CA
import app.aaps.plugins.automation.asJsonObject
import app.aaps.plugins.automation.elements.Comparator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

class TriggerCarbsAgoTest : TriggerTestBase() {

    @Test
    fun shouldRunTest() = runTest {
        whenever(persistenceLayer.getNewestCarbs()).thenReturn(CA(timestamp = now, duration = 0L, amount = 20.0))
        whenever(dateUtil.now()).thenReturn(now + 10 * 60 * 1000)
        assertThat(TriggerCarbsAgo(triggerDeps).setValue(10).comparator(Comparator.Compare.IS_EQUAL).shouldRun()).isTrue()
        assertThat(TriggerCarbsAgo(triggerDeps).setValue(30).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER).shouldRun()).isTrue()
        assertThat(TriggerCarbsAgo(triggerDeps).setValue(30).comparator(Comparator.Compare.IS_EQUAL_OR_GREATER).shouldRun()).isFalse()
        whenever(persistenceLayer.getNewestCarbs()).thenReturn(null)
        assertThat(TriggerCarbsAgo(triggerDeps).comparator(Comparator.Compare.IS_NOT_AVAILABLE).shouldRun()).isTrue()
        assertThat(TriggerCarbsAgo(triggerDeps).setValue(30).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER).shouldRun()).isFalse()
    }

    /** A rule stored by the v3 fork (full class name, same data) loads as this trigger, not as an unknown one. */
    @Test
    fun loadsV3StoredRule() {
        val v3 = """{"data":{"comparator":"IS_EQUAL_OR_LESSER","minutesAgo":45},"type":"app.aaps.plugins.automation.triggers.TriggerCarbsAgo"}"""
        val t = triggerFactory.instantiate(v3.asJsonObject())
        assertThat(t).isInstanceOf(TriggerCarbsAgo::class.java)
        t as TriggerCarbsAgo
        assertThat(t.minutesAgo.value).isEqualTo(45)
        assertThat(t.comparator.value).isEqualTo(Comparator.Compare.IS_EQUAL_OR_LESSER)
    }

    @Test
    fun duplicateKeepsValues() {
        val t = TriggerCarbsAgo(triggerDeps).setValue(120).comparator(Comparator.Compare.IS_EQUAL_OR_GREATER)
        val copy = t.duplicate() as TriggerCarbsAgo
        assertThat(copy.minutesAgo.value).isEqualTo(120)
        assertThat(copy.comparator.value).isEqualTo(Comparator.Compare.IS_EQUAL_OR_GREATER)
    }
}
