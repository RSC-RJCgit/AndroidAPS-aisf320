package app.aaps.database.persistence.converters

import app.aaps.core.data.model.CA
import app.aaps.database.entities.Carbs

fun Carbs.fromDb(): CA =
    CA(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        duration = this.duration,
        amount = this.amount,
        notes = this.notes,
        fatGrams = this.fatGrams,
        proteinGrams = this.proteinGrams,
        sourceId = this.sourceId,
        fpuId = this.fpuId,
        ids = this.interfaceIDs.fromDb()
    )

fun CA.toDb(): Carbs =
    Carbs(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        duration = this.duration,
        amount = this.amount,
        notes = this.notes,
        fatGrams = this.fatGrams,
        proteinGrams = this.proteinGrams,
        sourceId = this.sourceId,
        fpuId = this.fpuId,
        interfaceIDs_backing = this.ids.toDb()
    )
