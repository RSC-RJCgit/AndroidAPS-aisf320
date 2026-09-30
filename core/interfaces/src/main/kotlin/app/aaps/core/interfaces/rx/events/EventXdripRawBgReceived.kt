package app.aaps.core.interfaces.rx.events

/**
 * A genuine (non-zero, > 10 mg/dL) raw BG value arrived on an xDrip/GDH broadcast. Added 2026-09-30,
 * per explicit request, to carry that raw value out to a third ("tertiary") Nightscout site,
 * independent of AAPS's own smoothed value and independent of the primary/secondary NS sync paths --
 * see NSClientV3Plugin's tertiaryRawUploadClient()/its subscriber for the actual upload.
 */
class EventXdripRawBgReceived(
    val raw: Double,
    val timestamp: Long,
    val device: String
) : Event()
