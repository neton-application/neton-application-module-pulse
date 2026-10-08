package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * One release of a [PulseApp].
 *
 * Ordering is by [buildNumber], never by [versionName]. A semantic version string needs identical
 * comparison rules on the client and the server, and two implementations of that rule drift; a
 * monotonically increasing integer has one unambiguous answer. The name is for humans.
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
