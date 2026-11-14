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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ovh.dep.pam.crypto.IdentitySigner
import ovh.dep.pam.crypto.SessionCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ProtocolAndDiscoveryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private class InMemoryIdentity : IdentitySigner {
        private var priv: Ed25519PrivateKeyParameters? = null
        private var pub: Ed25519PublicKeyParameters? = null

        override val hasKeyPair: Boolean
            get() = priv != null && pub != null

        override val publicKeyBase64: String?
            get() = pub?.let { Base64.getEncoder().encodeToString(it.encoded) }

        override fun generateKeyPair(): String {
            val gen = Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(SecureRandom()))
            }
            val kp = gen.generateKeyPair()
            priv = kp.private as Ed25519PrivateKeyParameters
            pub = kp.public as Ed25519PublicKeyParameters
            return publicKeyBase64!!
        }

        override fun sign(data: ByteArray): String? {
            val p = priv ?: return null
            val signer = Ed25519Signer().apply {
                init(true, p)
                update(data, 0, data.size)
            }
            return Base64.getEncoder().encodeToString(signer.generateSignature())
        }
    }

    @Test
    fun endpointSelectorPrioritizesIpv4BeforeIpv6AndLinkLocal() {
        val sortedHosts = EndpointSelector.sortHosts(
            listOf(
                "fe80::1ff:fe23:4567:890a%wlan0",
                "2001:db8::5",
                "192.168.1.42",
                "192.168.1.42",
                "  ",
                "fe80::1"
            )
        )
        assertEquals(
            listOf(
                "192.168.1.42",
                "2001:db8::5",
                "fe80::1ff:fe23:4567:890a%wlan0",
                "fe80::1"
            ),
            sortedHosts
        )

        val addrs = EndpointSelector.sortInetAddresses(
            listOf(
                InetAddress.getByName("fe80::10"),
                InetAddress.getByName("2001:db8::5"),
                InetAddress.getByName("192.168.50.9"),
                InetAddress.getByName("0.0.0.0"),
                InetAddress.getByName("224.0.0.251")
            )
        )
        assertEquals(
            listOf(
                "192.168.50.9",
                InetAddress.getByName("2001:db8::5").hostAddress,
                InetAddress.getByName("fe80::10").hostAddress
            ),
            addrs
        )
    }

    @Test
    fun endpointSelectorBuildsRankedDeduplicatedCandidates() {
        val udp = UdpDiscoveredService("home-pc", "192.168.1.10", 34907, "pk")
        val nsd = ResolvedService(
            serviceName = "home-pc",
            host = "fe80::1",
            port = 34907,
            hosts = listOf("fe80::1", "192.168.1.10", "192.168.1.11")
        )

        val candidates = EndpointSelector.buildCandidates(
            udpService = udp,
            nsdService = nsd,
            lastHost = "192.168.1.11",
            lastPort = 40123
        )

        assertEquals(
            listOf(
                EndpointCandidate("192.168.1.10", 34907, EndpointCandidate.Source.UDP),
                EndpointCandidate("192.168.1.11", 34907, EndpointCandidate.Source.NSD),
                EndpointCandidate("fe80::1", 34907, EndpointCandidate.Source.NSD),
                EndpointCandidate("192.168.1.11", 40123, EndpointCandidate.Source.CACHED)
            ),
            candidates
        )
    }

    @Test
    fun qrPayloadParserHandlesBinaryJsonAndMalformedInputs() {
        val rawPub = ByteArray(32) { it.toByte() }
        val expectedPubB64 = Base64.getEncoder().encodeToString(rawPub)
        val nameBytes = "workstation".toByteArray(Charsets.UTF_8)
        val binary = byteArrayOf(nameBytes.size.toByte()) + nameBytes + rawPub

        val parsedBin = QrPayloadParser.parseBytes(binary)
        assertNotNull(parsedBin)
        assertEquals("workstation", parsedBin!!.serviceName)
        assertEquals(expectedPubB64, parsedBin.pcPubKey)

        val jsonStr = """{"service_name":"laptop","pc_pub_key":"$expectedPubB64"}"""
        val b64Wrapped = Base64.getEncoder().encodeToString(jsonStr.toByteArray(Charsets.UTF_8))

        val parsedJsonB64 = QrPayloadParser.parseBase64Text(b64Wrapped)
        assertNotNull(parsedJsonB64)
        assertEquals("laptop", parsedJsonB64!!.serviceName)
        assertEquals(expectedPubB64, parsedJsonB64.pcPubKey)

        val parsedRawJson = QrPayloadParser.parseBase64Text(jsonStr)
        assertNotNull(parsedRawJson)
        assertEquals("laptop", parsedRawJson!!.serviceName)

        assertNull(QrPayloadParser.parseBytes(byteArrayOf()))
        assertNull(QrPayloadParser.parseBase64Text("not valid json or binary"))
    }

    @Test
    fun udpDiscoveryReceivesAnnounceAndSendsDiscoverOverLocalhost() = runBlocking {
        val mgr = UdpDiscoveryManager(listenPort = 0)
        mgr.start()
        try {
            val port = mgr.localPort
            assertTrue("expected bound ephemeral UDP port, got $port", port > 0)

            // Wait briefly for receiveLoop to enter sock.receive()
            delay(50)

            val sender = DatagramSocket()
            sender.soTimeout = 2_000
            try {
                // Send unsolicited announce from localhost -> manager should index under stripped name
                val announce = json.encodeToString(
                    DiscoveryMessage(
                        type = "announce",
                        v = 1,
                        serviceName = "pambio_nikita-pc",
                        port = 34907,
                        pk = "test-pc-pk"
                    )
                ).toByteArray(Charsets.UTF_8)
                sender.send(DatagramPacket(announce, announce.size, InetAddress.getByName("127.0.0.1"), port))

                withTimeout(2_000) {
                    while (mgr.findMatch("nikita-pc", "test-pc-pk") == null) {
                        delay(20)
                    }
                }
                val match = mgr.findMatch("other-name", "test-pc-pk")
                assertNotNull(match)
                assertEquals("127.0.0.1", match!!.host)
                assertEquals(34907, match.port)

                // Now have mgr send discover unicast directly to sender's UDP port
                val listenerPort = sender.localPort
                mgr.sendDiscover(targetHosts = listOf("127.0.0.1"), targetPort = listenerPort)
                val recvBuf = ByteArray(1024)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                sender.receive(recvPkt)
                val discoverMsg = json.decodeFromString<DiscoveryMessage>(
                    String(recvPkt.data, 0, recvPkt.length, Charsets.UTF_8)
                )
                assertEquals("discover", discoverMsg.type)
                assertEquals(1, discoverMsg.v)
            } finally {
                sender.close()
            }
        } finally {
            mgr.stop()
        }
    }

    @Test
    fun tcpClientPairIdentifyAuthAndServerSignatureVerification() = runBlocking {
        val pcIdentity = InMemoryIdentity().apply { generateKeyPair() }
        val rogueIdentity = InMemoryIdentity().apply { generateKeyPair() }
        val phoneIdentity = InMemoryIdentity().apply { generateKeyPair() }
        val receivedAuthResponses = CopyOnWriteArrayList<AuthResponseMessage>()
        val pongStatus = CompletableDeferred<String>()

        val serverSocket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort

        // Mode sequence across connections:
        // conn 1: pairing
        // conn 2: identify accepted with valid server_sig -> then auth_request + ping
        // conn 3: identify rejected WITH valid server_sig -> REJECTED_BY_VERIFIED_SERVER
        // conn 4: identify rejected WITH rogue server_sig -> REJECTED_UNVERIFIED
        val serverJob = launch(Dispatchers.IO) {
            repeat(4) { connIdx ->
                val sock = serverSocket.accept()
                sock.soTimeout = 4_000
                try {
                    val inp = DataInputStream(sock.getInputStream())
                    val out = DataOutputStream(sock.getOutputStream())

                    val readFrame: () -> ByteArray = {
                        val len = inp.readInt()
                        ByteArray(len).also { inp.readFully(it) }
                    }
                    val writeFrame: (ByteArray) -> Unit = { data ->
                        out.writeInt(data.size)
                        out.write(data)
                        out.flush()
                    }

                    // Handshake
                    val hello = json.decodeFromString<HelloMessage>(String(readFrame(), Charsets.UTF_8))
                    val serverCrypto = SessionCrypto().apply { generateEphemeralKeypair() }
                    writeFrame(json.encodeToString(HelloAckMessage(ecdhPub = serverCrypto.getPublicKeyBase64())).toByteArray(Charsets.UTF_8))
                    serverCrypto.deriveSessionKey(hello.ecdhPub)

                    val readEnc: () -> String = {
                        String(serverCrypto.decrypt(readFrame()), Charsets.UTF_8)
                    }
                    val writeEnc: (String) -> Unit = { text ->
                        writeFrame(serverCrypto.encrypt(text.toByteArray(Charsets.UTF_8)))
                    }

                    val sigPayload = SessionCrypto.SERVER_IDENTIFY_CONTEXT.toByteArray(Charsets.UTF_8) + serverCrypto.sessionId!!

                    when (connIdx) {
                        0 -> {
                            val pairReq = json.decodeFromString<PairRequestMessage>(readEnc())
                            val expectedProofData = Base64.getDecoder().decode(pcIdentity.publicKeyBase64!!)
                            assertTrue(SessionCrypto.verifyEd25519(pairReq.devicePubKey, expectedProofData, pairReq.proof))
                            writeEnc(json.encodeToString(PairResponseMessage(type = "pair_response", status = "accepted")))
                        }
                        1 -> {
                            val idReq = json.decodeFromString<IdentifyMessage>(readEnc())
                            assertTrue(SessionCrypto.verifyEd25519(idReq.devicePubKey, serverCrypto.sessionId!!, idReq.signature))
                            val serverSig = pcIdentity.sign(sigPayload)!!
                            writeEnc(json.encodeToString(IdentifyAckMessage(type = "identify_ack", status = "accepted", serverSig = serverSig)))

                            // Push auth_request -> expect auth_response
                            writeEnc(
                                json.encodeToString(
                                    AuthRequestMessage(
                                        type = "auth_request",
                                        nonce = "nonce-42",
                                        timestamp = 1700000000L,
                                        user = "nikita",
                                        service = "sudo"
                                    )
                                )
                            )
                            val authResp = json.decodeFromString<AuthResponseMessage>(readEnc())
                            receivedAuthResponses.add(authResp)

                            // Push ping -> expect pong
                            writeEnc(json.encodeToString(PingMessage(type = "ping")))
                            val pong = json.decodeFromString<PongMessage>(readEnc())
                            pongStatus.complete(pong.status)
                        }
                        2 -> {
                            json.decodeFromString<IdentifyMessage>(readEnc())
                            val validRejectSig = pcIdentity.sign(sigPayload)!!
                            writeEnc(json.encodeToString(IdentifyAckMessage(type = "identify_ack", status = "rejected", serverSig = validRejectSig)))
                        }
                        3 -> {
                            json.decodeFromString<IdentifyMessage>(readEnc())
                            val rogueRejectSig = rogueIdentity.sign(sigPayload)!!
                            writeEnc(json.encodeToString(IdentifyAckMessage(type = "identify_ack", status = "rejected", serverSig = rogueRejectSig)))
                        }
                    }
                } finally {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        }

        try {
            // 1. Pairing
            val pairClient = TcpClient(phoneIdentity, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
            pairClient.connect("127.0.0.1", port)
            assertTrue(pairClient.sendPairRequest(pcIdentity.publicKeyBase64!!, "Test-Pixel"))
            pairClient.disconnect()

            // 2. Verified identify + auth_request + ping/pong
            val authClient = TcpClient(phoneIdentity, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
            authClient.connect("127.0.0.1", port)
            val idRes1 = authClient.sendIdentifyVerified(pcIdentity.publicKeyBase64)
            assertEquals(IdentifyResult.ACCEPTED, idRes1)

            val receivedReq = CompletableDeferred<AuthRequestMessage>()
            authClient.isStillPaired = { true }
            authClient.onAuthRequest = { req -> receivedReq.complete(req) }

            val listenJob = launch { authClient.listenForMessages() }
            val authReq = withTimeout(2_000) { receivedReq.await() }
            assertEquals("nonce-42", authReq.nonce)
            assertEquals("sudo", authReq.service)

            authClient.sendAuthResponse(authReq.nonce, approved = true)
            assertEquals("ok", withTimeout(2_000) { pongStatus.await() })
            listenJob.cancel()
            authClient.disconnect()

            assertEquals(1, receivedAuthResponses.size)
            assertEquals("approved", receivedAuthResponses[0].status)
            assertTrue(
                SessionCrypto.verifyEd25519(
                    phoneIdentity.publicKeyBase64!!,
                    "nonce-42".toByteArray(),
                    receivedAuthResponses[0].signature
                )
            )

            // 3. Verified server rejection
            val rejectClient = TcpClient(phoneIdentity, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
            rejectClient.connect("127.0.0.1", port)
            assertEquals(
                IdentifyResult.REJECTED_BY_VERIFIED_SERVER,
                rejectClient.sendIdentifyVerified(pcIdentity.publicKeyBase64)
            )
            rejectClient.disconnect()

            // 4. Rogue server rejection must NOT trigger unpair
            val spoofClient = TcpClient(phoneIdentity, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
            spoofClient.connect("127.0.0.1", port)
            assertEquals(
                IdentifyResult.REJECTED_UNVERIFIED,
                spoofClient.sendIdentifyVerified(pcIdentity.publicKeyBase64, trustUnverifiedReject = true)
            )
            spoofClient.disconnect()

            withTimeout(2_000) { serverJob.join() }
        } finally {
            serverJob.cancel()
            serverSocket.close()
        }
    }
}
