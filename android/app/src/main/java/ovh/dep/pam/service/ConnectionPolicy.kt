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

package ovh.dep.pam.service

/**
 * Pure-Kotlin connection, backoff, nonce deduplication, and boot policies
 * shared by [PamBioForegroundService] and [BootReceiver] and covered by JVM
 * unit tests.
 */
object BootStartPolicy {
    /**
     * Checks whether `(nowHour, nowMinute)` falls inside the scheduled off window
     * `[offHour:offMinute, onHour:onMinute)`.
     */
    fun isInScheduledOffWindow(
        nowHour: Int,
        nowMinute: Int,
        offHour: Int,
        offMinute: Int,
        onHour: Int,
        onMinute: Int
    ): Boolean {
        val now = nowHour * 60 + nowMinute
        val off = offHour * 60 + offMinute
        val on = onHour * 60 + onMinute
        if (off == on) return false
        return if (off > on) {
            // Spans midnight (e.g. 23:00 -> 06:00)
            now >= off || now < on
        } else {
            now in off until on
        }
    }

    /**
     * Determines whether the foreground service should start at boot/update.
     */
    fun shouldStartOnBoot(
        autoStartOnBoot: Boolean,
        serviceEnabled: Boolean,
        hasPairedDevices: Boolean,
        timerEnabled: Boolean,
        nowHour: Int,
        nowMinute: Int,
        offHour: Int,
        offMinute: Int,
        onHour: Int,
        onMinute: Int
    ): Boolean {
        if (!autoStartOnBoot || !hasPairedDevices) return false
        if (!timerEnabled) return serviceEnabled
        return !isInScheduledOffWindow(nowHour, nowMinute, offHour, offMinute, onHour, onMinute)
    }
}

/**
 * Exponential backoff schedule for reconnection attempts.
 * Starts at 0 ms (immediate), then 1s -> 2s -> 4s -> 8s -> 15s -> 30s.
 */
class ReconnectBackoff(
    private val stepsMs: LongArray = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
) {
    private var index = 0

    fun nextDelayMs(): Long {
        val delay = stepsMs[index]
        if (index < stepsMs.lastIndex) {
            index++
        }
        return delay
    }

    fun reset() {
        index = 0
    }

    /** Used after a healthy connection drops: retry quickly (500ms) once. */
    fun resetAfterDrop() {
        index = if (stepsMs.size > 1) 1 else 0
    }
}

/**
 * Thread-safe bounded cache of recently handled auth request nonces so
 * re-delivered prompts during brief reconnects do not re-open the UI.
 */
class RecentNonceCache(
    private val maxEntries: Int = 32,
    private val ttlMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Returns true if [nonce] has NOT been seen within [ttlMs] (and records it).
     */
    @Synchronized
    fun markIfNew(nonce: String): Boolean {
        if (nonce.isBlank()) return false
        val now = clockMs()
        val iter = seen.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > ttlMs) {
                iter.remove()
            }
        }
        if (seen.containsKey(nonce)) {
            return false
        }
        seen[nonce] = now
        while (seen.size > maxEntries) {
            val eldest = seen.keys.firstOrNull() ?: break
            seen.remove(eldest)
        }
        return true
    }

    @Synchronized
    fun clear() {
        seen.clear()
    }
}

