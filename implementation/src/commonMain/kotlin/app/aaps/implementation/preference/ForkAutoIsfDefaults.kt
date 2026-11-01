package app.aaps.implementation.preference

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * This fork's AutoISF settings, built in as fresh-install defaults (2026-11-01, per explicit request), so a new client or
 * Virtual-pump master starts set up without a settings file. Taken from the Windows virtual master's own settings on
 * 1 Nov 2026 (its AutoISF_settings export). No addresses, tokens or other secrets, and nothing pump-specific.
 *
 * Applied by [FreshInstallDefaults] only: once, on a new install, and only where nothing is stored. A value changed later
 * is stored and kept, and a settings import still brings the file's values.
 */
internal sealed class ForkDefault {

    /** Owned by the master on a client: a client takes these from its master, so they are not written there */
    abstract val synced: Boolean

    abstract fun isStored(preferences: Preferences): Boolean
    abstract fun write(preferences: Preferences)

    class Bool(private val key: BooleanKey, private val value: Boolean) : ForkDefault() {

        override val synced get() = key.sync != null
        override fun isStored(preferences: Preferences) = preferences.getIfExists(key) != null
        override fun write(preferences: Preferences) = preferences.put(key, value)
    }

    class Dbl(private val key: DoubleKey, private val value: Double) : ForkDefault() {

        override val synced get() = key.sync != null
        override fun isStored(preferences: Preferences) = preferences.getIfExists(key) != null
        override fun write(preferences: Preferences) = preferences.put(key, value)
    }

    class Whole(private val key: IntKey, private val value: Int) : ForkDefault() {

        override val synced get() = key.sync != null
        override fun isStored(preferences: Preferences) = preferences.getIfExists(key) != null
        override fun write(preferences: Preferences) = preferences.put(key, value)
    }

    class Str(private val key: StringKey, private val value: String) : ForkDefault() {

        override val synced get() = key.sync != null
        override fun isStored(preferences: Preferences) = preferences.getIfExists(key) != null
        override fun write(preferences: Preferences) = preferences.put(key, value)
    }
}

internal val forkAutoIsfDefaults: List<ForkDefault> = listOf(
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfMax, 2.6),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfMaxLow, 1.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfMin, 0.3),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfBgAccelWeightHigh, 0.95),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfBgAccelWeightNormal, 0.7),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfBoostAutomationsEnabled, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfCodedAutomationsEnabled, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfCustomAutomationsEnabled, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfFastRiseEnabled, true),
    ForkDefault.Whole(IntKey.ApsAutoIsfIobThPercentNormal, 22),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfLoopEveryMinute, true),
    ForkDefault.Str(StringKey.ApsAutoIsfLow70ProfileName, "Profile70"),
    ForkDefault.Str(StringKey.ApsAutoIsfLow80ProfileName, "Profile80"),
    ForkDefault.Str(StringKey.ApsAutoIsfLow90ProfileName, "Profile90"),
    ForkDefault.Str(StringKey.ApsAutoIsfLowProfileName, "Profile90"),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfLowReboundGuardEnabled, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfMealBolusInSmbCap, true),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfMildBoostRatio, 0.35),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfMjKotlinButtonsEnabled, false),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfPpWeightHigh, 0.15),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfPpWeightNormal, 0.1),
    ForkDefault.Whole(IntKey.ApsAutoIsfProfilePercentNormal, 100),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfShowInsulinTotals, true),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfSmbDeliveryBaseline, 0.11),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfSmbOffsetOverride, 0.8),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled, true),
    ForkDefault.Str(StringKey.ApsAutoIsfStandard100ProfileName, "Profile100"),
    ForkDefault.Str(StringKey.ApsAutoIsfStandard105ProfileName, "Profile130"),
    ForkDefault.Str(StringKey.ApsAutoIsfStandard110ProfileName, "Profile150"),
    ForkDefault.Str(StringKey.ApsAutoIsfStandardProfileName, "Profile150"),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfStepCutFromOldReasons, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfStepsFromPhone, true),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid100ProfileName, "Steroid100"),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid110ProfileName, "Steroid110"),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid130ProfileName, "Steroid130"),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid150ProfileName, "Steroid150"),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid190ProfileName, "Steroid190"),
    ForkDefault.Str(StringKey.ApsAutoIsfSteroid250ProfileName, "Steroid250"),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfSteroidKotlinButtonEnabled, false),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfTddFactor, true),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTddFactorFallback, 1.0),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfTddSensitivity, true),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset0002, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset0204, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset0406, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset0609, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset0912, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset1218, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset1822, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfTodOffset2200, 0.0),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfUamBoostEnabled, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfUamBoostUnrestrictedEnabled, true),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfUkf1DeltaCompensationOffset, 0.0),
    ForkDefault.Dbl(DoubleKey.ApsAutoIsfUkf1DeltaCompensationSlope, 1.0),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfUseLiveMjStateOnVirtual, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual, true),
    ForkDefault.Bool(BooleanKey.ApsAutoIsfUseUkf1ForDosing, false),
    ForkDefault.Bool(BooleanKey.ApsUseAutoIsfWeights, true),
)
