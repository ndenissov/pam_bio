package ovh.dep.pam.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol message types matching the Go daemon's protocol/messages.go.
 */

// ── TCP protocol messages ──────────────────────────────────

@Serializable
data class HelloMessage(
    val type: String = "hello",
    @SerialName("ecdh_pub") val ecdhPub: String
)

@Serializable
data class HelloAckMessage(
    val type: String = "hello_ack",
    @SerialName("ecdh_pub") val ecdhPub: String
)

@Serializable
data class IdentifyMessage(
    val type: String = "identify",
    @SerialName("device_pub_key") val devicePubKey: String,
    val signature: String
)

@Serializable
data class IdentifyAckMessage(
    val type: String,
    val status: String
)

@Serializable
data class PairRequestMessage(
    val type: String = "pair_request",
    @SerialName("device_pub_key") val devicePubKey: String,
    @SerialName("device_name") val deviceName: String,
    val proof: String
)

@Serializable
data class PairResponseMessage(
    val type: String,
    val status: String
)

@Serializable
data class AuthRequestMessage(
    val type: String,
    val nonce: String,
    val timestamp: Long,
    val user: String,
    val service: String
)

@Serializable
data class AuthResponseMessage(
    val type: String = "auth_response",
    val status: String,
    val nonce: String,
    val signature: String = ""
)

// ── QR pairing payload ────────────────────────────────────

@Serializable
data class QrPairingPayload(
    @SerialName("service_name") val serviceName: String,
    @SerialName("pc_pub_key") val pcPubKey: String
)

// ── Helper to peek at type ────────────────────────────────

@Serializable
data class BaseMessage(val type: String)
