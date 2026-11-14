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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {

    @Test
    fun scheduledOffWindowHandlesDaytimeAndMidnightWrap() {
        // Equal start/end means disabled
        assertFalse(BootStartPolicy.isInScheduledOffWindow(12, 0, 8, 0, 8, 0))

        // Same-day off window [09:00, 17:00)
        assertFalse(BootStartPolicy.isInScheduledOffWindow(8, 59, 9, 0, 17, 0))
        assertTrue(BootStartPolicy.isInScheduledOffWindow(9, 0, 9, 0, 17, 0))
        assertTrue(BootStartPolicy.isInScheduledOffWindow(12, 30, 9, 0, 17, 0))
        assertFalse(BootStartPolicy.isInScheduledOffWindow(17, 0, 9, 0, 17, 0))

        // Overnight off window [23:00, 06:00)
        assertFalse(BootStartPolicy.isInScheduledOffWindow(22, 59, 23, 0, 6, 0))
        assertTrue(BootStartPolicy.isInScheduledOffWindow(23, 0, 23, 0, 6, 0))
        assertTrue(BootStartPolicy.isInScheduledOffWindow(2, 15, 23, 0, 6, 0))
        assertFalse(BootStartPolicy.isInScheduledOffWindow(6, 0, 23, 0, 6, 0))
    }

    @Test
    fun shouldStartOnBootRespectsUserSettingDevicesAndTimer() {
        // Disabled autoStartOnBoot -> never start on boot
        assertFalse(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = false,
                serviceEnabled = true,
                hasPairedDevices = true,
                timerEnabled = false,
                nowHour = 12,
                nowMinute = 0,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )

        // No paired devices -> never start on boot
        assertFalse(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = true,
                serviceEnabled = true,
                hasPairedDevices = false,
                timerEnabled = false,
                nowHour = 12,
                nowMinute = 0,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )

        // Enabled + no timer -> follows serviceEnabled
        assertTrue(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = true,
                serviceEnabled = true,
                hasPairedDevices = true,
                timerEnabled = false,
                nowHour = 2,
                nowMinute = 0,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )
        assertFalse(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = true,
                serviceEnabled = false,
                hasPairedDevices = true,
                timerEnabled = false,
                nowHour = 12,
                nowMinute = 0,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )

        // Timer enabled: suppresses start inside off window, starts outside off window
        assertFalse(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = true,
                serviceEnabled = false,
                hasPairedDevices = true,
                timerEnabled = true,
                nowHour = 1,
                nowMinute = 30,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )
        assertTrue(
            BootStartPolicy.shouldStartOnBoot(
                autoStartOnBoot = true,
                serviceEnabled = false,
                hasPairedDevices = true,
                timerEnabled = true,
                nowHour = 10,
                nowMinute = 0,
                offHour = 23,
                offMinute = 0,
                onHour = 7,
                onMinute = 0
            )
        )
    }

    @Test
    fun reconnectBackoffProgressionAndResets() {
        val backoff = ReconnectBackoff()
        val observed = List(8) { backoff.nextDelayMs() }
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L),
            observed
        )

        backoff.resetAfterDrop()
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())

        backoff.reset()
        assertEquals(0L, backoff.nextDelayMs())
    }

    @Test
    fun recentNonceCacheDeduplicatesExpiresAndEvicts() {
        var fakeNow = 1_000L
        val cache = RecentNonceCache(maxEntries = 2, ttlMs = 500L, clockMs = { fakeNow })

        assertFalse(cache.markIfNew("   "))
        assertTrue(cache.markIfNew("n1"))
        assertFalse(cache.markIfNew("n1"))

        fakeNow = 1_400L
        assertFalse(cache.markIfNew("n1"))

        // Advance beyond TTL (500ms) -> expires
        fakeNow = 1_600L
        assertTrue(cache.markIfNew("n1"))

        // Exceed capacity 2 -> evicts oldest ("n1")
        assertTrue(cache.markIfNew("n2"))
        assertTrue(cache.markIfNew("n3"))
        assertTrue(cache.markIfNew("n1"))
    }
}

