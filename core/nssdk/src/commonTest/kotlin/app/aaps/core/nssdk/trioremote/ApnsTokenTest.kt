package app.aaps.core.nssdk.trioremote

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A key made here stands in for the .p8 file: it is the same kind of key, a P-256 key in PKCS #8. */
class ApnsTokenTest {

    private val ecdsa = CryptographyProvider.Default.get(ECDSA)
    private val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
    private val pem = pair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.PEM).decodeToString()

    private fun decodePart(part: String): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(part).decodeToString()

    @Test
    fun `the token names the key and the team`() {
        val parts = ApnsToken.make(pem, "ABCDE12345", "TEAM123456", 1_800_000_000).split(".")

        assertEquals(3, parts.size)
        assertEquals("""{"alg":"ES256","kid":"ABCDE12345"}""", decodePart(parts[0]))
        assertEquals("""{"iss":"TEAM123456","iat":1800000000}""", decodePart(parts[1]))
    }

    /** Apple checks the signature with the public half of the key. A DER signature would fail here. */
    @Test
    fun `the signature is r and s side by side and checks out`() {
        val token = ApnsToken.make(pem, "ABCDE12345", "TEAM123456", 1_800_000_000)
        val signed = token.substringBeforeLast(".")
        val signature = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(token.substringAfterLast("."))

        assertEquals(64, signature.size)
        assertTrue(pair.publicKey.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW).tryVerifySignatureBlocking(signed.encodeToByteArray(), signature))
    }

    /** A one-line text field drops the line breaks of a pasted key */
    @Test
    fun `a key pasted on one line still works`() {
        val oneLine = pem.replace("\n", "")

        assertTrue(ApnsToken.isValidKey(oneLine))
        assertTrue(ApnsToken.isValidKey(oneLine.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")))
    }

    @Test
    fun `text that is not a key is refused`() {
        assertFalse(ApnsToken.isValidKey(""))
        assertFalse(ApnsToken.isValidKey("not a key"))
        assertFalse(ApnsToken.isValidKey("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
    }

    @Test
    fun `an id is ten capital letters or digits`() {
        assertTrue(ApnsToken.isValidId("ABCDE12345"))
        assertFalse(ApnsToken.isValidId("abcde12345"))
        assertFalse(ApnsToken.isValidId("ABCDE1234"))
        assertFalse(ApnsToken.isValidId(""))
    }
}
