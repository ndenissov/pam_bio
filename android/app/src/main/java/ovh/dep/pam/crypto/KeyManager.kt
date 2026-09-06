package ovh.dep.pam.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the device's Ed25519 identity keypair.
 *
 * The Ed25519 private key is generated via BouncyCastle (for broad API-level
 * compatibility) and stored encrypted with an AES key from Android KeyStore.
 */
class KeyManager(context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "pambio_key_enc"
        private const val PREFS_NAME = "pambio_keys"
        private const val PREF_ENC_PRIV = "enc_private_key"
        private const val PREF_ENC_IV = "enc_iv"
        private const val PREF_PUB = "public_key"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether an identity keypair has already been generated. */
    val hasKeyPair: Boolean get() = prefs.contains(PREF_PUB)

    /** Returns the public key (base64) or null if not generated. */
    val publicKeyBase64: String? get() = prefs.getString(PREF_PUB, null)

    /**
     * Generates a new Ed25519 keypair, encrypts the private key with Android
     * KeyStore, and persists both.
     *
     * @return base64-encoded public key
     */
    fun generateKeyPair(): String {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()

        val privParams = keyPair.private as Ed25519PrivateKeyParameters
        val pubParams = keyPair.public as Ed25519PublicKeyParameters

        val pubBase64 = Base64.encodeToString(pubParams.encoded, Base64.NO_WRAP)

        // Encrypt private key with KeyStore-backed AES key
        ensureKeystoreKey()
        val cipher = getEncryptCipher()
        val encryptedPriv = cipher.doFinal(privParams.encoded)
        val iv = cipher.iv

        prefs.edit()
            .putString(PREF_PUB, pubBase64)
            .putString(PREF_ENC_PRIV, Base64.encodeToString(encryptedPriv, Base64.NO_WRAP))
            .putString(PREF_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()

        return pubBase64
    }

    /**
     * Signs [data] with the device's Ed25519 private key.
     *
     * @return base64-encoded signature
     */
    fun sign(data: ByteArray): String {
        val privKey = loadPrivateKey()
        val signer = Ed25519Signer()
        signer.init(true, privKey)
        signer.update(data, 0, data.size)
        val signature = signer.generateSignature()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    // ── Internal ──────────────────────────────────────────────

    private fun loadPrivateKey(): Ed25519PrivateKeyParameters {
        val encStr = prefs.getString(PREF_ENC_PRIV, null)
            ?: error("No private key stored")
        val ivStr = prefs.getString(PREF_ENC_IV, null)
            ?: error("No IV stored")

        val encrypted = Base64.decode(encStr, Base64.NO_WRAP)
        val iv = Base64.decode(ivStr, Base64.NO_WRAP)

        val cipher = getDecryptCipher(iv)
        val privBytes = cipher.doFinal(encrypted)
        return Ed25519PrivateKeyParameters(privBytes, 0)
    }

    private fun ensureKeystoreKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEYSTORE_ALIAS)) return

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGen.generateKey()
    }

    private fun getEncryptCipher(): Cipher {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEYSTORE_ALIAS, null)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun getDecryptCipher(iv: ByteArray): Cipher {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEYSTORE_ALIAS, null)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }
}
