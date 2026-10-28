package app.aaps.core.nssdk.trioremote

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/**
 * The encryption of a Trio remote command (2026-10-29, per explicit request), the same as LoopFollow's `SecureMessenger`
 * and Trio's own: AES-256-GCM, with the SHA-256 of the shared secret as the key.
 *
 * The result is base64 of the 12 byte nonce, then the cipher text, then the 16 byte tag. Trio reads it in that order, so
 * the order must not change.
 */
@OptIn(DelicateCryptographyApi::class)
object TrioRemoteCrypto {

    private const val NONCE_BYTES = 12

    private val sha256 by lazy { CryptographyProvider.Default.get(SHA256).hasher() }
    private val aesKeyDecoder by lazy { CryptographyProvider.Default.get(AES.GCM).keyDecoder() }

    private fun key(sharedSecret: String) =
        aesKeyDecoder.decodeFromByteArrayBlocking(AES.Key.Format.RAW, sha256.hashBlocking(sharedSecret.encodeToByteArray()))

    fun encrypt(sharedSecret: String, plain: String): String {
        val nonce = CryptographyRandom.nextBytes(NONCE_BYTES)
        // The library gives the cipher text with the tag after it, which is the order Trio expects after the nonce
        val sealed = key(sharedSecret).cipher().encryptWithIvBlocking(nonce, plain.encodeToByteArray())
        return Base64.Default.encode(nonce + sealed)
    }

    /** What Trio does with the message. Only the tests need it here. Null when it does not open with [sharedSecret]. */
    fun decrypt(sharedSecret: String, encrypted: String): String? = try {
        val bytes = Base64.Default.decode(encrypted)
        key(sharedSecret).cipher()
            .decryptWithIvBlocking(bytes.copyOfRange(0, NONCE_BYTES), bytes.copyOfRange(NONCE_BYTES, bytes.size))
            .decodeToString()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
