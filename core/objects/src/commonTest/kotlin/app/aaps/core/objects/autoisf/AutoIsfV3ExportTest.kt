package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import kotlin.test.Test
import kotlin.test.assertEquals

/** The exported files in the 3426 app's columns, names and order (2026-11-20). */
class AutoIsfV3ExportTest {

    private val minute = 60_000L

    private fun row(time: Long, glucose: Double, iob: Double = 0.0, smb: Double = 0.0) = AIV(
        timestamp = time, acceIsf = 1.0, bgIsf = 1.0, ppIsf = 1.0, duraIsf = 1.0, finalIsf = 1.0,
        glucose = glucose, delta = -0.3 * 18.0182, shortAvgDelta = -0.3 * 18.0182, longAvgDelta = 0.0, bgAcceleration = 0.0,
        iob = iob, smbDelivered = smb
    )

    @Test fun theHeaderIsThe3426AppsHeader() {
        assertEquals(
            "Time,BGL,Target,Final,acce,bg,pp,dura,UAMci,SMB,FastRise,SmbRatio,MildBst,SMBi5,iobTH,acWt,ppWt,Lslope,acceBG,deltaAcce," +
                "deltaAcceUkf,deltaAcceU3,Delta,SDelta,LDelta,rawBGL,rawD1,rawD5,rawD15,ukfRawBGL,RawUKF5,RawUKF15,ukf3RawBGL,RawUKF3_5," +
                "RawUKF3_15,ukf2RawBGL,RawUKF2_5,RawUKF2_15,Int5,Req,TBR,IOB,IOBd5,Basal,COB,COBt,carbAbs,HP,HP2,HP3,targetBgOffset," +
                "offsetSoZeroSMB,LowBG,S5,S15,S30,S60,S180,MJ,Notes,ukf1Acce,ukf1AcceISF",
            AUTO_ISF_V3_EXPORT_HEADER.joinToString(",")
        )
    }

    @Test fun numbersAlwaysHaveAPoint() {
        assertEquals("6.3", fixedDecimals(6.3, 1))
        assertEquals("-0.30", fixedDecimals(-0.3, 2))
        assertEquals("0.00", fixedDecimals(-0.001, 2))
        assertEquals("12", fixedDecimals(11.6, 0))
    }

    @Test fun aRowHasEveryColumnInMmolAndFillsWhatTheSourcesGive() {
        val now = 100 * minute
        val rows = listOf(row(now, 6.3 * 18.0182, iob = 1.28, smb = 0.3), row(now - 5 * minute, 6.6 * 18.0182, iob = 1.4))
        val raw = listOf(now to 68.0, now - 5 * minute to 109.0)
        val loop = listOf(LoopResultText(now, "MildBst: 0.35 ; LowBGrecent: N ; targetBgOffset: 0.5 ;", 0.12, targetBG = 5.4 * 18.0182, rate = 0.0))
        val exported = rows.autoIsfV3ExportRows(timeText = { "t" }, loopResults = loop, rawReadings = raw, profileBasalAt = { 0.55 })
        val cells = AUTO_ISF_V3_EXPORT_HEADER.zip(exported.first()).toMap()
        assertEquals(AUTO_ISF_V3_EXPORT_HEADER.size, exported.first().size)
        assertEquals("6.3", cells["BGL"])
        assertEquals("5.4", cells["Target"])
        assertEquals("0.30", cells["SMB"])
        assertEquals("0.35", cells["MildBst"])
        assertEquals("-0.30", cells["Delta"])
        assertEquals("3.8", cells["rawBGL"])
        assertEquals("-2.28", cells["rawD5"])
        assertEquals("0.12", cells["Req"])
        assertEquals("0.00", cells["TBR"])
        assertEquals("1.28", cells["IOB"])
        assertEquals("-0.12", cells["IOBd5"])
        assertEquals("0.55", cells["Basal"])
        assertEquals("0.0", cells["COB"])
        // No carb absorption worked out (as on a client): COBt and carbAbs stay "--"
        assertEquals("--", cells["COBt"])
        assertEquals("--", cells["carbAbs"])
        // HP: (6.3 - 1.28) + 0.25 x -0.3 + 0.25 x -2.28 = 4.37
        assertEquals("4.4", cells["HP"])
        assertEquals("--", cells["HP3"])
        assertEquals("N", cells["LowBG"])
        assertEquals("0.5", cells["targetBgOffset"])
    }
}
