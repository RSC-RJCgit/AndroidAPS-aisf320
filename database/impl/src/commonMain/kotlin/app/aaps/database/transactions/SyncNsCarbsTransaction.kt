package app.aaps.database.transactions

import app.aaps.database.entities.Carbs

/**
 * Sync the carbs from NS
 */
class SyncNsCarbsTransaction(private val carbs: List<Carbs>, private val nsClientMode: Boolean) :
    Transaction<SyncNsCarbsTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        val result = TransactionResult()

        for (carb in carbs) {
            val current: Carbs? =
                carb.interfaceIDs.nightscoutId?.let {
                    database.carbsDao.getByNSId(it)
                }

            if (current != null) {
                val invalidated = current.isValid && !carb.isValid
                val shortened = current.duration != carb.duration && nsClientMode && carb.duration < current.duration
                val metadataChanged = current.mergeImportedMetadata(carb)
                if (invalidated) current.isValid = false
                // Keep the existing amount and duration rules; metadata does not change dosing.
                if (shortened) {
                    current.amount = carb.amount
                    current.duration = carb.duration
                }
                if (invalidated || shortened || metadataChanged) {
                    database.carbsDao.updateExistingEntry(current)
                    if (invalidated) result.invalidated.add(current)
                    if (shortened || metadataChanged) result.updated.add(current)
                }
                continue
            }

            // not known nsId
            // Check by pumpId + pumpType + pumpSerial (primary deduplication - prevents NS duplicate _id records)
            val existingByPumpId = if (carb.interfaceIDs.pumpId != null && carb.interfaceIDs.pumpType != null && carb.interfaceIDs.pumpSerial != null) {
                database.carbsDao.findByPumpIds(carb.interfaceIDs.pumpId!!, carb.interfaceIDs.pumpType!!, carb.interfaceIDs.pumpSerial!!)
            } else {
                null
            }

            if (existingByPumpId != null) {
                // Same pump carb exists, just update/add the new nsId
                if (existingByPumpId.interfaceIDs.nightscoutId == null) {
                    existingByPumpId.interfaceIDs.nightscoutId = carb.interfaceIDs.nightscoutId
                    existingByPumpId.isValid = carb.isValid
                    existingByPumpId.mergeImportedMetadata(carb)
                    database.carbsDao.updateExistingEntry(existingByPumpId)
                    result.updatedNsId.add(existingByPumpId)
                }
                // If existing already has a different nsId, this is a duplicate NS record - ignore it
                continue
            }

            // Fallback: check by timestamp (for manual carbs without pumpId)
            val existing = database.carbsDao.findByTimestamp(carb.timestamp)
            if (existing != null && existing.interfaceIDs.nightscoutId == null) {
                // the same record, update nsId only
                existing.interfaceIDs.nightscoutId = carb.interfaceIDs.nightscoutId
                existing.isValid = carb.isValid
                existing.mergeImportedMetadata(carb)
                database.carbsDao.updateExistingEntry(existing)
                result.updatedNsId.add(existing)
            } else {
                database.carbsDao.insertNewEntry(carb)
                result.inserted.add(carb)
            }
        }
        return result
    }

    class TransactionResult {

        val updated = mutableListOf<Carbs>()
        val updatedNsId = mutableListOf<Carbs>()
        val inserted = mutableListOf<Carbs>()
        val invalidated = mutableListOf<Carbs>()
    }
}

/** Keep imported metadata when another sender omits it on a later sync. */
internal fun Carbs.mergeImportedMetadata(incoming: Carbs): Boolean {
    val fat = incoming.fatGrams ?: fatGrams
    val protein = incoming.proteinGrams ?: proteinGrams
    val source = incoming.sourceId ?: sourceId
    val fpu = incoming.fpuId ?: fpuId
    val changed = fat != fatGrams || protein != proteinGrams || source != sourceId || fpu != fpuId
    fatGrams = fat
    proteinGrams = protein
    sourceId = source
    fpuId = fpu
    return changed
}
