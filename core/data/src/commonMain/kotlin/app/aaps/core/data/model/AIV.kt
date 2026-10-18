package app.aaps.core.data.model

/**
 * The AutoISF values of one loop (AutoISF 3.2.1). The ISF factors are 1.0 when that factor did not change the ISF.
 * [iobThEffective] is the IOB threshold in units in force for this loop, drawn as the IOB_TH line on the IOB graph.
 */
data class AIV(
    var timestamp: Long,
    var acceIsf: Double,
    var bgIsf: Double,
    var ppIsf: Double,
    /** Place holder, as in AutoISF 3.2.1. */
    var driftIsf: Double,
    var duraIsf: Double,
    var finalIsf: Double,
    var iobThEffective: Double,
)
