package app.aaps.core.utils

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LiveStepsMirrorTest {
    private val preferences: Preferences = mock()
    private var saved = "[]"
    private val now = 1_789_666_000_000L
    private val live = "openaps://samsung SM-A366B"
    private val virtual = "openaps://samsung SM-F731B"
    private lateinit var mirror: LiveStepsMirror

    @BeforeEach fun setup() {
        whenever(preferences.get(StringNonKey.LiveStepsHistory)).thenAnswer { saved }
        doAnswer { saved = it.getArgument(1); null }
            .whenever(preferences).put(eq(StringNonKey.LiveStepsHistory), any<String>())
        mirror = LiveStepsMirror(preferences)
    }

    @Test fun `parses live reason while preserving zero and missing buckets`() {
        val sample = mirror.receive(live, virtual, now, "Steps5M: 0 ;Steps15M: 0 ;Steps30M: 18 ;Steps60M: 18 ;Steps180M: 347 ;", now)!!
        assertThat(sample.steps(5)).isEqualTo(0)
        assertThat(sample.steps(60)).isEqualTo(18)
        assertThat(sample.steps(180)).isEqualTo(347)
        assertThat(sample.steps(10)).isNull()
        assertThat(LiveStepsMirror.parse("Steps10M: 12 ;")[10]).isEqualTo(12)
        assertThat(LiveStepsMirror.parse("steps60min is 305 ;; STEPS180M=347")).containsExactly(60, 305, 180, 347)
    }

    @Test fun `rejects self echo even when it contains tokens`() {
        assertThat(mirror.receive(virtual, virtual, now, "Steps60M: 999 ;", now)).isNull()
        assertThat(mirror.at(now)).isNull()
    }

    @Test fun `missing token does not overwrite remote steps with zero`() {
        mirror.receive(live, virtual, now, "Steps60M: 18 ;", now)
        assertThat(mirror.receive(live, virtual, now + 1000, "No step data", now + 1000)).isNull()
        assertThat(mirror.at(now + 1000)?.steps(60)).isEqualTo(18)
    }

    @Test fun `rejects future stale malformed and anonymous input`() {
        assertThat(mirror.receive(live, virtual, now + 1, "Steps60M: 9", now)).isNull()
        assertThat(mirror.receive(live, virtual, now - LiveStepsMirror.MAX_AGE_MS - 1, "Steps60M: 9", now)).isNull()
        assertThat(mirror.receive(null, virtual, now, "Steps60M: 9", now)).isNull()
        assertThat(LiveStepsMirror.parse("Steps60M: -1 ;Steps30M: 999999999999999 ;Steps5M: null")).isEmpty()
    }

    @Test fun `out of order messages cannot replace newer values or lend future values to history`() {
        mirror.receive(live, virtual, now, "Steps60M: 0 ;", now)
        mirror.receive(live, virtual, now - 60_000, "Steps60M: 18 ;", now)
        assertThat(mirror.at(now)?.steps(60)).isEqualTo(0)
        assertThat(mirror.at(now - 30_000)?.steps(60)).isEqualTo(18)
        assertThat(mirror.at(now - 60_001)).isNull()
        assertThat(mirror.at(now + LiveStepsMirror.MAX_AGE_MS + 1)).isNull()
    }

    @Test fun `all buckets come from one sample and survive a process restart`() {
        mirror.receive(live, virtual, now - 60_000, "Steps60M: 18 ;Steps180M: 347 ;", now)
        mirror.receive(live, virtual, now, "Steps60M: 0 ;", now)
        val reloaded = LiveStepsMirror(preferences)
        assertThat(reloaded.at(now)?.steps(60)).isEqualTo(0)
        assertThat(reloaded.at(now)?.steps(180)).isNull()
        assertThat(reloaded.at(now - 60_000)?.steps(180)).isEqualTo(347)
    }

    @Test fun `duplicate messages do not grow persisted history`() {
        mirror.receive(live, virtual, now, "Steps60M: 18 ;", now)
        val before = saved
        assertThat(mirror.receive(live, virtual, now, "Steps60M: 18 ;", now)).isNull()
        assertThat(saved).isEqualTo(before)
    }

    @Test fun `cumulative counts require every contributing bin`() {
        val partial = LiveStepsMirror.Sample(now, live, mapOf(5 to 13, 15 to 7, 30 to 40, 60 to 64, 180 to 90))
        assertThat(partial.hasDosingBuckets()).isTrue()
        assertThat(partial.cumulativeBuckets()[10]).isNull()
        assertThat(partial.cumulativeBuckets()[15]).isNull()
        val complete = partial.copy(buckets = partial.buckets + (10 to 20))
        assertThat(complete.cumulativeBuckets()[10]).isEqualTo(33)
        assertThat(complete.cumulativeBuckets()[15]).isEqualTo(40)
        assertThat(complete.cumulativeBuckets()[60]).isEqualTo(64)
    }

    @Test fun `historical missing steps cannot prove inactivity`() {
        assertThat(mirror.noHighStepsDuring(listOf(now), 1000)).isFalse()
        mirror.receive(live, virtual, now, "Steps60M: 0 ;", now)
        assertThat(mirror.noHighStepsDuring(listOf(now, now + 60_000), 1000)).isTrue()
        assertThat(mirror.noHighStepsDuring(listOf(now - 1, now), 1000)).isFalse()
        assertThat(mirror.noHighStepsDuring(listOf(now, now + LiveStepsMirror.MAX_AGE_MS + 1), 1000)).isFalse()
        mirror.receive(live, virtual, now + 60_000, "Steps60M: 1001 ;", now + 60_000)
        assertThat(mirror.noHighStepsDuring(listOf(now, now + 60_000), 1000)).isFalse()
    }

    @Test fun `corrupt cache recovers when a valid NS sample arrives`() {
        saved = "broken JSON"
        assertThat(mirror.at(now)).isNull()
        mirror.receive(live, virtual, now, "Steps60M: 18 ;", now)
        assertThat(LiveStepsMirror(preferences).at(now)?.steps(60)).isEqualTo(18)
    }
}
