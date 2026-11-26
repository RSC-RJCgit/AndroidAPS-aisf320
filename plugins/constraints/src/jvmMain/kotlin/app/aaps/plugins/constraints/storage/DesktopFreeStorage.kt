package app.aaps.plugins.constraints.storage

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/**
 * The free space where the desktop app keeps its data (the home folder, which holds `.aaps2`).
 *
 * Fork (2026-11-26 dev41 merge): the desktop Virtual master gets the loop-only plugins, and upstream's
 * StorageConstraintPlugin is one of them now, so the desktop needs this as Android and iOS have theirs.
 */
@ContributesBinding(AppScope::class)
@Inject
class DesktopFreeStorage : FreeStorage {

    override fun availableMb(): Long = File(System.getProperty("user.home") ?: ".").usableSpace / BYTES_IN_MB

    private companion object {

        const val BYTES_IN_MB = 1048576L
    }
}
