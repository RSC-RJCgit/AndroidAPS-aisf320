package app.aaps.plugins.automation.triggers

import app.aaps.plugins.automation.asJsonObject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

class TriggerProfileTest : TriggerTestBase() {

    @BeforeEach fun mock() {
        runBlocking {
            whenever(profileFunction.getOriginalProfileName()).thenReturn("Standard")
        }
    }

    @Test fun runsOnlyForTheRunningProfile() = runTest {
        assertThat(TriggerProfile(triggerDeps).setValue("Standard").shouldRun()).isTrue()
        assertThat(TriggerProfile(triggerDeps).setValue("Low").shouldRun()).isFalse()
        assertThat(TriggerProfile(triggerDeps).shouldRun()).isFalse()
    }

    @Test fun copyConstructorTest() = runTest {
        val t1 = TriggerProfile(triggerDeps).setValue("Low").duplicate() as TriggerProfile
        assertThat(t1.profileName.value).isEqualTo("Low")
    }

    private val json = "{\"data\":{\"profileName\":\"Standard\"},\"type\":\"TriggerProfile\"}"

    @Test fun toJSONTest() = runTest {
        JSONAssert.assertEquals(json, TriggerProfile(triggerDeps).setValue("Standard").toJSON(), true)
    }

    @Test fun fromJSONTest() = runTest {
        val t = triggerFactory.instantiate(TriggerProfile(triggerDeps).setValue("Standard").toJSON().asJsonObject()) as TriggerProfile
        assertThat(t.profileName.value).isEqualTo("Standard")
    }
}
