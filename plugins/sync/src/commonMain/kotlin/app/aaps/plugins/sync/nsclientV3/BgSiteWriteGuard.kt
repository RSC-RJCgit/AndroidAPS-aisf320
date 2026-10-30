package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.authorization.NSAuthRole
import app.aaps.core.nssdk.localmodel.authorization.NSAuthSubject
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.nssdk.localmodel.entry.NSMbgV3
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.food.NSFood
import app.aaps.core.nssdk.localmodel.treatment.CreateUpdateResponse
import app.aaps.core.nssdk.localmodel.treatment.NSTreatment
import kotlinx.serialization.json.JsonObject

/**
 * The main-site client of a Virtual-pump master, with every write refused while [blocked] says the main site is the
 * one BG is read from (2026-10-30, per explicit request; see `uploadSiteIsBgSite`).
 *
 * Reads pass through. Treatment, BG and device status uploads are already stopped earlier, in
 * `DataSyncSelectorV3.doUpload`, so their cursors stay put; this catches the rest - the client-control and running
 * configuration documents in the settings collection, tokens, and anything added later - at the one place they all go
 * through. A refused write throws, the same as a failed request, and every caller already handles that.
 */
internal class BgSiteWriteGuard(
    private val inner: NSAndroidClient,
    private val blocked: () -> Boolean
) : NSAndroidClient by inner {

    private fun refuseIfBlocked() {
        if (blocked()) throw IllegalStateException("Upload stopped: the main Nightscout site is the BG source")
    }

    override suspend fun createSgv(nsSgvV3: NSSgvV3): CreateUpdateResponse = refuseIfBlocked().let { inner.createSgv(nsSgvV3) }
    override suspend fun updateSvg(nsSgvV3: NSSgvV3): CreateUpdateResponse = refuseIfBlocked().let { inner.updateSvg(nsSgvV3) }
    override suspend fun createCalibration(nsMbgV3: NSMbgV3): CreateUpdateResponse = refuseIfBlocked().let { inner.createCalibration(nsMbgV3) }
    override suspend fun updateCalibration(nsMbgV3: NSMbgV3): CreateUpdateResponse = refuseIfBlocked().let { inner.updateCalibration(nsMbgV3) }
    override suspend fun createDeviceStatus(nsDeviceStatus: NSDeviceStatus): CreateUpdateResponse = refuseIfBlocked().let { inner.createDeviceStatus(nsDeviceStatus) }
    override suspend fun createProfileStore(remoteProfileStore: JsonObject): CreateUpdateResponse = refuseIfBlocked().let { inner.createProfileStore(remoteProfileStore) }
    override suspend fun createTreatment(nsTreatment: NSTreatment): CreateUpdateResponse = refuseIfBlocked().let { inner.createTreatment(nsTreatment) }
    override suspend fun updateTreatment(nsTreatment: NSTreatment): CreateUpdateResponse = refuseIfBlocked().let { inner.updateTreatment(nsTreatment) }
    override suspend fun createFood(nsFood: NSFood): CreateUpdateResponse = refuseIfBlocked().let { inner.createFood(nsFood) }
    override suspend fun updateFood(nsFood: NSFood): CreateUpdateResponse = refuseIfBlocked().let { inner.updateFood(nsFood) }
    override suspend fun createSettings(settings: JsonObject): CreateUpdateResponse = refuseIfBlocked().let { inner.createSettings(settings) }
    override suspend fun patchSettings(identifier: String, settings: JsonObject): CreateUpdateResponse = refuseIfBlocked().let { inner.patchSettings(identifier, settings) }
    override suspend fun updateSettings(identifier: String, settings: JsonObject): CreateUpdateResponse = refuseIfBlocked().let { inner.updateSettings(identifier, settings) }
    override suspend fun deleteSettings(identifier: String): CreateUpdateResponse = refuseIfBlocked().let { inner.deleteSettings(identifier) }
    override suspend fun deleteSettingsPermanent(identifier: String): CreateUpdateResponse = refuseIfBlocked().let { inner.deleteSettingsPermanent(identifier) }
    override suspend fun createAuthRole(role: NSAuthRole): NSAuthRole = refuseIfBlocked().let { inner.createAuthRole(role) }
    override suspend fun updateAuthRole(role: NSAuthRole) = refuseIfBlocked().let { inner.updateAuthRole(role) }
    override suspend fun createAuthSubject(subject: NSAuthSubject): NSAuthSubject = refuseIfBlocked().let { inner.createAuthSubject(subject) }
    override suspend fun deleteAuthSubject(id: String) = refuseIfBlocked().let { inner.deleteAuthSubject(id) }
}
