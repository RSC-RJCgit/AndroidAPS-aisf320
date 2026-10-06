package app.aaps.plugins.sync.nsShared

/**
 * True when a Nightscout treatment was uploaded by iAPS (its `enteredBy` field). Boluses from iAPS are another loop's insulin,
 * not this phone's, so every Nightscout bolus path ignores them -- SMBs and meal boluses alike (2026-10-06, per explicit request).
 * iAPS writes its SMBs with eventType "SMB" and no AAPS "type" field, which used to be stored as ordinary (meal) boluses and drawn
 * on the graph. Carbs and other treatments from iAPS are not affected.
 */
fun isIapsEntry(enteredBy: String?): Boolean = enteredBy?.trim()?.equals("iAPS", ignoreCase = true) == true
