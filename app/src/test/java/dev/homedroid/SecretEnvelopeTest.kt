package dev.homedroid

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class SecretEnvelopeTest {
    /** The same AES-GCM as the keystore, with an in-memory key. */
    private class JceSealer : SecretEnvelope.Sealer {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            return iv to Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(plain)
        }
        override fun decrypt(iv: ByteArray, sealed: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(sealed)
    }

    @Test fun roundTripAndFormat() {
        val s = JceSealer()
        val sealed = SecretEnvelope.seal(s, "tunnel-tökén")
        assertTrue(sealed.startsWith("hd1:"))
        val raw = Base64.getDecoder().decode(sealed.removePrefix("hd1:"))
        assertEquals(12 + "tunnel-tökén".toByteArray().size + 16, raw.size)
        assertEquals("tunnel-tökén", SecretEnvelope.open(s, sealed))
        assertNotEquals(sealed, SecretEnvelope.seal(s, "tunnel-tökén"))
    }

    @Test fun rejectsTamperingOtherKeysAndPlaintext() {
        val s = JceSealer()
        val sealed = SecretEnvelope.seal(s, "secret")
        val raw = Base64.getDecoder().decode(sealed.removePrefix("hd1:")).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(SecretEnvelope.open(s, "hd1:" + Base64.getEncoder().encodeToString(raw)))
        assertNull(SecretEnvelope.open(JceSealer(), sealed))
        assertNull(SecretEnvelope.open(s, "plain-password"))
        assertNull(SecretEnvelope.open(s, "hd1:%%%"))
        assertNull(SecretEnvelope.open(s, "hd1:AAAA"))
    }

    @Test fun fakeCipherShowsTheLayout() {
        val xor = object : SecretEnvelope.Sealer {
            override fun encrypt(plain: ByteArray) = ByteArray(12) { it.toByte() } to plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            override fun decrypt(iv: ByteArray, sealed: ByteArray): ByteArray {
                assertArrayEquals(ByteArray(12) { it.toByte() }, iv)
                return sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            }
        }
        val sealed = SecretEnvelope.seal(xor, "ab")
        assertEquals("hd1:" + Base64.getEncoder().encodeToString(ByteArray(12) { it.toByte() } + byteArrayOf(0x3b, 0x38)), sealed)
        assertEquals("ab", SecretEnvelope.open(xor, sealed))
    }
}
