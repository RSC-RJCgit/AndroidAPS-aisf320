package app.aaps.core.data.model

/**
 * Sealed class representing individual actions that can be part of a Scene.
 * Each action maps to a specific system operation (TT, profile switch, etc.).
 */
sealed class SceneAction {

    /**
     * Set a temporary target.
     * @param reason TT reason category
     * @param targetMgdl Target value in mg/dL (converted at display time)
     */
    data class TempTarget(
        val reason: TT.Reason,
        val targetMgdl: Double
    ) : SceneAction()

    /**
     * Switch to a named profile with optional percentage and time shift.
     * @param profileName Name of the profile to switch to
     * @param percentage Basal percentage (100 = no change)
     * @param timeShiftHours Time shift in hours (0 = no shift)
     */
    data class ProfileSwitch(
        val profileName: String,
        val percentage: Int = 100,
        val timeShiftHours: Int = 0
    ) : SceneAction()

    /**
     * Toggle SMB on or off.
     * @param enabled Whether SMB should be enabled
     */
    data class SmbToggle(
        val enabled: Boolean
    ) : SceneAction()

    /**
     * Change the loop running mode.
     * @param mode Target running mode
     */
    data class LoopModeChange(
        val mode: RM.Mode
    ) : SceneAction()

    /**
     * Create a CarePortal therapy event entry.
     * @param type Therapy event type (Exercise, Sickness, etc.)
     * @param note Optional note text
     */
    data class CarePortalEvent(
        val type: TE.Type,
        val note: String = ""
    ) : SceneAction()

    /**
     * AutoISF fork: change AutoISF settings while the scene runs. Each value that is null stays as it is.
     * At the end of the scene the previous values come back, like [SmbToggle].
     * @param enabled AutoISF weights on or off ("Enable AutoISF")
     * @param acceWeight BG acceleration ISF weight, 0 to 1
     * @param iobThPercent iobTH in percent of max IOB, 10 to 100
     */
    data class AutoIsfSettings(
        val enabled: Boolean? = null,
        val acceWeight: Double? = null,
        val iobThPercent: Int? = null
    ) : SceneAction() {

        /** True when the action changes at least one setting. */
        val changesSomething: Boolean get() = enabled != null || acceWeight != null || iobThPercent != null

        companion object {

            const val ACCE_WEIGHT_MIN = 0.0
            const val ACCE_WEIGHT_MAX = 1.0
            const val IOB_TH_MIN = 10
            const val IOB_TH_MAX = 100
        }
    }
}
