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

import java.net.Inet4Address
import java.net.InetAddress

/**
 * A concrete TCP endpoint candidate for reaching a paired PC.
 */
data class EndpointCandidate(
    val host: String,
    val port: Int,
    val source: Source
) {
    enum class Source {
        UDP,
        NSD,
        CACHED
    }
}

/**
 * Ranks and deduplicates candidate endpoints and host addresses so IPv4 LAN
 * addresses are always attempted before global IPv6 and link-local IPv6 (`fe80::`),
 * and freshly discovered addresses precede stale cached ones.
 */
object EndpointSelector {
    const val DEFAULT_PORT = 34907

    /** Returns a rank for an IP literal: lower is better. */
    fun addressPriority(host: String): Int {
        val clean = host.substringBefore('%').trim()
        if (clean.isEmpty()) return Int.MAX_VALUE
        // Quick syntactic check before InetAddress resolution to avoid DNS lookups
        if (clean.contains('.')) return 0 // IPv4 literal
        if (clean.contains(':')) {
            val lower = clean.lowercase()
            return if (lower.startsWith("fe80:")) 3 else 1 // link-local IPv6 last
        }
        return 2
    }

    /** Sorts [InetAddress] objects preferring IPv4 -> non-link-local IPv6 -> link-local IPv6. */
    fun sortInetAddresses(addrs: Collection<InetAddress>): List<String> {
        return addrs
            .filter { !it.isAnyLocalAddress && !it.isMulticastAddress }
            .sortedWith(
                compareBy<InetAddress> { addr ->
                    when {
                        addr is Inet4Address -> 0
                        !addr.isLinkLocalAddress -> 1
                        else -> 2
                    }
                }.thenBy { it.hostAddress ?: "" }
            )
            .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
            .distinct()
    }

    /** Sorts raw host strings by address priority while preserving order within ties. */
    fun sortHosts(hosts: Collection<String>): List<String> {
        return hosts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { addressPriority(it) }
    }

    /**
     * Builds an ordered, deduplicated list of TCP endpoints to attempt.
     *
     * Priority:
     * 1. Fresh UDP announcement (carries the exact reachable LAN IPv4 + port).
     * 2. NSD resolved endpoints (IPv4 first, link-local IPv6 last).
     * 3. Cached last-known endpoint (`lastHost:lastPort`), plus `lastHost:34907` if different.
     */
    fun buildCandidates(
        udpService: UdpDiscoveredService?,
        nsdService: ResolvedService?,
        lastHost: String,
        lastPort: Int
    ): List<EndpointCandidate> {
        val seen = LinkedHashSet<Pair<String, Int>>()
        val result = ArrayList<EndpointCandidate>()

        fun add(host: String, port: Int, source: EndpointCandidate.Source) {
            val h = host.trim()
            if (h.isEmpty() || port !in 1..65535) return
            if (seen.add(h to port)) {
                result.add(EndpointCandidate(h, port, source))
            }
        }

        if (udpService != null) {
            add(udpService.host, udpService.port, EndpointCandidate.Source.UDP)
        }

        if (nsdService != null) {
            val sortedNsdHosts = sortHosts(nsdService.hosts.ifEmpty { listOf(nsdService.host) })
            for (h in sortedNsdHosts) {
                add(h, nsdService.port, EndpointCandidate.Source.NSD)
            }
        }

        if (lastHost.isNotBlank()) {
            val effectivePort = if (lastPort in 1..65535) lastPort else DEFAULT_PORT
            add(lastHost, effectivePort, EndpointCandidate.Source.CACHED)
            if (effectivePort != DEFAULT_PORT) {
                add(lastHost, DEFAULT_PORT, EndpointCandidate.Source.CACHED)
            }
        }

        return result
    }
}

