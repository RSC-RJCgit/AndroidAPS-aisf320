package app.aaps.database.persistence.converters

import app.aaps.core.data.model.AIV
import app.aaps.database.entities.AutoIsfValues

fun AutoIsfValues.fromDb(): AIV =
    AIV(
        timestamp = timestamp,
        acceIsf = acceIsf,
        bgIsf = bgIsf,
        ppIsf = ppIsf,
        driftIsf = driftIsf,
        duraIsf = duraIsf,
        finalIsf = finalIsf,
        iobThEffective = iobThEffective,
    )

fun AIV.toDb(): AutoIsfValues =
    AutoIsfValues(
        timestamp = timestamp,
        acceIsf = acceIsf,
        bgIsf = bgIsf,
        ppIsf = ppIsf,
        driftIsf = driftIsf,
        duraIsf = duraIsf,
        finalIsf = finalIsf,
        iobThEffective = iobThEffective,
    )
