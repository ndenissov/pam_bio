package ovh.dep.pam.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ovh.dep.pam.crypto.KeyManager
import ovh.dep.pam.crypto.SessionCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client that connects to the PamBio daemon on a PC.
 *
 * Handles the ECDH handshake, identify/pair flows, and listens for auth
 * requests. All network I/O runs on [Dispatchers.IO].
 */
class TcpClient(
    private val keyManager: KeyManager,
    private val json: Json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true 
    }
) {
    companion object {
        private const val TAG = "TcpClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
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
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = sock
            output = DataOutputStream(sock.getOutputStream())
            input = DataInputStream(sock.getInputStream())

            performHandshake()
        } catch (e: Exception) {
            Log.e(TAG, "Connect to $host:$port failed", e)
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

        Log.i(TAG, "ECDH handshake completed")
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
            val proof = keyManager.sign(android.util.Base64.decode(pcPubKey, android.util.Base64.NO_WRAP))

            val req = PairRequestMessage(
                devicePubKey = pubKey,
                deviceName = deviceName,
                proof = proof
            )
            writeEncrypted(json.encodeToString(req))

            val respJson = readEncrypted()
            val resp = json.decodeFromString<PairResponseMessage>(respJson)
            Log.i(TAG, "Pair response: ${resp.status}")
            resp.status == "accepted"
        }

    // ── Identify (reconnection) ────────────────────────────

    /**
     * Sends an identify message to prove we are a known paired device.
     * @return true if identification was accepted
     */
    suspend fun sendIdentify(): Boolean = withContext(Dispatchers.IO) {
        val crypto = sessionCrypto ?: error("No session")
        val pubKey = keyManager.publicKeyBase64 ?: error("No keypair")
        val sessionId = crypto.sessionId ?: error("No session ID")

        val signature = keyManager.sign(sessionId)
        val msg = IdentifyMessage(
            devicePubKey = pubKey,
            signature = signature
        )
        writeEncrypted(json.encodeToString(msg))

        val ackJson = readEncrypted()
        val ack = json.decodeFromString<IdentifyAckMessage>(ackJson)
        Log.i(TAG, "Identify response: ${ack.status}")
        ack.status == "accepted"
    }

    // ── Auth response ──────────────────────────────────────

    /**
     * Sends an auth response (approve or deny).
     */
    suspend fun sendAuthResponse(nonce: String, approved: Boolean) = withContext(Dispatchers.IO) {
        val status = if (approved) "approved" else "denied"
        val signature = if (approved) keyManager.sign(nonce.toByteArray()) else ""

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
                        Log.i(TAG, "Auth request: user=${authReq.user} service=${authReq.service}")
                        onAuthRequest?.invoke(authReq)
                    }
                    "ping" -> {
                        val paired = isStillPaired?.invoke() ?: true
                        val status = if (paired) "ok" else "unpaired"
                        val pong = PongMessage(status = status)
                        writeEncrypted(json.encodeToString(pong))
                    }
                    else -> Log.w(TAG, "Unknown message type: ${base.type}")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Connection closed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Listen error", e)
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
        if (length > MAX_MSG_SIZE) error("Message too large: $length")
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
        if (length > MAX_MSG_SIZE) error("Message too large: $length")
        val ciphertext = ByteArray(length)
        inp.readFully(ciphertext)
        val plaintext = crypto.decrypt(ciphertext)
        return String(plaintext)
    }
}
