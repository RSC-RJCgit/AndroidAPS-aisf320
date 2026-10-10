package app.aaps.ui.compose.quickLaunch

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the stored format of the quick launch toolbar.
 *
 * This is user configuration living in preferences, so the text these produce has to keep reading
 * back the same way. The tests were written against the `org.json` implementation and then kept
 * while it moved to kotlinx.serialization, so they say the conversion changed nothing that is
 * stored - which is the only way to make that swap safely.
 */
class QuickLaunchSerializerTest {

    @Test
    fun aDynamicActionKeepsItsIdThroughARoundTrip() {
        val actions = listOf(QuickLaunchAction.QuickWizardAction("guid-1"))

        val json = QuickLaunchSerializer.toJson(actions)

        // Asserted by content, not by exact text: org.json does not keep key order, so the two keys
        // can come out either way round. What matters is that both are there and it reads back.
        assertThat(json).contains("\"type\":\"quick_wizard\"")
        assertThat(json).contains("\"id\":\"guid-1\"")
        assertThat(QuickLaunchSerializer.fromJson(json).first()).isEqualTo(QuickLaunchAction.QuickWizardAction("guid-1"))
    }

    @Test
    fun aProfileActionAtItsDefaultsWritesNeitherPercentageNorDuration() {
        // Absent, not written as 100 and 0 - the reader defaults them, and the stored text stays short.
        val json = QuickLaunchSerializer.toJson(listOf(QuickLaunchAction.ProfileAction("Weekday", 100, 0)))

        assertThat(json).contains("\"type\":\"profile\"")
        assertThat(json).contains("\"id\":\"Weekday\"")
        assertThat(json).doesNotContain("pct")
        assertThat(json).doesNotContain("dur")
        assertThat(QuickLaunchSerializer.fromJson(json).first())
            .isEqualTo(QuickLaunchAction.ProfileAction("Weekday", 100, 0))
    }

    @Test
    fun aProfileActionWithAPercentageAndDurationKeepsBoth() {
        val original = QuickLaunchAction.ProfileAction("Weekend", 120, 90)

        val restored = QuickLaunchSerializer.fromJson(QuickLaunchSerializer.toJson(listOf(original))).first()

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun theConfigEntryIsAlwaysPresentAndAlwaysLast() {
        val json = QuickLaunchSerializer.toJson(
            listOf(QuickLaunchAction.QuickLaunchConfig, QuickLaunchAction.QuickWizardAction("g"))
        )

        val restored = QuickLaunchSerializer.fromJson(json)

        assertThat(restored.last()).isEqualTo(QuickLaunchAction.QuickLaunchConfig)
        assertThat(restored.count { it == QuickLaunchAction.QuickLaunchConfig }).isEqualTo(1)
    }

    @Test
    fun blankTextGivesTheDefaultToolbar() {
        assertThat(QuickLaunchSerializer.fromJson("")).isEqualTo(QuickLaunchAction.default)
        assertThat(QuickLaunchSerializer.fromJson("   ")).isEqualTo(QuickLaunchAction.default)
    }

    @Test
    fun damagedTextGivesTheDefaultToolbarRatherThanThrowing() {
        // What a half written preference looks like. Losing the toolbar is recoverable; crashing on
        // every start is not.
        assertThat(QuickLaunchSerializer.fromJson("[{\"type\":")).isEqualTo(QuickLaunchAction.default)
        assertThat(QuickLaunchSerializer.fromJson("not json at all")).isEqualTo(QuickLaunchAction.default)
    }

    @Test
    fun anEntryWithATypeButNoIdIsDropped() {
        // A dynamic action without its id cannot be resolved, so it is skipped rather than restored
        // as something half formed.
        val restored = QuickLaunchSerializer.fromJson("""[{"type":"quick_wizard"}]""")

        assertThat(restored).isEqualTo(listOf(QuickLaunchAction.QuickLaunchConfig))
    }

    @Test
    fun anEmptyArrayStillYieldsTheConfigEntry() {
        assertThat(QuickLaunchSerializer.fromJson("[]")).isEqualTo(listOf(QuickLaunchAction.QuickLaunchConfig))
    }
}
