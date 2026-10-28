package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteControl
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.trioremote.ApnsPushClient
import app.aaps.core.nssdk.trioremote.ApnsToken
import app.aaps.core.nssdk.trioremote.TrioRemoteMessage
import app.aaps.plugins.sync.nsclientV3.data.trioPushInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToLong

/**
 * Trio remote control as LoopFollow does it (2026-10-29, per explicit request). The four values come from the settings;
 * how to reach Trio comes from Trio's own profile on this app's Nightscout site, so nothing about the site is asked for.
 *
 * Nothing secret is written to the log: not the shared secret, not the key, not the device token.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class TrioRemoteControlImpl(
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) : TrioRemoteControl {

    private val apns by lazy { ApnsPushClient(nowSeconds = { dateUtil.now() / 1000 }) }

    override fun setup(): TrioRemoteSetup {
        val info = trioPushInfo(preferences.get(StringNonKey.NsClientTrioPushInfo), preferences.get(StringKey.NsClientUrl))
        val missing = buildList {
            if (preferences.get(StringKey.TrioRemoteUser).isBlank()) add(TrioRemoteSetup.Missing.USER)
            if (preferences.get(StringKey.TrioRemoteSharedSecret).isEmpty()) add(TrioRemoteSetup.Missing.SHARED_SECRET)
            if (!ApnsToken.isValidId(preferences.get(StringKey.TrioRemoteApnsKeyId).trim())) add(TrioRemoteSetup.Missing.KEY_ID)
            if (!ApnsToken.isValidKey(preferences.get(StringKey.TrioRemoteApnsKey))) add(TrioRemoteSetup.Missing.APNS_KEY)
            if (info == null) add(TrioRemoteSetup.Missing.TRIO_PROFILE)
            else if (!ApnsToken.isValidId(info.details.teamId)) add(TrioRemoteSetup.Missing.TEAM_ID)
        }
        return TrioRemoteSetup(
            enabled = preferences.get(BooleanKey.TrioRemoteEnabled),
            missing = missing,
            trio = info?.details,
            overridePresets = info?.presets.orEmpty()
        )
    }

    override suspend fun send(command: TrioRemoteCommand): TrioRemoteResult {
        val setup = setup()
        val trio = setup.trio
        if (!setup.ready || trio == null) return TrioRemoteResult.Failed("Not set up: ${setup.missing.joinToString()}")
        val message = messageFor(command, preferences.get(StringKey.TrioRemoteUser).trim(), dateUtil.now() / 1000.0)
        val result = apns.send(
            deviceToken = trio.deviceToken,
            bundleId = trio.bundleId,
            production = trio.production,
            keyId = preferences.get(StringKey.TrioRemoteApnsKeyId).trim(),
            teamId = trio.teamId,
            apnsKey = preferences.get(StringKey.TrioRemoteApnsKey),
            collapseId = message.commandType,
            body = message.toPush(preferences.get(StringKey.TrioRemoteSharedSecret), "Remote Command: ${titleOf(message.commandType)}")
        )
        return when (result) {
            ApnsPushClient.Result.Sent        -> {
                aapsLogger.debug(LTag.NSCLIENT, "Trio remote: ${message.commandType} sent")
                TrioRemoteResult.Sent
            }

            is ApnsPushClient.Result.Refused -> {
                aapsLogger.error(LTag.NSCLIENT, "Trio remote: ${message.commandType} refused (${result.status}) ${result.reason}")
                TrioRemoteResult.Failed(result.reason)
            }
        }
    }

    companion object {

        internal fun messageFor(command: TrioRemoteCommand, user: String, nowSeconds: Double): TrioRemoteMessage = when (command) {
            is TrioRemoteCommand.TempTarget    ->
                TrioRemoteMessage(TrioRemoteMessage.TEMP_TARGET, user, nowSeconds, target = command.targetMgdl, duration = command.durationMinutes)

            TrioRemoteCommand.CancelTempTarget -> TrioRemoteMessage(TrioRemoteMessage.CANCEL_TEMP_TARGET, user, nowSeconds)

            is TrioRemoteCommand.Meal          -> TrioRemoteMessage(
                TrioRemoteMessage.MEAL, user, nowSeconds,
                // LoopFollow leaves out a value of 0, and so does this
                carbs = command.carbs.takeIf { it > 0 },
                fat = command.fat.takeIf { it > 0 },
                protein = command.protein.takeIf { it > 0 },
                scheduledTimeSeconds = command.eatenAt?.let { it / 1000.0 }
            )

            // Two decimals, so Trio is not sent 1.2000000000000002
            is TrioRemoteCommand.Bolus         ->
                TrioRemoteMessage(TrioRemoteMessage.BOLUS, user, nowSeconds, bolusAmount = (command.units * 100).roundToLong() / 100.0)

            is TrioRemoteCommand.StartOverride -> TrioRemoteMessage(TrioRemoteMessage.START_OVERRIDE, user, nowSeconds, overrideName = command.name)
            TrioRemoteCommand.CancelOverride   -> TrioRemoteMessage(TrioRemoteMessage.CANCEL_OVERRIDE, user, nowSeconds)
        }

        /** LoopFollow's alert titles. Trio shows them, so they stay in English like LoopFollow's. */
        private fun titleOf(commandType: String): String = when (commandType) {
            TrioRemoteMessage.BOLUS              -> "Bolus"
            TrioRemoteMessage.TEMP_TARGET        -> "Temp Target"
            TrioRemoteMessage.CANCEL_TEMP_TARGET -> "Cancel Temp Target"
            TrioRemoteMessage.MEAL               -> "Meal"
            TrioRemoteMessage.START_OVERRIDE     -> "Start Override"
            else                                 -> "Cancel Override"
        }
    }
}
