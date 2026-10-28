package app.aaps.core.nssdk.trioremote

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/**
 * The token that lets this phone send a push through Apple (2026-10-29, per explicit request): a JWT signed with ES256,
 * made from the APNs key (the .p8 file from the Apple developer account that built Trio), its Key ID and the Team ID.
 *
 * Apple refuses a token that is renewed more often than every 20 minutes, and one older than an hour. `ApnsPushClient`
 * keeps one and makes a new one after [MAX_AGE_SECONDS].
 */
object ApnsToken {

    /** Less than Apple's hour, more than its 20 minutes */
    const val MAX_AGE_SECONDS = 50L * 60

    private val keyIdPattern = Regex("^[A-Z0-9]{10}$")
    private val ecdsa by lazy { CryptographyProvider.Default.get(ECDSA) }

    /** A Key ID or a Team ID: ten capital letters or digits, as Apple gives them */
    fun isValidId(id: String): Boolean = keyIdPattern.matches(id)

    /**
     * The key as bytes (PKCS #8), or null when [pem] is not a key.
     *
     * Every space and line break is ignored, so a key pasted into a one-line field, where the line breaks are lost, still
     * works. The header and footer may be there or not.
     */
    fun keyBytes(pem: String): ByteArray? {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        return try {
            Base64.Default.decode(body)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** True when [pem] is a P-256 key this code can sign with */
    fun isValidKey(pem: String): Boolean = try {
        keyBytes(pem)?.let { privateKey(it) } != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * A signed token, issued at [issuedAtSeconds].
     *
     * @throws Exception when [pem] is not a key
     */
    fun make(pem: String, keyId: String, teamId: String, issuedAtSeconds: Long): String {
        val bytes = keyBytes(pem) ?: throw IllegalArgumentException("The APNs key is not a key")
        val header = base64Url("""{"alg":"ES256","kid":"$keyId"}""".encodeToByteArray())
        val claims = base64Url("""{"iss":"$teamId","iat":$issuedAtSeconds}""".encodeToByteArray())
        val signed = "$header.$claims"
        // RAW is r and s side by side, which is what a JWT needs. DER would be refused by Apple.
        val signature = privateKey(bytes).signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignatureBlocking(signed.encodeToByteArray())
        return "$signed.${base64Url(signature)}"
    }

    private fun privateKey(der: ByteArray): ECDSA.PrivateKey =
        ecdsa.privateKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PrivateKey.Format.DER, der)

    internal fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
}
