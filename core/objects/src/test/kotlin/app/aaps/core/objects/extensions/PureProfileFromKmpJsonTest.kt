package app.aaps.core.objects.extensions

import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * A profile switch made by a KMP (AAPS 4) client carries a profile without "dia": the insulin duration is in
 * iCfg.insulinEndTime. This build used to drop such a switch without a word (2026-10-15). The JSON below is the shape
 * found on the master's Nightscout site.
 */
class PureProfileFromKmpJsonTest : TestBaseWithProfile() {

    private fun kmpProfile(withICfg: Boolean) = JSONObject(
        """{"units":"mmol",""" +
            (if (withICfg) """"iCfg":{"insulinLabel":"Lyumjev 45m 9h U100","insulinEndTime":32400000,"insulinPeakTime":2700000,"concentration":1.0,"insulinNickname":""},""" else "") +
            """"timezone":"UTC",
            "sens":[{"time":"00:00","timeAsSeconds":0,"value":7.0},{"time":"02:00","timeAsSeconds":7200,"value":6.5}],
            "carbratio":[{"time":"00:00","timeAsSeconds":0,"value":6.4}],
            "basal":[{"time":"00:00","timeAsSeconds":0,"value":0.4},{"time":"01:00","timeAsSeconds":3600,"value":0.3}],
            "target_low":[{"time":"00:00","timeAsSeconds":0,"value":5.2}],
            "target_high":[{"time":"00:00","timeAsSeconds":0,"value":5.2}]}"""
    )

    @Test
    fun aKmpProfileWithoutDiaTakesTheInsulinDurationFromICfg() {
        val profile = pureProfileFromJson(kmpProfile(withICfg = true), dateUtil)

        assertThat(profile).isNotNull()
        assertThat(profile!!.dia).isWithin(0.001).of(9.0)
    }

    @Test
    fun aProfileWithNeitherDiaNorICfgIsStillRefused() {
        assertThat(pureProfileFromJson(kmpProfile(withICfg = false), dateUtil)).isNull()
    }

    @Test
    fun diaWinsWhenBothAreThere() {
        val json = kmpProfile(withICfg = true).put("dia", 5.0)

        assertThat(pureProfileFromJson(json, dateUtil)!!.dia).isWithin(0.001).of(5.0)
    }
}
