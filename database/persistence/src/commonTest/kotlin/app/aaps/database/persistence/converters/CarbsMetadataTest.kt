package app.aaps.database.persistence.converters

import app.aaps.core.data.model.CA
import app.aaps.core.data.model.IDs
import kotlin.test.Test
import kotlin.test.assertEquals

class CarbsMetadataTest {
    @Test
    fun nutritionAndSourceLinksSurviveDatabaseConversion() {
        val carbs = CA(
            timestamp = 1785992179555L, duration = 0L, amount = 7.5, notes = "Meal",
            fatGrams = 12.5, proteinGrams = 18.25, sourceId = "source-carb", fpuId = "meal-group",
            ids = IDs(nightscoutId = "server-carb")
        )
        assertEquals(carbs, carbs.toDb().fromDb())
    }
}
