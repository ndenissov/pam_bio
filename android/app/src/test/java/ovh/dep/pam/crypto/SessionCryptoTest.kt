/*
 * Copyright 2026 Nikita Denissov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ovh.dep.pam.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class SessionCryptoTest {

    @Test
    fun ecdhAndAesGcmRoundTripBetweenTwoPeers() {
        val alice = SessionCrypto().apply { generateEphemeralKeypair() }
        val bob = SessionCrypto().apply { generateEphemeralKeypair() }

        alice.deriveSessionKey(bob.getPublicKeyBase64())
        bob.deriveSessionKey(alice.getPublicKeyBase64())

        assertNotNull(alice.sessionKey)
        assertNotNull(alice.sessionId)
        assertEquals(32, alice.sessionKey!!.size)
        assertEquals(32, alice.sessionId!!.size)
        assertArrayEquals(alice.sessionKey, bob.sessionKey)
        assertArrayEquals(alice.sessionId, bob.sessionId)

        val message = """{"type":"auth_request","nonce":"abc-123"}""".toByteArray(Charsets.UTF_8)
        val ciphertext = alice.encrypt(message)
        assertTrue(ciphertext.size > message.size + 12)
        val decrypted = bob.decrypt(ciphertext)
        assertArrayEquals(message, decrypted)

        // Empty payload must still round-trip cleanly with 12-byte nonce + 16-byte tag
        val emptyCt = bob.encrypt(ByteArray(0))
        assertArrayEquals(ByteArray(0), alice.decrypt(emptyCt))
    }

    @Test
    fun decryptRejectsTamperingWrongKeyAndShortCiphertext() {
        val alice = SessionCrypto().apply { generateEphemeralKeypair() }
        val bob = SessionCrypto().apply { generateEphemeralKeypair() }
        val mallory = SessionCrypto().apply { generateEphemeralKeypair() }

        alice.deriveSessionKey(bob.getPublicKeyBase64())
        bob.deriveSessionKey(alice.getPublicKeyBase64())
        mallory.deriveSessionKey(alice.getPublicKeyBase64())

        val ct = alice.encrypt("secret-payload".toByteArray(Charsets.UTF_8))

        // Flip last byte (tag corruption)
        val tampered = ct.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xff).toByte()
        assertThrows(Exception::class.java) {
            bob.decrypt(tampered)
        }

        // Wrong session key
        assertThrows(Exception::class.java) {
            mallory.decrypt(ct)
        }

        // Truncated ciphertext shorter than nonce size (12)
        assertThrows(IllegalStateException::class.java) {
            bob.decrypt(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun verifyEd25519MatchesServerIdentifySignatureSemantics() {
        val gen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        val otherKp = gen.generateKeyPair()
        val otherPub = otherKp.public as Ed25519PublicKeyParameters

        val sessionId = ByteArray(32) { idx -> (idx * 7 + 3).toByte() }
        val payload = SessionCrypto.SERVER_IDENTIFY_CONTEXT.toByteArray(Charsets.UTF_8) + sessionId

        val signer = Ed25519Signer().apply {
            init(true, priv)
            update(payload, 0, payload.size)
        }
        val sig = signer.generateSignature()

        val pubB64 = Base64.getEncoder().encodeToString(pub.encoded)
        val otherPubB64 = Base64.getEncoder().encodeToString(otherPub.encoded)
        val sigB64 = Base64.getEncoder().encodeToString(sig)

        assertTrue(SessionCrypto.verifyEd25519(pubB64, payload, sigB64))
        assertFalse(SessionCrypto.verifyEd25519(otherPubB64, payload, sigB64))
        assertFalse(SessionCrypto.verifyEd25519(pubB64, sessionId, sigB64))
        assertFalse(SessionCrypto.verifyEd25519(pubB64, payload, Base64.getEncoder().encodeToString(byteArrayOf(1, 2))))
        assertFalse(SessionCrypto.verifyEd25519("!!!not-base64!!!", payload, sigB64))
    }
}

