package app.aaps.database.persistence.converters

import app.aaps.core.data.model.AIV
import app.aaps.database.entities.AutoIsfValues

fun AutoIsfValues.fromDb(): AIV =
    AIV(
        timestamp = timestamp,
        acceIsf = acceIsf,
        bgIsf = bgIsf,
        ppIsf = ppIsf,
        duraIsf = duraIsf,
        finalIsf = finalIsf,
        glucose = glucose,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        bgAcceleration = bgAcceleration,
        iob = iob,
        smbDelivered = smbDelivered,
        ukfRawBgl = ukfRawBgl,
        iobThEffective = iobThEffective,
        targetMgdl = targetMgdl,
        uamCarbImpact = uamCarbImpact,
        smbDeliveryRatio = smbDeliveryRatio,
        acceIsfWeight = acceIsfWeight,
        ppIsfWeight = ppIsfWeight,
        fslCalSlope = fslCalSlope,
        cob = cob,
        basal = basal,
        note = note,
    )

fun AIV.toDb(): AutoIsfValues =
    AutoIsfValues(
        timestamp = timestamp,
        acceIsf = acceIsf,
        bgIsf = bgIsf,
        ppIsf = ppIsf,
        duraIsf = duraIsf,
        finalIsf = finalIsf,
        glucose = glucose,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        bgAcceleration = bgAcceleration,
        iob = iob,
        smbDelivered = smbDelivered,
        ukfRawBgl = ukfRawBgl,
        iobThEffective = iobThEffective,
        targetMgdl = targetMgdl,
        uamCarbImpact = uamCarbImpact,
        smbDeliveryRatio = smbDeliveryRatio,
        acceIsfWeight = acceIsfWeight,
        ppIsfWeight = ppIsfWeight,
        fslCalSlope = fslCalSlope,
        cob = cob,
        basal = basal,
        note = note,
    )
