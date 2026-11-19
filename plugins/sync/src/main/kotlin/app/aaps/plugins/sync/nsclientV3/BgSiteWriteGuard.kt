package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.food.NSFood
import app.aaps.core.nssdk.localmodel.treatment.CreateUpdateResponse
import app.aaps.core.nssdk.localmodel.treatment.NSTreatment
import org.json.JSONObject

/**
 * The main-site client of a Virtual-pump master, with every write refused while [blocked] says it must not upload there
 * (2026-11-19, per explicit request, ported from v4): the site is the one BG is read from, a real looper uploads there, or it is
 * not checked yet (see [NSClientV3Plugin.mainSiteUploadBlock]).
 *
 * Reads pass through. The upload pass is already stopped earlier (DataSyncWorker), so the sync cursors stay put; this catches
 * any write that goes another way. A refused write throws, the same as a failed request, and every caller already handles that.
 */
internal class BgSiteWriteGuard(
    private val inner: NSAndroidClient,
    private val blocked: () -> Boolean
) : NSAndroidClient by inner {

    private fun refuseIfBlocked() {
        if (blocked()) throw IllegalStateException("Upload to the main Nightscout site stopped")
    }

    override suspend fun createSgv(nsSgvV3: NSSgvV3): CreateUpdateResponse = refuseIfBlocked().let { inner.createSgv(nsSgvV3) }
    override suspend fun updateSvg(nsSgvV3: NSSgvV3): CreateUpdateResponse = refuseIfBlocked().let { inner.updateSvg(nsSgvV3) }
    override suspend fun createDeviceStatus(nsDeviceStatus: NSDeviceStatus): CreateUpdateResponse = refuseIfBlocked().let { inner.createDeviceStatus(nsDeviceStatus) }
    override suspend fun createProfileStore(remoteProfileStore: JSONObject): CreateUpdateResponse = refuseIfBlocked().let { inner.createProfileStore(remoteProfileStore) }
    override suspend fun createTreatment(nsTreatment: NSTreatment): CreateUpdateResponse = refuseIfBlocked().let { inner.createTreatment(nsTreatment) }
    override suspend fun updateTreatment(nsTreatment: NSTreatment): CreateUpdateResponse = refuseIfBlocked().let { inner.updateTreatment(nsTreatment) }
    override suspend fun createFood(nsFood: NSFood): CreateUpdateResponse = refuseIfBlocked().let { inner.createFood(nsFood) }
    override suspend fun updateFood(nsFood: NSFood): CreateUpdateResponse = refuseIfBlocked().let { inner.updateFood(nsFood) }
}
