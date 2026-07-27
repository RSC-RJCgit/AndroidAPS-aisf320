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
        glucose = this.glucose,
        insulinReq = this.insulinReq,
        tbrRate = this.tbrRate,
        smbDelivered = this.smbDelivered,
        delta = this.delta,
        shortAvgDelta = this.shortAvgDelta,
        bgAcceleration = this.bgAcceleration,
        smbDeliveryRatio = this.smbDeliveryRatio,
        iob = this.iob,
        acceIsfWeight = this.acceIsfWeight,
        fslCalSlope = this.fslCalSlope,
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
        glucose = this.glucose,
        insulinReq = this.insulinReq,
        tbrRate = this.tbrRate,
        smbDelivered = this.smbDelivered,
        delta = this.delta,
        shortAvgDelta = this.shortAvgDelta,
        bgAcceleration = this.bgAcceleration,
        smbDeliveryRatio = this.smbDeliveryRatio,
        iob = this.iob,
        acceIsfWeight = this.acceIsfWeight,
        fslCalSlope = this.fslCalSlope,
        interfaceIDs_backing = this.ids.toDb()
    )
