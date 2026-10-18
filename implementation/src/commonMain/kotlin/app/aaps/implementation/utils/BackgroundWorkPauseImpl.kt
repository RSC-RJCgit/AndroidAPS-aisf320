package app.aaps.implementation.utils

import app.aaps.core.interfaces.configuration.BackgroundWorkPause
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the pause flag. Only the iOS keep-alive sets it, so it stays false on Android and desktop. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class BackgroundWorkPauseImpl : BackgroundWorkPause {

    private val _paused = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = _paused.asStateFlow()

    override fun setPaused(paused: Boolean) {
        _paused.value = paused
    }
}
