package app.aaps.core.objects.wizard

import app.aaps.core.data.model.CarbsType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.keys.StringNonKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock

class QuickWizardTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var bolusWizard: BolusWizard
    @Mock lateinit var quickWizardMock: QuickWizard

    private val data1 = "{\"buttonText\":\"Meal\",\"carbs\":36,\"validFrom\":0,\"validTo\":18000," +
        "\"useBG\":0,\"useCOB\":0,\"useBolusIOB\":0,\"useBasalIOB\":0,\"useTrend\":0,\"useSuperBolus\":0,\"useTemptarget\":0}"
    private val data2 = "{\"buttonText\":\"Lunch\",\"carbs\":18,\"validFrom\":36000,\"validTo\":39600," +
        "\"useBG\":0,\"useCOB\":0,\"useBolusIOB\":1,\"useBasalIOB\":2,\"useTrend\":0,\"useSuperBolus\":0,\"useTemptarget\":0}"
    private var array: List<QuickWizardEntryData> = entries(data1, data2)

    /**
     * Keeps the test data as the JSON text a real preference holds, and parses it the same way the
     * production code does, so this still covers [QuickWizardEntryData.fromJsonObject].
     */
    private fun entries(vararg json: String): List<QuickWizardEntryData> =
        json.map { QuickWizardEntryData.fromJsonObject(Json.parseToJsonElement(it) as JsonObject) }

    class MockedTime : QuickWizardEntry.Time() {

        override fun secondsFromMidnight() = 0
    }

    private val mockedTime = MockedTime()
    private lateinit var quickWizard: QuickWizard

    @BeforeEach
    fun setup() {
        whenever(preferences.get(StringNonKey.QuickWizard)).thenReturn("[]")
        whenever(preferences.observe(StringNonKey.QuickWizard)).thenReturn(MutableStateFlow("[]"))
        val quickWizardEntry = QuickWizardEntry(aapsLogger, preferences, profileFunction, loop, iobCobCalculator, persistenceLayer, dateUtil, glucoseStatusProvider, mock(), mock(), { bolusWizard }, { quickWizardMock })
        quickWizardEntry.time = mockedTime
        quickWizard = QuickWizard(preferences) { quickWizardEntry }
    }

    @Test
    fun setDataTest() {
        quickWizard.setData(array)
        assertThat(quickWizard.size()).isEqualTo(2)
    }

    @Test
    fun test() {
        quickWizard.setData(array)
        assertThat(quickWizard[1].buttonText()).isEqualTo("Lunch")
    }

    @Test
    fun active() {
        quickWizard.setData(array)
        val e: QuickWizardEntry = quickWizard.getActive()!!
        assertThat(e.carbs().toDouble()).isWithin(0.01).of(36.0)
        quickWizard.remove(0)
        quickWizard.remove(0)
        assertThat(quickWizard.getActive()).isNull()
    }

    @Test
    fun newEmptyItemTest() {
        assertThat(quickWizard.newEmptyItem()).isNotNull()
    }

    @Test
    fun addOrUpdate() {
        quickWizard.setData(array)
        assertThat(quickWizard.size()).isEqualTo(2)
        quickWizard.addOrUpdate(quickWizard.newEmptyItem())
        assertThat(quickWizard.size()).isEqualTo(3)
        val q: QuickWizardEntry = quickWizard.newEmptyItem()
        q.position = 0
        quickWizard.addOrUpdate(q)
        assertThat(quickWizard.size()).isEqualTo(3)
    }

    @Test
    fun remove() {
        quickWizard.setData(array)
        quickWizard.remove(0)
        assertThat(quickWizard.size()).isEqualTo(1)
    }

    private val data3 = "{\"buttonText\":\"Dinner\",\"carbs\":24,\"validFrom\":0,\"validTo\":18000," +
        "\"useBG\":0,\"useCOB\":0,\"useBolusIOB\":0,\"useBasalIOB\":0,\"useTrend\":0,\"useSuperBolus\":0,\"useTemptarget\":0}"

    private fun buttonTexts() = (0 until quickWizard.size()).map { quickWizard[it].buttonText() }

    @Test
    fun reorderAppliesThePermutation() {
        quickWizard.setData(entries(data1, data2, data3))

        // order[newIndex] == oldIndex, so this reads "Dinner, Meal, Lunch".
        assertThat(quickWizard.reorder(listOf(2, 0, 1))).isTrue()

        assertThat(buttonTexts()).containsExactly("Dinner", "Meal", "Lunch").inOrder()
    }

    @Test
    fun reorderWithTheIdentityOrderDoesNotWrite() {
        quickWizard.setData(entries(data1, data2))
        clearInvocations(preferences)

        assertThat(quickWizard.reorder(listOf(0, 1))).isTrue()

        assertThat(buttonTexts()).containsExactly("Meal", "Lunch").inOrder()
        // The entries ride a bidirectionally synced preference, so a pointless write would be a full
        // round trip to the paired device.
        verify(preferences, never()).put(eq(StringNonKey.QuickWizard), any<String>())
    }

    @Test
    fun reorderRejectsAnOrderThatIsNotAPermutation() {
        quickWizard.setData(entries(data1, data2, data3))

        assertThat(quickWizard.reorder(listOf(0, 1))).isFalse()          // too short
        assertThat(quickWizard.reorder(listOf(0, 1, 1))).isFalse()       // duplicate
        assertThat(quickWizard.reorder(listOf(0, 1, 5))).isFalse()       // out of range

        assertThat(buttonTexts()).containsExactly("Meal", "Lunch", "Dinner").inOrder()
    }

    @Test
    fun mealTypeSplitsCarbsAsTheWizard() {
        // Cake: 90% of the carbs now, 20% as extended carbs from +60 min over 2 h. The button's own fixed extended carbs are not used.
        quickWizard.setData(entries("{\"buttonText\":\"Pasta\",\"carbs\":20,\"validFrom\":0,\"validTo\":86340,\"unreliableSmb\":false,\"autoFpu\":false,\"carbsType\":\"CAKE\",\"useEcarbs\":0,\"carbs2\":30,\"time\":15,\"duration\":4}"))
        val e = quickWizard[0]
        assertThat(e.carbsNow()).isEqualTo(18)
        assertThat(e.eCarbsGrams()).isEqualTo(4)
        assertThat(e.eCarbsDelayMinutes()).isEqualTo(60)
        assertThat(e.eCarbsDurationHours()).isEqualTo(2)
    }

    @Test
    fun buttonWithoutMealTypeKeepsItsOwnExtendedCarbs() {
        quickWizard.setData(entries("{\"buttonText\":\"Pasta\",\"carbs\":20,\"validFrom\":0,\"validTo\":86340,\"unreliableSmb\":false,\"autoFpu\":false,\"useEcarbs\":0,\"carbs2\":30,\"time\":15,\"duration\":4}"))
        val e = quickWizard[0]
        assertThat(e.carbsType()).isEqualTo(CarbsType.BREAD)
        assertThat(e.carbsNow()).isEqualTo(20)
        assertThat(e.eCarbsGrams()).isEqualTo(30)
        assertThat(e.eCarbsDelayMinutes()).isEqualTo(15)
        assertThat(e.eCarbsDurationHours()).isEqualTo(4)
    }

    @Test
    fun automaticFatAndProteinReplaceTheMealTypeExtendedCarbs() {
        quickWizard.setData(entries("{\"buttonText\":\"Pasta\",\"carbs\":20,\"validFrom\":0,\"validTo\":86340,\"unreliableSmb\":false,\"autoFpu\":true,\"carbsType\":\"CAKE\"}"))
        val e = quickWizard[0]
        assertThat(e.carbsNow()).isEqualTo(18)
        assertThat(e.eCarbsGrams()).isEqualTo(0)
    }
}
