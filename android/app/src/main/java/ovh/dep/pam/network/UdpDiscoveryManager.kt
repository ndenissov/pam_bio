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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovered PamBio daemon from UDP broadcast/unicast discovery.
 */
data class UdpDiscoveredService(
    val serviceName: String,
    val host: String,
    val port: Int,
    val pcPubKey: String = "",
    val lastSeenMs: Long = System.currentTimeMillis()
)

/**
 * KDE-Connect-style UDP discovery client & knock listener.
 *
 * Sends `discover` probes via LAN broadcast, subnet directed broadcasts, and
 * unicast to cached PC IPs, and listens for `announce` packets (both direct
 * replies and unsolicited PC startup/wakeup "knocks") so reconnection completes
 * in milliseconds without waiting on mDNS caches.
 */
class UdpDiscoveryManager(
    private val listenPort: Int = DEFAULT_DISCOVERY_PORT,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "UdpDiscovery"
        const val DEFAULT_DISCOVERY_PORT = 34907
        private const val MAX_DATAGRAM_BYTES = 2048

        /** Collects all active IPv4 directed broadcast addresses + 255.255.255.255. */
        fun collectBroadcastAddresses(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    for (ifAddr in iface.interfaceAddresses) {
                        val bcast = ifAddr.broadcast ?: continue
                        if (bcast is Inet4Address) {
                            out.add(bcast)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                out.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Exception) {
            }
            return out.toList()
        }
    }

    private val _services = MutableStateFlow<Map<String, UdpDiscoveredService>>(emptyMap())
    val services: StateFlow<Map<String, UdpDiscoveredService>> = _services

    private val _announceEvents = MutableSharedFlow<UdpDiscoveredService>(extraBufferCapacity = 32)
    val announceEvents: SharedFlow<UdpDiscoveredService> = _announceEvents.asSharedFlow()

    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null

    val localPort: Int
        get() = socketRef.get()?.localPort ?: -1

    @Synchronized
    fun start() {
        if (socketRef.get() != null) return

        val sock = openSocket() ?: return
        socketRef.set(sock)
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = localScope
        receiveJob = localScope.launch {
            receiveLoop(sock)
        }
    }

    @Synchronized
    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        scope?.cancel()
        scope = null
        val sock = socketRef.getAndSet(null)
        try {
            sock?.close()
        } catch (_: Exception) {
        }
        _services.value = emptyMap()
    }

    fun clearCache() {
        _services.value = emptyMap()
    }

    /** Finds a discovered service matching [pcPubKey] (preferred) or [serviceName]. */
    fun findMatch(serviceName: String, pcPubKey: String = ""): UdpDiscoveredService? {
        val current = _services.value
        if (pcPubKey.isNotBlank()) {
            current.values.firstOrNull { it.pcPubKey == pcPubKey }?.let { return it }
        }
        return current[serviceName]
    }

    /**
     * Broadcasts a `discover` datagram to LAN broadcasts plus unicast to [targetHosts].
     */
    suspend fun sendDiscover(
        targetHosts: Collection<String> = emptyList(),
        targetPort: Int = DEFAULT_DISCOVERY_PORT
    ) = withContext(Dispatchers.IO) {
        val sock = socketRef.get() ?: return@withContext
        if (sock.isClosed) return@withContext

        val payload = json.encodeToString(DiscoveryMessage(type = "discover", v = 1))
            .toByteArray(Charsets.UTF_8)

        val destinations = LinkedHashSet<InetAddress>()
        for (host in targetHosts) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) continue
            try {
                destinations.add(InetAddress.getByName(trimmed))
            } catch (_: Exception) {
            }
        }
        destinations.addAll(collectBroadcastAddresses())

        for (addr in destinations) {
            try {
                val packet = DatagramPacket(payload, payload.size, addr, targetPort)
                sock.send(packet)
            } catch (e: Exception) {
                NetLog.d(TAG, "discover send to ${addr.hostAddress}:$targetPort failed: ${e.message}")
            }
        }
    }

    private fun openSocket(): DatagramSocket? {
        return try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.broadcast = true
            try {
                sock.bind(InetSocketAddress(listenPort))
            } catch (e: BindException) {
                if (listenPort != 0) {
                    NetLog.w(TAG, "Port $listenPort busy, falling back to ephemeral UDP port: ${e.message}")
                    sock.bind(InetSocketAddress(0))
                } else {
                    throw e
                }
            }
            NetLog.i(TAG, "UDP discovery bound on port ${sock.localPort}")
            sock
        } catch (e: Exception) {
            NetLog.e(TAG, "Failed to bind UDP discovery socket", e)
            null
        }
    }

    private fun CoroutineScope.receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM_BYTES)
        while (isActive && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val msg = json.decodeFromString<DiscoveryMessage>(raw)
                if (msg.type != "announce" || msg.port !in 1..65535 || msg.serviceName.isBlank()) {
                    continue
                }
                val host = packet.address?.hostAddress?.takeIf { it.isNotBlank() } ?: continue
                val discovered = UdpDiscoveredService(
                    serviceName = msg.serviceName.removePrefix("pambio_"),
                    host = host,
                    port = msg.port,
                    pcPubKey = msg.pk,
                    lastSeenMs = System.currentTimeMillis()
                )
                _services.value = _services.value + (discovered.serviceName to discovered)
                _announceEvents.tryEmit(discovered)
                NetLog.d(TAG, "UDP announce: ${discovered.serviceName} -> ${discovered.host}:${discovered.port}")
            } catch (e: Exception) {
                if (sock.isClosed || !isActive) break
                NetLog.d(TAG, "UDP receive loop error: ${e.message}")
            }
        }
    }
}

