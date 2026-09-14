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
    /** strict / allowlist / any — see [PulsePackagePolicy]. */
    val packagePolicy: String = PulsePackagePolicy.STRICT,
    /** Extra bundle identifiers for allowlist policy, comma or newline separated. */
    val allowedPackages: String? = null,
    val remark: String? = null,
    /** 1 enabled, 0 disabled. A disabled app keeps its data but stops answering update checks. */
    val status: Int = 1,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

/**
 * Package validation belongs to an App, not to the App ID itself. App IDs are stable public
 * identifiers; bundle identifiers can legitimately change during internal resigning.
 */
object PulsePackagePolicy {
    const val STRICT = "strict"
    const val ALLOWLIST = "allowlist"
    const val ANY = "any"
    val ALL = listOf(STRICT, ALLOWLIST, ANY)

    fun isValid(value: String) = value in ALL

    fun accepts(app: PulseApp, reported: String?): Boolean = when (app.packagePolicy) {
        ANY -> true
        STRICT -> !reported.isNullOrBlank() && reported == app.packageName
        ALLOWLIST -> !reported.isNullOrBlank() && reported in buildSet {
            app.packageName?.takeIf(String::isNotBlank)?.let(::add)
            app.allowedPackages.orEmpty().split(',', '\n').map(String::trim)
                .filter(String::isNotBlank).forEach(::add)
        }
        else -> false
    }
}

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
