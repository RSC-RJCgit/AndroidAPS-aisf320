package app.aaps.plugins.automation.actions

import app.aaps.core.keys.IntKey
import app.aaps.plugins.automation.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

class ActionBolusWizardPercentageTest : ActionsTestBase() {

    private lateinit var sut: ActionBolusWizardPercentage

    @BeforeEach fun setUp() {
        whenever(rh.gs(R.string.set_bolus_wizard_percentage_to, 100)).thenReturn("Set bolus wizard percentage to 100%")
        sut = ActionBolusWizardPercentage(injector)
    }

    @Test fun friendlyName() {
        assertThat(sut.friendlyName()).isEqualTo(R.string.set_bolus_wizard_percentage)
    }

    @Test fun shortDescription() {
        assertThat(sut.shortDescription()).isEqualTo("Set bolus wizard percentage to 100%")
    }

    @Test fun doAction() = runTest {
        sut.percentage = 75
        val result = sut.doAction()

        assertThat(result.success).isTrue()
        assertThat(result.comment).isEqualTo("OK")
        verify(preferences).put(IntKey.OverviewBolusPercentage, 75)
    }

    @Test fun jsonRoundTrip() {
        sut.percentage = 80
        JSONAssert.assertEquals(
            """{"type":"ActionBolusWizardPercentage","data":{"percentage":80}}""",
            sut.toJSON(),
            true
        )

        val clone = ActionBolusWizardPercentage(injector).fromJSON("""{"percentage":80}""") as ActionBolusWizardPercentage
        assertThat(clone.percentage).isEqualTo(80)
    }

    @Test fun validatesPreferenceRange() {
        sut.percentage = 10
        assertThat(sut.isValid()).isTrue()
        sut.percentage = 200
        assertThat(sut.isValid()).isTrue()
        sut.percentage = 201
        assertThat(sut.isValid()).isFalse()
    }
}
