package ovh.dep.pam.crypto

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec as HmacKeySpec

/**
 * Handles ECDH (X25519) key exchange and AES-256-GCM session encryption.
 * Mirrors the Go daemon's crypto/session.go implementation.
 */
class SessionCrypto {

    companion object {
        private val HKDF_SALT = "pambio-session-v1".toByteArray()
        private val HKDF_INFO = "aes-256-gcm-key".toByteArray()
        private val SID_INFO = "pambio-session-id".toByteArray()
        private const val AES_KEY_SIZE = 32
        private const val GCM_NONCE_SIZE = 12
        private const val GCM_TAG_BITS = 128
    }

    private var privateKey: X25519PrivateKeyParameters? = null
    private var publicKey: X25519PublicKeyParameters? = null
    var sessionKey: ByteArray? = null
        private set
    var sessionId: ByteArray? = null
        private set

    /** Generates an ephemeral X25519 keypair for ECDH exchange. */
    fun generateEphemeralKeypair() {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        privateKey = kp.private as X25519PrivateKeyParameters
        publicKey = kp.public as X25519PublicKeyParameters
    }

    /** Returns our ephemeral public key as base64. */
    fun getPublicKeyBase64(): String {
        val pub = publicKey ?: error("Call generateEphemeralKeypair() first")
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    /**
     * Performs the ECDH key exchange with the remote party's public key,
     * then derives the AES-256-GCM session key and session ID.
     */
    fun deriveSessionKey(remotePublicKeyBase64: String) {
        val priv = privateKey ?: error("Call generateEphemeralKeypair() first")
        val remotePubBytes = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        val remotePub = X25519PublicKeyParameters(remotePubBytes, 0)

        // X25519 DH
        val agreement = X25519Agreement()
        agreement.init(priv)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(remotePub, sharedSecret, 0)

        // HKDF-SHA256 → AES key
        sessionKey = hkdfSha256(sharedSecret, HKDF_SALT, HKDF_INFO, AES_KEY_SIZE)

        // Session ID
        val md = MessageDigest.getInstance("SHA-256")
        md.update(SID_INFO)
        md.update(sharedSecret)
        sessionId = md.digest()
    }

    /** Encrypts [plaintext] with AES-256-GCM. Returns nonce || ciphertext || tag. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = sessionKey ?: error("Session not established")
        val nonce = ByteArray(GCM_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /** Decrypts AES-256-GCM ciphertext (nonce || ct || tag). */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val key = sessionKey ?: error("Session not established")
        if (ciphertext.size < GCM_NONCE_SIZE) error("Ciphertext too short")
        val nonce = ciphertext.copyOfRange(0, GCM_NONCE_SIZE)
        val ct = ciphertext.copyOfRange(GCM_NONCE_SIZE, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }

    // ── HKDF-SHA256 (RFC 5869) ─────────────────────────────

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
        val prk = hmacSha256(salt, ikm)
        // Expand
        val n = (length + 31) / 32
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(HmacKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
