package app.aaps.plugins.sync.nsclientV3.stats

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other phone's daily totals from its second-site records (2026-11-01). Times are in a zone without daylight
 * saving: a day is 24 h from midnight, and the time of day is the time since midnight.
 */
class SecondaryTddCalculationTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val first = 100 * day
    private val secondOfDay: (Long) -> Int = { time -> ((time % day) / 1000).toInt() }

    /** 1 U/h all day, unless a test says otherwise */
    private val flat = RemoteBasalProfile(first - day, listOf(0 to 1.0))

    private fun close(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "expected $expected, was $actual")

    private fun tdds(
        boluses: List<RemoteBolus> = emptyList(),
        carbs: List<Pair<Long, Double>> = emptyList(),
        tempBasals: List<RemoteTempBasal> = emptyList(),
        profiles: List<RemoteBasalProfile> = listOf(flat),
        days: Int = 1
    ) = secondaryDailyTdds((0 until days).map { first + it * day }, first + days * day, boluses, carbs, tempBasals, profiles, secondOfDay)

    @Test
    fun `basal is the profile when no temp basal runs`() {
        val result = tdds(boluses = listOf(RemoteBolus(first + hour, 2.0)))

        close(24.0, result.single().basal)
        close(2.0, result.single().bolus)
        close(26.0, result.single().total)
    }

    @Test
    fun `a temp basal replaces the profile while it runs`() {
        // 0 U/h for 2 h: 22 h at 1 U/h
        val result = tdds(tempBasals = listOf(RemoteTempBasal(first + 3 * hour, 2 * hour, 0.0)))

        close(22.0, result.single().basal)
    }

    /** A rate given as a percentage is a share of the profile basal at that time */
    @Test
    fun `a percent temp basal is taken from the profile`() {
        val result = tdds(tempBasals = listOf(RemoteTempBasal(first, 4 * hour, 150.0, isAbsolute = false)))

        close(20.0 + 4 * 1.5, result.single().basal)
    }

    /** A newer temp basal cancels the one before it, as on the pump */
    @Test
    fun `a newer temp basal ends the older one`() {
        val result = tdds(
            tempBasals = listOf(
                RemoteTempBasal(first, 10 * hour, 3.0),
                RemoteTempBasal(first + hour, hour, 0.0)
            )
        )

        // 1 h at 3, 1 h at 0, then the profile again for 22 h
        close(3.0 + 0.0 + 22.0, result.single().basal)
    }

    @Test
    fun `a temp basal running over midnight counts on both days`() {
        val result = tdds(tempBasals = listOf(RemoteTempBasal(first + day - hour, 2 * hour, 0.0)), days = 2)

        assertEquals(2, result.size)
        close(23.0, result[0].basal)
        close(23.0, result[1].basal)
    }

    @Test
    fun `the profile in force at each minute sets the basal`() {
        val result = tdds(
            boluses = listOf(RemoteBolus(first, 1.0)),
            profiles = listOf(flat, RemoteBasalProfile(first + 12 * hour, listOf(0 to 2.0)))
        )

        close(12.0 + 24.0, result.single().basal)
    }

    @Test
    fun `basal blocks follow the time of day`() {
        val result = tdds(
            boluses = listOf(RemoteBolus(first, 1.0)),
            profiles = listOf(RemoteBasalProfile(first - day, listOf(0 to 0.5, 6 * 3600 to 1.0, 18 * 3600 to 0.5)))
        )

        close(6 * 0.5 + 12 * 1.0 + 6 * 0.5, result.single().basal)
    }

    /** Without a profile there is no way to know the basal, and a guess would look like a real total */
    @Test
    fun `a day before any profile is left out`() {
        val result = tdds(boluses = listOf(RemoteBolus(first + hour, 2.0)), profiles = listOf(RemoteBasalProfile(first + hour, listOf(0 to 1.0))))

        assertTrue(result.isEmpty())
    }

    /** A gap on the site would otherwise read as a day of profile basal and no boluses */
    @Test
    fun `a day with nothing recorded is left out`() {
        assertTrue(tdds().isEmpty())
    }

    @Test
    fun `basal insulin boluses count as basal and carbs are summed`() {
        val result = tdds(
            boluses = listOf(RemoteBolus(first + hour, 2.0), RemoteBolus(first + 2 * hour, 10.0, isBasalInsulin = true)),
            carbs = listOf(first + hour to 30.0, first + 5 * hour to 15.0)
        )

        close(34.0, result.single().basal)
        close(2.0, result.single().bolus)
        close(45.0, result.single().carbs)
    }

    @Test
    fun `boluses on another day are not counted`() {
        val result = tdds(boluses = listOf(RemoteBolus(first - hour, 5.0), RemoteBolus(first + hour, 1.0), RemoteBolus(first + day, 7.0)))

        close(1.0, result.single().bolus)
    }

    @Test
    fun `the basal of an uploaded profile is read`() {
        val json = """{"dia":5,"basal":[{"time":"00:00","timeAsSeconds":0,"value":0.8},{"time":"06:30","value":1.2}],"sens":[]}"""

        assertEquals(listOf(0 to 0.8, (6 * 3600 + 30 * 60) to 1.2), basalFromProfileJson(json))
    }

    @Test
    fun `a profile without basal or not JSON gives nothing`() {
        assertTrue(basalFromProfileJson("""{"dia":5}""").isEmpty())
        assertTrue(basalFromProfileJson("not json").isEmpty())
    }
}
