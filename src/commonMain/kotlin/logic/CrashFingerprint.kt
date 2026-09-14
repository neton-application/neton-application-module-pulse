package logic

/**
 * Turns a crash into a grouping key.
 *
 * Pure, and tested on its own, because this single decision determines whether the crash list is
 * usable: too coarse and unrelated faults merge into one unfixable issue, too fine and the same
 * bug shatters into thousands of rows.
 */
object CrashFingerprint {

    /**
     * Frames are normalised to static addresses before hashing. A runtime address is different on
     * every launch because of ASLR, so hashing those would give every device its own issue —
     * exactly the failure the aggregation exists to prevent.
     */
    fun of(name: String, message: String, frames: List<Long>, slide: Long): String {
        if (frames.isEmpty()) {
            // No stack to group by. Falling back to name and message is coarse, but grouping
            // by something beats one issue per event.
            return hash(listOf(name, normaliseMessage(message)))
        }
        // Only frames from the image whose slide we know can be normalised. A crash stack walks
        // through several images, and each has its own load address; subtracting the app's slide
        // from a system frame produces a number that changes every launch, which makes the whole
        // fingerprint unstable and files one new issue per crash instead of grouping them.
        val own = frames.map { it - slide }.filter { it > 0 && it < MAX_IMAGE_SPAN }
        if (own.isEmpty()) return hash(listOf(name, normaliseMessage(message)))
        val top = own.take(TOP_FRAMES).map { it.toString() }
        // The name is included so two different signals crashing at the same address stay apart.
        return hash(listOf(name) + top)
    }

    /**
     * Addresses, pointers and long digit runs inside a message vary per occurrence and would
     * shatter one bug into thousands of issues, so they are collapsed before hashing.
     */
    fun normaliseMessage(message: String): String =
        message
            .replace(HEX, "0x?")
            .replace(DIGITS, "?")
            .trim()
            .take(200)

    fun parseFrames(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    /** FNV-1a, rendered as 16 hex chars. Stable across processes and platforms. */
    private fun hash(parts: List<String>): String {
        var h = 0xcbf29ce484222325UL
        for (p in parts) {
            for (c in p) {
                h = h xor c.code.toULong()
                h *= 0x100000001b3UL
            }
            h = h xor 0x7cUL
            h *= 0x100000001b3UL
        }
        return h.toString(16).padStart(16, '0')
    }

    /**
     * How many frames decide identity. Deep enough that two different callers of one crashing
     * helper stay separate; shallow enough that an extra frame from a runtime update does not
     * split a known issue in two.
     */
    private const val TOP_FRAMES = 5

    /**
     * Largest plausible offset within one image. Anything beyond this came from a different image
     * loaded at a different address, so it cannot be normalised with this slide.
     */
    private const val MAX_IMAGE_SPAN = 1L shl 30

    private val HEX = Regex("0x[0-9a-fA-F]+")
    private val DIGITS = Regex("\\d{3,}")
}
