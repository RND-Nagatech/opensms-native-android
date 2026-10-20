package org.daslearning.opensms.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.daslearning.opensms.next.platform.AndroidFieldCipher
import org.daslearning.opensms.next.platform.AndroidTokenVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {
    @Test
    fun aesGcmFieldsRoundTripWithFreshCiphertextAndHmacsAreDomainSeparated() {
        val cipher = AndroidFieldCipher()
        val value = "+628123456789 SMS body"
        val encrypted1 = cipher.encrypt(value)
        val encrypted2 = cipher.encrypt(value)
        assertNotEquals(value, encrypted1)
        assertNotEquals(encrypted1, encrypted2)
        assertEquals(value, cipher.decrypt(encrypted1))

        val verifier = AndroidTokenVerifier()
        val token = verifier.createToken()
        assertTrue(token.length >= 40)
        assertEquals(verifier.verifier(token), verifier.verifier(token))
        assertNotEquals(verifier.verifier(token), verifier.fingerprintPayload(token))
    }
}
