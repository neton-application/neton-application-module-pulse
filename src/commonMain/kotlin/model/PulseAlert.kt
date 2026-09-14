package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * A condition worth interrupting someone for.
 *
 * The two guards are not optional extras. Without [minDevices] a freshly released build with three
 * testers reports 33% crash-free and fires immediately, every release, until people stop reading
 * alerts. Without [cooldownMs] an ongoing crash fires on every evaluation and does the same thing
 * faster.
 */
@Serializable
@Table("pulse_alert_rules")
data class PulseAlertRule(
    @Id val id: Long = 0,
    val name: String,
    /** [AlertType]. */
    val type: String,
    /** Scope to one app key; null watches everything. */
    val projectId: String? = null,
    /** Percentage floor for crash_free_rate; device-count floor for new_issue_impact. */
    val threshold: Double = 0.0,
    /** Below this many devices the sample is too small to mean anything. */
    val minDevices: Long = 10,
    val cooldownMs: Long = 3_600_000,
    val webhookUrl: String? = null,
    val status: Int = 1,
    val lastFiredAt: Long? = null,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

object AlertType {
    /** A build's crash-free device rate fell below the threshold. */
    const val CRASH_FREE_RATE = "crash_free_rate"
    /** A new crash issue reached the threshold number of devices. */
    const val NEW_ISSUE_IMPACT = "new_issue_impact"

    val ALL = listOf(CRASH_FREE_RATE, NEW_ISSUE_IMPACT)
    fun isValid(v: String) = v in ALL
}

/** One firing, kept so that "what did we send, and did it arrive" is answerable. */
@Serializable
@Table("pulse_alert_records")
data class PulseAlertRecord(
    @Id val id: Long = 0,
    val ruleId: Long,
    val ruleName: String = "",
    val type: String,
    val projectId: String? = null,
    val title: String = "",
    val detail: String = "",
    /** What was measured, and what it was measured against. */
    val observed: Double = 0.0,
    val threshold: Double = 0.0,
    /** [DeliveryStatus]. */
    val deliveryStatus: Int = DeliveryStatus.PENDING,
    val deliveryError: String? = null,
    val firedAt: Long = 0,
    @CreatedAt val createdAt: Long? = null,
)

object DeliveryStatus {
    const val PENDING = 0
    const val DELIVERED = 1
    const val FAILED = 2
    /** No webhook configured — the alert is recorded, there was simply nowhere to send it. */
    const val NOT_CONFIGURED = 3
}
