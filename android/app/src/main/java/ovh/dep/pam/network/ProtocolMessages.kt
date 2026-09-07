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

@Serializable
data class PingMessage(
    val type: String
)

@Serializable
data class PongMessage(
    val type: String = "pong",
    val status: String
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
