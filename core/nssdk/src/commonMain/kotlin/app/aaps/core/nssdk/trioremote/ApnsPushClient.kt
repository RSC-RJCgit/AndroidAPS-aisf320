package app.aaps.core.nssdk.trioremote

import app.aaps.core.nssdk.networking.nsHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends a push through Apple's server to one app on one iPhone (2026-10-29, per explicit request), for Trio's remote
 * control. The headers are LoopFollow's (`PushNotificationManager.swift`), apart from the expiry: Apple keeps the push for
 * 10 minutes when the phone cannot be reached, the same time Trio still accepts the command.
 *
 * Apple only talks HTTP/2. OkHttp (Android, desktop) and Darwin (iOS) both use it by themselves.
 *
 * @param http only for tests; the app uses the platform client
 */
class ApnsPushClient(
    private val http: HttpClient = defaultClient(),
    private val nowSeconds: () -> Long
) {

    /** What Apple said */
    sealed interface Result {
        data object Sent : Result

        /** [reason] is Apple's word for it, like "BadDeviceToken", or the error of a connection that failed */
        data class Refused(val status: Int?, val reason: String) : Result
    }

    private var token: String? = null
    private var tokenFor: String? = null
    private var tokenIssuedAt = 0L

    suspend fun send(
        deviceToken: String,
        bundleId: String,
        production: Boolean,
        keyId: String,
        teamId: String,
        apnsKey: String,
        collapseId: String,
        body: String
    ): Result = try {
        val host = if (production) PRODUCTION_HOST else DEVELOPMENT_HOST
        val response = http.post("https://$host/3/device/$deviceToken") {
            header("authorization", "bearer ${tokenFor(keyId, teamId, apnsKey)}")
            header("apns-priority", "10")
            header("apns-expiration", (nowSeconds() + EXPIRY_SECONDS).toString())
            header("apns-topic", bundleId)
            header("apns-push-type", "alert")
            header("apns-collapse-id", collapseId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status.isSuccess()) Result.Sent
        else Result.Refused(response.status.value, reasonOf(response.bodyAsText()) ?: response.status.description)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Refused(null, e.message ?: e::class.simpleName ?: "error")
    }

    fun close() = http.close()

    /** One token for as long as Apple allows, and a new one when the key changes */
    private fun tokenFor(keyId: String, teamId: String, apnsKey: String): String {
        val now = nowSeconds()
        val forKey = "$keyId|$teamId|${apnsKey.hashCode()}"
        val current = token
        if (current != null && tokenFor == forKey && now - tokenIssuedAt < ApnsToken.MAX_AGE_SECONDS) return current
        return ApnsToken.make(apnsKey, keyId, teamId, now).also {
            token = it
            tokenFor = forKey
            tokenIssuedAt = now
        }
    }

    private fun reasonOf(body: String): String? =
        runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("reason") as? JsonPrimitive }.getOrNull()?.contentOrNull

    companion object {

        const val PRODUCTION_HOST = "api.push.apple.com"
        const val DEVELOPMENT_HOST = "api.sandbox.push.apple.com"
        private const val EXPIRY_SECONDS = 10L * 60
        private const val TIMEOUT_MS = 20L * 1000

        private fun defaultClient(): HttpClient = nsHttpClient(logging = false, logger = {}) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = TIMEOUT_MS
                requestTimeoutMillis = TIMEOUT_MS
            }
        }
    }
}
