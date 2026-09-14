package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * A rule that says a bundled module is worth knowing about.
 *
 * Rules, not a blocklist: most third-party SDKs are not malicious, they just need to be visible.
 * The severity is what separates "noted" from "look at this now".
 */
@Serializable
@Table("pulse_module_rules")
data class PulseModuleRule(
    @Id val id: Long = 0,
    /** Module name match; `*` may lead and/or trail, e.g. `*Ads*`. */
    val pattern: String,
    val label: String = "",
    /** [AuditSeverity]. */
    val severity: String = AuditSeverity.INFO,
    val note: String? = null,
    /** Scopes the rule to one app; null applies it everywhere. */
    val appId: Long? = null,
    val status: Int = 1,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

object AuditSeverity {
    const val INFO = "info"
    const val WARN = "warn"
    const val CRITICAL = "critical"

    val ALL = listOf(INFO, WARN, CRITICAL)
    fun isValid(v: String) = v in ALL

    /** Ordering for "which of these two matters more"; unknown values sort lowest. */
    fun rank(v: String): Int = when (v) {
        CRITICAL -> 3
        WARN -> 2
        INFO -> 1
        else -> 0
    }
}

/**
 * One module that matched a rule, aggregated across every device that reported it.
 *
 * Aggregated on purpose: a module present on ten thousand devices is one alert with a count, not
 * ten thousand alerts. The latter is a list nobody reads.
 */
@Serializable
@Table("pulse_module_alerts")
data class PulseModuleAlert(
    @Id val id: Long = 0,
    val projectId: String = "",
    val moduleName: String,
    /** [AlertKind] — whether this is about what shipped or about what it did. */
    val kind: String = AlertKind.MODULE,
    /** The sensitive API for a behaviour alert; null for a structural one. */
    val api: String? = null,
    /** Total observed calls for a behaviour alert; 0 for a structural one. */
    val callCount: Long = 0,
    val ruleId: Long? = null,
    val severity: String = AuditSeverity.INFO,
    val label: String = "",
    val note: String? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    /** How often it was reported; says something about frequency. */
    val occurrences: Long = 0,
    /** How many distinct devices; says something about reach. Both matter. */
    val deviceCount: Long = 0,
    /** [AlertStatus]. */
    val status: Int = AlertStatus.OPEN,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

object AlertKind {
    /** The module ships inside the app. Determined at release, changes slowly. */
    const val MODULE = "module"
    /**
     * The module called a sensitive API at runtime. The same build can behave differently for
     * different users and paths, so this is kept separate from the structural finding rather than
     * overwriting it.
     */
    const val BEHAVIOR = "behavior"
}

object AlertStatus {
    const val OPEN = 0
    /** Seen and accepted — the module is known and allowed. */
    const val ACKNOWLEDGED = 1
    const val IGNORED = 2
}

/** One device that reported one alerting module; the basis for an honest device count. */
@Serializable
@Table("pulse_module_sightings")
data class PulseModuleSighting(
    @Id val id: Long = 0,
    val alertId: Long,
    val deviceId: String = "",
    val appVersion: String? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
)
