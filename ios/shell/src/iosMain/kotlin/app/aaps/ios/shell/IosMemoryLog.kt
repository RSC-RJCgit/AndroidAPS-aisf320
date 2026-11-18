package app.aaps.ios.shell

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * Logs the app's memory every 10 minutes, under CORE (2026-11-18, per explicit request).
 *
 * The iPhone's jetsam reports of 11-17 Nov 2026 had AAPSClient2 as the largest app in 11 of 14, at 0.4 to 1 GB,
 * already 425 MB a minute after start, where the master used about 100 MB. A jetsam report is written only when iOS
 * runs short of memory, so this line is what shows the curve in between, and whether a change brought it down.
 *
 * - `footprint` is the figure jetsam itself compares with the limit (TASK_VM_INFO phys_footprint).
 * - `Kotlin heap` is what the Kotlin objects held after the last garbage collection. Most of the footprint there
 *   means Kotlin data (loop results, graph points); most of it elsewhere means native memory (drawing, SQLite).
 */
class IosMemoryLog(
    private val aapsLogger: AAPSLogger
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            delay(FIRST_DELAY_MS)
            while (true) {
                log()
                delay(INTERVAL_MS)
            }
        }
    }

    @OptIn(NativeRuntimeApi::class)
    private fun log() {
        val footprint = footprintBytes()
        val gc = GC.lastGCInfo
        val heap = gc?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes
        aapsLogger.debug(
            LTag.CORE,
            "Memory: footprint ${footprint?.let { "${it / MB} MB" } ?: "unknown"}, " +
                "Kotlin heap ${heap?.let { "${it / MB} MB" } ?: "unknown"} after GC ${gc?.epoch ?: "none"}"
        )
    }

    /** The phys_footprint of this app, or null when iOS does not give it. */
    @OptIn(ExperimentalForeignApi::class)
    private fun footprintBytes(): Long? = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).convert()
        val result = task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS) info.phys_footprint.toLong() else null
    }

    private companion object {

        const val FIRST_DELAY_MS = 60_000L
        const val INTERVAL_MS = 10 * 60_000L
        const val MB = 1024L * 1024L
    }
}
