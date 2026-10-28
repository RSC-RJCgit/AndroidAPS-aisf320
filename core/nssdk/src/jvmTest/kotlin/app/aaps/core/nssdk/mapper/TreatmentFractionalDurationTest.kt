package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.NSResponse
import app.aaps.core.nssdk.remotemodel.RemoteTreatment
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test

class TreatmentFractionalDurationTest {
    @Test
    fun `fractional temp basal minutes preserve seconds`() {
        val basal = """{"eventType":"Temp Basal","absolute":0.35,"duration":1.25,
            "created_at":"2026-10-27T12:00:00Z","identifier":"basal-id"}""".toNSTreatment() as NSTemporaryBasal
        assertThat(basal.duration).isEqualTo(75_000L)
        assertThat(basal.identifier).isEqualTo("basal-id")
        assertThat((basal.convertToRemoteAndBack() as NSTemporaryBasal).duration).isEqualTo(75_000L)
    }

    @Test
    fun `explicit milliseconds remain authoritative over fractional minutes`() {
        val basal = """{"eventType":"Temp Basal","absolute":0.35,"duration":1.25,
            "durationInMilliseconds":90000,"created_at":"2026-10-27T12:00:00Z"}""".toNSTreatment() as NSTemporaryBasal
        assertThat(basal.duration).isEqualTo(90_000L)
    }

    @Test
    fun `fractional carb duration is not truncated to whole minutes`() {
        val carbs = """{"eventType":"Carb Correction","carbs":10,"duration":30.5,
            "created_at":"2026-10-27T12:00:00Z"}""".toNSTreatment() as NSCarbs
        assertThat(carbs.duration).isEqualTo(1_830_000L)
    }

    @Test
    fun `a mixed treatment page retains bolus carbs SMB and fractional basal`() {
        val response = nsSdkJson.decodeFromString(
            NSResponse.serializer(ListSerializer(RemoteTreatment.serializer())),
            """{"result":[
                {"eventType":"Temp Basal","absolute":0.35,"duration":1.25,"created_at":"2026-10-27T12:00:00Z"},
                {"eventType":"Bolus","insulin":0.5,"created_at":"2026-10-27T12:00:00Z"},
                {"eventType":"SMB","insulin":0.05,"created_at":"2026-10-27T12:00:00Z"},
                {"eventType":"Carb Correction","carbs":10,"duration":0,"created_at":"2026-10-27T12:00:00Z"}
            ]}"""
        )
        val treatments = checkNotNull(response.result).mapNotNull { it.toTreatment() }
        assertThat(treatments).hasSize(4)
        assertThat((treatments[0] as NSTemporaryBasal).duration).isEqualTo(75_000L)
        assertThat((treatments[1] as NSBolus).type).isEqualTo(NSBolus.BolusType.NORMAL)
        assertThat((treatments[2] as NSBolus).type).isEqualTo(NSBolus.BolusType.SMB)
        assertThat((treatments[3] as NSCarbs).duration).isEqualTo(0L)
    }
}
