package app.aaps.implementation.steps

import kotlin.math.roundToLong

/**
 * The step and movement counting of AutoISF 3.2.1 (`StepService` and `PhoneMovementDetector`), without the Android
 * sensor calls, so it can be tested and used on other platforms.
 *
 * Steps are kept per 5-minute block. A block number is the time divided by 5 minutes, rounded (as in 3.2.1).
 */
class PhoneStepBlocks {

    private var previousStepCount = -1
    private val stepsMap = LinkedHashMap<Long, Int>()
    private var lastMovedAt = 0L

    private fun block(nowMs: Long): Long = (nowMs / FIVE_MINUTES_MS.toDouble()).roundToLong()

    /** [totalSteps] is the step counter value since the phone started. */
    fun onStepCounter(totalSteps: Int, nowMs: Long) {
        val now = block(nowMs)
        if (previousStepCount >= 0) {
            var recentStepCount = totalSteps - previousStepCount
            stepsMap[now]?.let { recentStepCount += it }
            stepsMap[now] = recentStepCount
        }
        previousStepCount = totalSteps
        if (stepsMap.size > BLOCKS_TO_KEEP) {
            val removeBefore = now - BLOCKS_TO_KEEP
            stepsMap.entries.removeAll { it.key < removeBefore }
        }
    }

    /** [x], [y] and [z] are the accelerometer values in m/s2. */
    fun onAcceleration(x: Float, y: Float, z: Float, nowMs: Long) {
        val acceleration = (x * x + y * y + z * z) / (GRAVITY_EARTH * GRAVITY_EARTH)
        if (acceleration > MOVEMENT_THRESHOLD) lastMovedAt = nowMs
    }

    fun steps5Min(nowMs: Long): Int = stepsMap[block(nowMs) - 1] ?: 0
    fun steps10Min(nowMs: Long): Int = stepsMap[block(nowMs) - 2] ?: 0
    fun steps15Min(nowMs: Long): Int = stepsMap[block(nowMs) - 3] ?: 0
    fun steps30Min(nowMs: Long): Int = stepsInLast(6, nowMs)
    fun steps60Min(nowMs: Long): Int = stepsInLast(12, nowMs)

    /** As in 3.2.1, only [BLOCKS_TO_KEEP] blocks are kept, so this holds at most the last 100 minutes. */
    fun steps180Min(nowMs: Long): Int = stepsInLast(36, nowMs)

    fun phoneMoved(nowMs: Long): Boolean = lastMovedAt + MOVED_WINDOW_MS > nowMs

    private fun stepsInLast(blocks: Int, nowMs: Long): Int {
        val from = block(nowMs) - blocks
        return stepsMap.entries.filter { it.key > from }.sumOf { it.value }
    }

    companion object {

        private const val FIVE_MINUTES_MS = 300_000L
        private const val BLOCKS_TO_KEEP = 20
        private const val MOVEMENT_THRESHOLD = 1.05
        private const val MOVED_WINDOW_MS = 15 * 60_000L
        private const val GRAVITY_EARTH = 9.80665f
    }
}
