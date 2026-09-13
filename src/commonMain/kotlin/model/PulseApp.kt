package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * One application, per platform. The same product's iOS and Android builds are two rows, not one
 * with a platform list: their package names, review cadence and force-update policy genuinely
 * differ, and folding them together only pushes platform branching into every query.
 *
 * [appKey] is the same value the SDK reports as `projectId`, which is what ties an event stream to
 * an app record.
 */
@Serializable
@Table("pulse_apps")
data class PulseApp(
    @Id val id: Long = 0,
    val name: String,
    /** ios / android / harmony / web — see [PulsePlatform]. */
    val platform: String,
    val appKey: String,
    /** iOS bundle id, Android applicationId, HarmonyOS bundleName. Null for web. */
    val packageName: String? = null,
    val remark: String? = null,
    /** 1 enabled, 0 disabled. A disabled app keeps its data but stops answering update checks. */
    val status: Int = 1,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

/**
 * Supported platforms. Stored as a string so adding one is not a schema change; validated on write
 * so a typo cannot create an app nothing will ever match.
 */
object PulsePlatform {
    const val IOS = "ios"
    const val ANDROID = "android"
    const val HARMONY = "harmony"
    const val WEB = "web"

    val ALL = listOf(IOS, ANDROID, HARMONY, WEB)

    fun isValid(platform: String): Boolean = platform in ALL
}
