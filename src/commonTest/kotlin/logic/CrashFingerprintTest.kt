package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The fingerprint decides whether the crash list is usable. Too coarse and unrelated faults merge
 * into one unfixable issue; too fine and one bug shatters into thousands of rows. Both directions
 * are pinned here.
 */
class CrashFingerprintTest {

    @Test
    fun theSameCrashOnTwoDevicesGroupsTogetherDespiteAslr() {
        // Same build, same fault, different load address — which is the normal case, and the one
        // that would produce one issue per device if the slide were not removed.
        // Both resolve to static addresses 1_000_000 and 1_000_100.
        val a = CrashFingerprint.of("SIGSEGV", "boom", listOf(4_296_000_000, 4_296_000_100), slide = 4_295_000_000)
        val b = CrashFingerprint.of("SIGSEGV", "boom", listOf(9_001_000_000, 9_001_000_100), slide = 9_000_000_000)
        assertEquals(a, b, "identical stacks at different load addresses must be one issue")
    }

    @Test
    fun differentStacksAreDifferentIssues() {
        val a = CrashFingerprint.of("SIGSEGV", "boom", listOf(1000, 2000), slide = 0)
        val b = CrashFingerprint.of("SIGSEGV", "boom", listOf(1000, 3000), slide = 0)
        assertNotEquals(a, b)
    }

    @Test
    fun differentSignalsAtTheSameAddressStayApart() {
        val seg = CrashFingerprint.of("SIGSEGV", "", listOf(1000), slide = 0)
        val abrt = CrashFingerprint.of("SIGABRT", "", listOf(1000), slide = 0)
        assertNotEquals(seg, abrt)
    }

    @Test
    fun onlyTheTopFramesDecideIdentity() {
        // A shared crashing helper reached from two different places is still one issue as long as
        // the top frames match; conversely an extra frame deep in the stack from a runtime update
        // must not split a known issue in two.
        val deepA = (1..5).map { it * 100L } + listOf(9001L, 9002L)
        val deepB = (1..5).map { it * 100L } + listOf(7001L)
        assertEquals(
            CrashFingerprint.of("SIGSEGV", "", deepA, 0),
            CrashFingerprint.of("SIGSEGV", "", deepB, 0),
        )
    }

    @Test
    fun withoutFramesItFallsBackToNameAndMessage() {
        val a = CrashFingerprint.of("NullPointer", "value was null", emptyList(), 0)
        val b = CrashFingerprint.of("NullPointer", "value was null", emptyList(), 0)
        val c = CrashFingerprint.of("NullPointer", "index out of range", emptyList(), 0)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun varyingAddressesInAMessageDoNotShatterOneBug() {
        // The same fault reported with a different pointer each time is one bug, not thousands.
        val a = CrashFingerprint.of("Error", "bad access at 0x16f2a3b40", emptyList(), 0)
        val b = CrashFingerprint.of("Error", "bad access at 0x7ffee1c22", emptyList(), 0)
        assertEquals(a, b)
        assertEquals("bad access at 0x?", CrashFingerprint.normaliseMessage("bad access at 0x16f2a3b40"))
        assertEquals("failed after ? ms", CrashFingerprint.normaliseMessage("failed after 12345 ms"))
    }

    @Test
    fun aFingerprintIsShortAndStable() {
        val f = CrashFingerprint.of("SIGSEGV", "x", listOf(1, 2, 3), 0)
        assertEquals(16, f.length)
        assertEquals(f, CrashFingerprint.of("SIGSEGV", "x", listOf(1, 2, 3), 0))
        assertTrue(f.all { it in "0123456789abcdef" })
    }

    @Test
    fun framesParseDefensively() {
        assertEquals(listOf(1L, 2L, 3L), CrashFingerprint.parseFrames("1, 2 ,3"))
        assertEquals(listOf(1L, 3L), CrashFingerprint.parseFrames("1,not-a-number,3"))
        assertEquals(emptyList(), CrashFingerprint.parseFrames(null))
        assertEquals(emptyList(), CrashFingerprint.parseFrames(""))
    }

    @Test
    fun framesFromOtherImagesDoNotDestabiliseTheFingerprint() {
        // A real stack interleaves app frames with system library frames, and the system images
        // have their own load addresses. Including them would make the fingerprint change on every
        // launch — one new issue per crash, which is the exact opposite of aggregating.
        val slideA = 4_295_000_000L
        val slideB = 9_000_000_000L
        val appFrames = listOf(1_000_000L, 1_000_100L, 1_000_200L)

        val runA = listOf(
            slideA + appFrames[0], slideA + appFrames[1],
            6_665_893_700L,                      // a system frame, unrelated to slideA
            slideA + appFrames[2],
        )
        val runB = listOf(
            slideB + appFrames[0], slideB + appFrames[1],
            7_100_000_000L,                      // a different system frame on the next launch
            slideB + appFrames[2],
        )

        assertEquals(
            CrashFingerprint.of("SIGSEGV", "boom", runA, slideA),
            CrashFingerprint.of("SIGSEGV", "boom", runB, slideB),
            "system frames must not take part in the fingerprint",
        )
    }

    @Test
    fun aStackEntirelyOutsideTheKnownImageFallsBackToNameAndMessage() {
        // Nothing normalisable is left, so grouping by name and message is the honest answer
        // rather than hashing addresses that mean nothing.
        val a = CrashFingerprint.of("SIGSEGV", "boom", listOf(9_999_999_999L), slide = 1)
        val b = CrashFingerprint.of("SIGSEGV", "boom", listOf(8_888_888_888L), slide = 1)
        assertEquals(a, b)
    }
}
