package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TrioCompatibilityTest {

    @Test
    fun `Trio SMB keeps amount time and server identity and becomes an SMB`() {
        val bolus = """{"eventType":"SMB","insulin":0.25,"created_at":"2026-08-06T04:56:19.555Z",
            "enteredBy":"Trio","id":"trio-source-id","identifier":"server-id"}""".toNSTreatment() as NSBolus

        assertThat(bolus.type).isEqualTo(NSBolus.BolusType.SMB)
        assertThat(bolus.eventType).isEqualTo(EventType.CORRECTION_BOLUS)
        assertThat(bolus.insulin).isEqualTo(0.25)
        assertThat(bolus.date).isEqualTo(1785992179555L)
        assertThat(bolus.identifier).isEqualTo("server-id")
        assertThat((bolus.convertToRemoteAndBack() as NSBolus).type).isEqualTo(NSBolus.BolusType.SMB)
    }

    @Test
    fun `Trio manual bolus stays normal`() {
        val bolus = """{"eventType":"Bolus","insulin":2.0,"date":1785992179555}""".toNSTreatment() as NSBolus
        assertThat(bolus.type).isEqualTo(NSBolus.BolusType.NORMAL)
    }

    @Test
    fun `explicit bolus type takes precedence over foreign event name`() {
        val bolus = """{"eventType":"SMB","type":"NORMAL","insulin":2.0,"date":1785992179555}""".toNSTreatment() as NSBolus
        assertThat(bolus.type).isEqualTo(NSBolus.BolusType.NORMAL)
    }

    @Test
    fun `glucose uses ISO dateString when numeric date is missing`() {
        val reading = """{"type":"sgv","sgv":123,"dateString":"2026-08-06T06:56:19.555+02:00"}""".toNSSgvV3()
        assertThat(reading?.date).isEqualTo(1785992179555L)
        assertThat(reading?.sgv).isEqualTo(123.0)
    }

    @Test
    fun `numeric glucose date wins over a different dateString`() {
        val reading = """{"type":"sgv","sgv":123,"date":1785992179555,"dateString":"2020-01-01T00:00:00Z"}""".toNSSgvV3()
        assertThat(reading?.date).isEqualTo(1785992179555L)
    }

    @Test
    fun `glucose without a readable timestamp is skipped`() {
        assertThat("""{"type":"sgv","sgv":123,"dateString":"invalid"}""".toNSSgvV3()).isNull()
        assertThat("""{"type":"sgv","sgv":123}""".toNSSgvV3()).isNull()
    }

    @Test
    fun `Trio charging state comes from uploader`() {
        val status = """{"device":"Trio","uploader":{"battery":30,"isCharging":true}}""".toNSDeviceStatus()
        assertThat(status.isCharging).isTrue()
        assertThat(status.uploader?.battery).isEqualTo(30)
    }

    @Test
    fun `explicit top level charging state wins`() {
        val status = """{"isCharging":false,"uploader":{"isCharging":true}}""".toNSDeviceStatus()
        assertThat(status.isCharging).isFalse()
    }
}
