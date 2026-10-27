package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Timestamp of last modification of every collection
 *
 **/
@Serializable
data class LastModified(
    @SerialName("collections") val collections: Collections
) {

    @Serializable
    data class Collections(

        // Nightscout can propagate a fractional entry date into these collection timestamps.
        @SerialName("devicestatus") @Serializable(with = EntryDateSerializer::class) var devicestatus: Long = 0,
        @SerialName("entries") @Serializable(with = EntryDateSerializer::class) var entries: Long = 0,
        @SerialName("profile") @Serializable(with = EntryDateSerializer::class) var profile: Long = 0,
        @SerialName("treatments") @Serializable(with = EntryDateSerializer::class) var treatments: Long = 0,
        @SerialName("foods") @Serializable(with = EntryDateSerializer::class) var foods: Long = 0,
        @SerialName("settings") @Serializable(with = EntryDateSerializer::class) var settings: Long = 0
    )

    fun set(colName: String, value: Long) {
        when (colName) {
            "devicestatus" -> collections.devicestatus = value
            "entries"      -> collections.entries = value
            "profile"      -> collections.profile = value
            "treatments"   -> collections.treatments = value
            "foods"        -> collections.foods = value
            "settings"     -> collections.settings = value
        }
    }
}
