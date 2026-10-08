package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * One release of a [PulseApp].
 *
 * Update checks compare the three-part [versionName] (`1.0.2` vs `1.0.3`) via [logic.SemVer];
 * [buildNumber] is the fallback when either side's name is not a parseable semver, and still must
 * increase with every release (the stores gate installs on it). The original worry — that a semver
 * rule implemented on both client and server would drift — does not apply here: the comparison runs
 * only on the server, from the client's reported version name; there is no second implementation.
 */
@Serializable
@Table("pulse_app_versions")
data class PulseAppVersion(
    @Id val id: Long = 0,
    val appId: Long,
    /** Displayed to users, e.g. "1.4.2". */
    val versionName: String,
    /** Compared against the client's current build; must increase with every release. */
    val buildNumber: Long,
    val releaseNotes: String = "",
    val downloadUrl: String? = null,
    /** True when the client must update before it may continue into business features. */
    val forceUpdate: Boolean = false,
    /** 0 draft, 1 published. Only published versions are visible to update checks. */
    val published: Int = 0,
    val publishedAt: Long? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
