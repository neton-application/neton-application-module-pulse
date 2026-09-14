package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crash-free rates are what a release decision is made on, so their arithmetic and their
 * behaviour at the edges are pinned here.
 *
 * They are constructor properties with derived defaults rather than computed `get()` accessors:
 * kotlinx.serialization only writes constructor properties, and as computed properties these — the
 * headline numbers of the whole page — silently never reached the browser.
 */
class VersionStatsTest {

    @Test
    fun ratesAreDerivedFromTheCounts() {
        val s = VersionStats(buildNumber = 141, devices = 4, crashedDevices = 1, sessions = 10, crashedSessions = 3)
        assertEquals(75.0, s.crashFreeDeviceRate)
        assertEquals(70.0, s.crashFreeSessionRate)
    }

    @Test
    fun aBuildWithNoDataIsNotReportedAsBroken() {
        // No devices means no crashes, and 0/0 has to resolve to "nothing wrong" rather than 0%,
        // which would make an unreleased build look like a total failure.
        val s = VersionStats(buildNumber = 999)
        assertEquals(100.0, s.crashFreeDeviceRate)
        assertEquals(100.0, s.crashFreeSessionRate)
    }

    @Test
    fun everyDeviceCrashingIsZeroPercent() {
        val s = VersionStats(buildNumber = 1, devices = 3, crashedDevices = 3, sessions = 3, crashedSessions = 3)
        assertEquals(0.0, s.crashFreeDeviceRate)
        assertEquals(0.0, s.crashFreeSessionRate)
    }

    @Test
    fun deviceAndSessionRatesDivergeWhenOneUserCrashesRepeatedly() {
        // One unhappy user crashing on every launch: the device rate stays high because only one
        // person is affected, while the session rate collapses. The gap between them is the point
        // of reporting both — a device rate alone would hide a crash loop, and a session rate
        // alone would make one user look like an outage.
        val s = VersionStats(buildNumber = 1, devices = 100, crashedDevices = 1, sessions = 100, crashedSessions = 50)
        assertTrue(s.crashFreeDeviceRate > 98.0, "device rate: ${s.crashFreeDeviceRate}")
        assertEquals(50.0, s.crashFreeSessionRate)
    }

    @Test
    fun theRatesAreSerialisedNotJustComputed() {
        // Regression guard for the actual bug: the console reads these off the JSON, so they must
        // be part of the serialised form.
        val json = kotlinx.serialization.json.Json.encodeToString(
            VersionStats.serializer(),
            VersionStats(buildNumber = 1, devices = 2, crashedDevices = 1, sessions = 2, crashedSessions = 1),
        )
        assertTrue(json.contains("crashFreeDeviceRate"), json)
        assertTrue(json.contains("crashFreeSessionRate"), json)
    }
}
