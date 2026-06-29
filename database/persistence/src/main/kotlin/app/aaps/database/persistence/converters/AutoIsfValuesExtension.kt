package app.aaps.database.persistence.converters

import app.aaps.core.data.model.AIV
import app.aaps.database.entities.AutoIsfValues

fun AutoIsfValues.fromDb(): AIV =
    AIV(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        acceIsf = this.acceIsf,
        bgIsf = this.bgIsf,
        ppIsf = this.ppIsf,
        driftIsf = this.driftIsf,
        duraIsf = this.duraIsf,
        finalIsf = this.finalIsf,
        iobThEffective = this.iobThEffective,
        delta = this.delta,
        shortAvgDelta = this.shortAvgDelta,
        bgAcceleration = this.bgAcceleration,
        ids = this.interfaceIDs.fromDb()
    )

fun AIV.toDb(): AutoIsfValues =
    AutoIsfValues(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        acceIsf = this.acceIsf,
        bgIsf = this.bgIsf,
        ppIsf = this.ppIsf,
        driftIsf = this.driftIsf,
        duraIsf = this.duraIsf,
        finalIsf = this.finalIsf,
        iobThEffective = this.iobThEffective,
        delta = this.delta,
        shortAvgDelta = this.shortAvgDelta,
        bgAcceleration = this.bgAcceleration,
        interfaceIDs_backing = this.ids.toDb()
    )
