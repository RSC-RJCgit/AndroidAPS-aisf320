package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrioLoopResultTest {
    private fun objectFrom(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val sample = objectFrom(
        """{"timestamp":"2026-10-27T14:24:34.816Z","bg":133,"eventualBG":154,
            "current_target":111,"IOB":0.8,"COB":7,"ISF":90,"rate":0.35,"duration":30,
            "auto_ISFratio":1.2,"acce_ISFratio":1.1,"bg_ISFratio":1.05,"pp_ISFratio":1.15,
            "dura_ISFratio":1.08,"bg_acce":-5.1343,"SMBratio":0.8,"iob_THeffective":2,
            "predBGs":{"IOB":[133,128],"ZT":[133,133]},"reason":"Trio result"}"""
    )

    @Test
    fun trioFieldsFillTheAivRowWithoutInferringAnAlgorithm() {
        val result = receivedLoopResult("Trio", sample, GlucoseUnit.MGDL)
        val row = assertNotNull(receivedAutoIsfRow(1L, result) { error("No text delta needs conversion") })
        assertEquals(111.0, result.targetBG)
        assertEquals(90.0, result.variable_sens)
        assertEquals(1.2, row.finalIsf)
        assertEquals(1.1, row.acceIsf)
        assertEquals(1.05, row.bgIsf)
        assertEquals(1.15, row.ppIsf)
        assertEquals(1.08, row.duraIsf)
        assertEquals(-5.1343, row.bgAcceleration)
        assertEquals(0.8, row.smbDeliveryRatio)
        assertEquals(2.0, row.iobThEffective)
        assertEquals(111.0, row.targetMgdl)
        assertEquals(0.8, row.iob)
        assertEquals(7.0, row.cob)
        assertEquals(0.35, row.basal)
        assertEquals(APSResult.Algorithm.UNKNOWN, result.algorithm)
        assertEquals(30, result.duration)
        assertEquals(listOf(133, 128), result.predBGs?.IOB)
    }

    @Test
    fun mmolConvertsOnlyTheDisplayedTarget() {
        val data = JsonObject(sample.toMutableMap().apply { put("current_target", JsonPrimitive(6.2)) })
        val result = receivedLoopResult("Trio", data, GlucoseUnit.MMOL)
        assertEquals(111.696658, assertNotNull(result.targetBG), 0.000001)
        assertEquals(133.0, result.bg)
        assertEquals(154.0, result.eventualBG)
        assertEquals(90.0, result.variable_sens)
        assertEquals(listOf(133, 128), result.predBGs?.IOB)
        assertEquals(-5.1343, result.autoIsfBgAcceleration)
    }

    @Test
    fun nativeValuesWinAndOtherSendersAreNotChanged() {
        val data = objectFrom("""{"targetBG":100,"current_target":6.2,"variable_sens":88,"ISF":90,
            "autoIsfFinal":1.4,"auto_ISFratio":1.2,"algorithm":"SMB"}""")
        val result = receivedLoopResult("Trio", data, GlucoseUnit.MMOL)
        assertEquals(100.0, result.targetBG)
        assertEquals(88.0, result.variable_sens)
        assertEquals(1.4, result.autoIsfFinal)
        assertEquals(APSResult.Algorithm.SMB, result.algorithm)
        val other = receivedLoopResult("openaps://phone", sample, GlucoseUnit.MGDL)
        assertNull(other.targetBG)
        assertNull(other.autoIsfFinal)
        assertNull(other.variable_sens)
    }

    @Test
    fun unknownSourceUnitsLeaveTheTargetUnmapped() {
        val result = receivedLoopResult("Trio", sample, null)
        assertNull(result.targetBG)
        assertEquals(1.2, result.autoIsfFinal)
        assertEquals(90.0, result.variable_sens)
    }

    @Test
    fun sourceProfileUnitsBelongToTheirSiteAndTime() {
        val profile = objectFrom("""{"enteredBy":"Trio","defaultProfile":"default",
            "startDate":"2026-10-14T09:13:00Z","units":"mg/dl",
            "store":{"default":{"units":"mmol"}}}""")
        val record = assertNotNull(trioProfileUnitsRecord(profile, "https://example.invalid/"))
        assertEquals(GlucoseUnit.MMOL, trioSourceUnits(record, "https://example.invalid", Long.MAX_VALUE))
        assertNull(trioSourceUnits(record, "https://other.invalid", Long.MAX_VALUE))
        assertNull(trioSourceUnits(record, "https://example.invalid", 0L))
        assertNull(trioSourceUnits("", "https://example.invalid", Long.MAX_VALUE))
        assertNull(trioProfileUnitsRecord(objectFrom("""{"enteredBy":"AAPS"}"""), "https://example.invalid"))
    }
}
