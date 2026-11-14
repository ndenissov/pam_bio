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


package ovh.dep.pam.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ovh.dep.pam.crypto.IdentitySigner
import ovh.dep.pam.crypto.SessionCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

enum class IdentifyResult {
    ACCEPTED,
    REJECTED_BY_VERIFIED_SERVER,
    REJECTED_UNVERIFIED
}

/**
 * TCP client that connects to the PamBio daemon on a PC.
 *
 * Handles the ECDH handshake, identify/pair flows, and listens for auth
 * requests. All network I/O runs on [Dispatchers.IO].
 */
class TcpClient(
    private val keyManager: IdentitySigner,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "TcpClient"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000
        const val DEFAULT_READ_TIMEOUT_MS = 18_000 // daemon pings every 5s
        private const val MAX_MSG_SIZE = 1 shl 20 // 1 MB
    }

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var sessionCrypto: SessionCrypto? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Callback for incoming auth requests. */
    var onAuthRequest: ((AuthRequestMessage) -> Unit)? = null

    /** Callback to check if device is still paired. */
    var isStillPaired: (suspend () -> Boolean)? = null

    // ── Connection ─────────────────────────────────────────

    /**
     * Connects to the daemon at [host]:[port] and performs the ECDH handshake.
     * Throws an exception if connection or handshake fails.
     */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            sock.soTimeout = readTimeoutMs
            socket = sock
            output = DataOutputStream(sock.getOutputStream())
            input = DataInputStream(sock.getInputStream())

            performHandshake()
        } catch (e: Exception) {
            NetLog.e(TAG, "Connect to $host:$port failed", e)
            disconnect()
            throw e
        }
    }

    /** Disconnects and cleans up. */
    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        input = null
        sessionCrypto = null
    }

    // ── Handshake ──────────────────────────────────────────

    private fun performHandshake() {
        val crypto = SessionCrypto()
        crypto.generateEphemeralKeypair()

        // Send hello
        val hello = HelloMessage(ecdhPub = crypto.getPublicKeyBase64())
        writePlaintext(json.encodeToString(hello))

        // Read hello_ack
        val ackJson = readPlaintext()
        val ack = json.decodeFromString<HelloAckMessage>(ackJson)
        if (ack.type != "hello_ack") error("Expected hello_ack, got ${ack.type}")

        // Derive session key
        crypto.deriveSessionKey(ack.ecdhPub)
        sessionCrypto = crypto

        NetLog.i(TAG, "ECDH handshake completed")
    }

    // ── Pairing ────────────────────────────────────────────

    /**
     * Sends a pair request to the daemon after successful handshake.
     * @param pcPubKey base64-encoded Ed25519 public key of the PC (from QR)
     * @param deviceName human-readable name for this Android device
     * @return true if pairing was accepted
     */
    suspend fun sendPairRequest(pcPubKey: String, deviceName: String): Boolean =
        withContext(Dispatchers.IO) {
            val pubKey = keyManager.publicKeyBase64 ?: keyManager.generateKeyPair()
            val proof = keyManager.sign(Base64.getDecoder().decode(pcPubKey.trim()))
                ?: return@withContext false

            val req = PairRequestMessage(
                devicePubKey = pubKey,
                deviceName = deviceName,
                proof = proof
            )
            writeEncrypted(json.encodeToString(req))

            val respJson = readEncrypted()
            val resp = json.decodeFromString<PairResponseMessage>(respJson)
            NetLog.i(TAG, "Pair response: ${resp.status}")
            resp.status == "accepted"
        }

    // ── Identify (reconnection) ────────────────────────────

    /**
     * Sends an identify message to prove we are a known paired device and
     * verifies the server's cryptographic proof when provided.
     */
    suspend fun sendIdentifyVerified(
        expectedPcPubKey: String? = null,
        trustUnverifiedReject: Boolean = false
    ): IdentifyResult = withContext(Dispatchers.IO) {
        val crypto = sessionCrypto ?: error("No session")
        val pubKey = keyManager.publicKeyBase64 ?: error("No keypair")
        val sessionId = crypto.sessionId ?: error("No session ID")

        val signature = keyManager.sign(sessionId) ?: return@withContext IdentifyResult.REJECTED_UNVERIFIED
        val msg = IdentifyMessage(
            devicePubKey = pubKey,
            signature = signature
        )
        writeEncrypted(json.encodeToString(msg))

        val ackJson = readEncrypted()
        val ack = json.decodeFromString<IdentifyAckMessage>(ackJson)
        NetLog.i(TAG, "Identify response: ${ack.status}")

        val serverSig = ack.serverSig
        if (!expectedPcPubKey.isNullOrBlank() && !serverSig.isNullOrBlank()) {
            val expectedPayload = SessionCrypto.SERVER_IDENTIFY_CONTEXT.toByteArray(Charsets.UTF_8) + sessionId
            val sigValid = SessionCrypto.verifyEd25519(expectedPcPubKey, expectedPayload, serverSig)
            if (!sigValid) {
                NetLog.w(TAG, "Server identity signature mismatch! Ignoring response.")
                return@withContext IdentifyResult.REJECTED_UNVERIFIED
            }
            return@withContext if (ack.status == "accepted") {
                IdentifyResult.ACCEPTED
            } else {
                IdentifyResult.REJECTED_BY_VERIFIED_SERVER
            }
        }

        if (ack.status == "accepted") {
            IdentifyResult.ACCEPTED
        } else if (trustUnverifiedReject) {
            IdentifyResult.REJECTED_BY_VERIFIED_SERVER
        } else {
            IdentifyResult.REJECTED_UNVERIFIED
        }
    }

    /** Backwards-compatible wrapper for simple callers. */
    suspend fun sendIdentify(): Boolean = sendIdentifyVerified() == IdentifyResult.ACCEPTED

    // ── Auth response ──────────────────────────────────────

    /**
     * Sends an auth response (approve or deny).
     */
    suspend fun sendAuthResponse(nonce: String, approved: Boolean) = withContext(Dispatchers.IO) {
        val status = if (approved) "approved" else "denied"
        val signature = if (approved) keyManager.sign(nonce.toByteArray()) ?: "" else ""

        val resp = AuthResponseMessage(
            status = status,
            nonce = nonce,
            signature = signature
        )
        writeEncrypted(json.encodeToString(resp))
    }

    // ── Listen loop ────────────────────────────────────────

    /**
     * Blocking loop that reads messages from the daemon.
     * Call from a coroutine; will run until disconnected.
     */
    suspend fun listenForMessages() = withContext(Dispatchers.IO) {
        try {
            while (isConnected) {
                val msgJson = readEncrypted()
                val base = json.decodeFromString<BaseMessage>(msgJson)

                when (base.type) {
                    "auth_request" -> {
                        val authReq = json.decodeFromString<AuthRequestMessage>(msgJson)
                        NetLog.i(TAG, "Auth request: user=${authReq.user} service=${authReq.service}")
                        onAuthRequest?.invoke(authReq)
                    }
                    "ping" -> {
                        val paired = isStillPaired?.invoke() ?: true
                        val status = if (paired) "ok" else "unpaired"
                        val pong = PongMessage(status = status)
                        writeEncrypted(json.encodeToString(pong))
                    }
                    else -> NetLog.w(TAG, "Unknown message type: ${base.type}")
                }
            }
        } catch (e: IOException) {
            NetLog.i(TAG, "Connection closed: ${e.message}")
        } catch (e: Exception) {
            NetLog.e(TAG, "Listen error", e)
        }
    }

    // ── Framing helpers ────────────────────────────────────

    private fun writePlaintext(data: String) {
        val bytes = data.toByteArray()
        val out = output ?: error("Not connected")
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    private fun readPlaintext(): String {
        val inp = input ?: error("Not connected")
        val length = inp.readInt()
        if (length !in 1..MAX_MSG_SIZE) error("Invalid message length: $length")
        val bytes = ByteArray(length)
        inp.readFully(bytes)
        return String(bytes)
    }

    private fun writeEncrypted(data: String) {
        val crypto = sessionCrypto ?: error("No session")
        val plaintext = data.toByteArray()
        val ciphertext = crypto.encrypt(plaintext)
        val out = output ?: error("Not connected")
        synchronized(out) {
            out.writeInt(ciphertext.size)
            out.write(ciphertext)
            out.flush()
        }
    }

    private fun readEncrypted(): String {
        val crypto = sessionCrypto ?: error("No session")
        val inp = input ?: error("Not connected")
        val length = inp.readInt()
        if (length !in 1..MAX_MSG_SIZE) error("Invalid message length: $length")
        val ciphertext = ByteArray(length)
        inp.readFully(ciphertext)
        val plaintext = crypto.decrypt(ciphertext)
        return String(plaintext)
    }
}
