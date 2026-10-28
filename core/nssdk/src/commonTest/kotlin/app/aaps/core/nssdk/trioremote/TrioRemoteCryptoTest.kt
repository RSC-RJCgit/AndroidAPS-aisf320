package app.aaps.core.nssdk.trioremote

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Runs on every target, because the iPhone, the Android phone and the PC must all make what Trio can open. */
class TrioRemoteCryptoTest {

    @Test
    fun `a message opens again with the same secret`() {
        val sealed = TrioRemoteCrypto.encrypt("the-secret", """{"command_type":"bolus"}""")

        assertEquals("""{"command_type":"bolus"}""", TrioRemoteCrypto.decrypt("the-secret", sealed))
    }

    @Test
    fun `a message does not open with another secret`() {
        val sealed = TrioRemoteCrypto.encrypt("the-secret", "hello")

        assertNull(TrioRemoteCrypto.decrypt("another-secret", sealed))
    }

    /** Trio splits the bytes as 12 of nonce, the text, then 16 of tag. */
    @Test
    fun `the nonce comes first and the tag last`() {
        val bytes = Base64.Default.decode(TrioRemoteCrypto.encrypt("s", "12345"))

        assertEquals(12 + 5 + 16, bytes.size)
    }

    @Test
    fun `each message gets a new nonce`() {
        assertNotEquals(TrioRemoteCrypto.encrypt("s", "same"), TrioRemoteCrypto.encrypt("s", "same"))
    }
}
