package dev.homedroid

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The stored form of an encrypted secret: "hd1:" + base64(IV + ciphertext). Kept free of
 * Android, with the cipher passed in, so it can be tested.
 */
object SecretEnvelope {
    const val PREFIX = "hd1:"
    const val IV_BYTES = 12

    /** AES-GCM, or a stand-in in tests. [encrypt] picks its own IV and returns it with the ciphertext. */
    interface Sealer {
        fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>
        fun decrypt(iv: ByteArray, sealed: ByteArray): ByteArray
    }

    fun seal(sealer: Sealer, value: String): String {
        val (iv, ct) = sealer.encrypt(value.toByteArray(Charsets.UTF_8))
        require(iv.size == IV_BYTES) { "unexpected IV length" }
        return PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    /** The plaintext, or null if [stored] isn't an envelope or doesn't decrypt. */
    fun open(sealer: Sealer, stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (bytes.size <= IV_BYTES) return null
            String(sealer.decrypt(bytes.copyOfRange(0, IV_BYTES), bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
}

/** AES-256-GCM with a key that never leaves the AndroidKeyStore; usable without the user present. */
class KeystoreSealer : SecretEnvelope.Sealer {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return c.iv to c.doFinal(plain)
    }

    override fun decrypt(iv: ByteArray, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(sealed)

    companion object { private const val ALIAS = "homedroid_secrets" }
}

/**
 * Secrets in [prefs], encrypted at rest under "<name>.enc". A plaintext value left by an older
 * version under "<name>" is encrypted on first read and then removed. If the keystore doesn't
 * work on this phone, values stay in plaintext (as before) rather than being lost.
 */
class SecretStore(private val prefs: SharedPreferences, private val sealer: SecretEnvelope.Sealer = shared) {

    fun get(name: String): String? = synchronized(LOCK) {
        val plain = prefs.getString(name, null)
        prefs.getString("$name.enc", null)?.let { stored ->
            SecretEnvelope.open(sealer, stored)?.let { value ->
                if (plain != null) prefs.edit().remove(name).apply()
                return value
            }
            warn("could not decrypt $name")
            return plain
        }
        if (plain != null) seal(plain)?.let { prefs.edit().putString("$name.enc", it).remove(name).commit() }
        plain
    }

    fun put(name: String, value: String) = synchronized(LOCK) {
        val sealed = seal(value)
        if (sealed != null) prefs.edit().putString("$name.enc", sealed).remove(name).apply()
        else prefs.edit().putString(name, value).remove("$name.enc").apply()
    }

    fun remove(name: String) = synchronized(LOCK) { prefs.edit().remove(name).remove("$name.enc").apply() }

    /** The envelope, checked by opening it again, or null to keep plaintext. */
    private fun seal(value: String): String? = try {
        SecretEnvelope.seal(sealer, value).takeIf { SecretEnvelope.open(sealer, it) == value }
            ?: run { warn("keystore round trip failed"); null }
    } catch (e: Exception) {
        warn("keystore unavailable: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        private val LOCK = Any()
        private val shared by lazy { KeystoreSealer() }
        @Volatile private var warned = false

        private fun warn(message: String) {
            if (warned) return
            warned = true
            Log.w("Homedroid", "Secrets stay unencrypted: $message")
            Jobs.line("Secrets: $message; they stay unencrypted on this phone")
        }
    }
}
