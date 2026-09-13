package model

import kotlinx.serialization.Serializable
import neton.database.annotations.*

/**
 * One ingested Pulse event, persisted. Mirrors the client's unified Event (see PulseKit): a session
 * id, a kind (Analytics / Crash / Error / Log / Network / Performance / Runtime / Breadcrumb), a
 * name, the neutral source module, and an attributes JSON blob. Kept as one table with a `kind`
 * column; the analytics vs apm split is a query concern for now (can become separate tables later).
 *
 * Data minimization: attributes hold metadata only (no request bodies, tokens, keychain content).
 */
@Serializable
@Table("pulse_events")
data class PulseEvent(
    @Id val id: Long = 0,
    /** Client event id (uuid); lets ingestion be idempotent if a batch is retried. */
    val eventId: String,
    val sessionId: String,
    /** epoch ms from the client. */
    val ts: Long = 0,
    /** Analytics / Crash / Error / Log / Network / Performance / Runtime / Breadcrumb. */
    val kind: String,
    val name: String,
    /** Neutral source module (server attributes first/system/third-party, not the client). */
    val sourceModule: String? = null,
    /** Attributes JSON string (metadata only). */
    val attributes: String = "{}",
    /**
     * Batch-level identity, flattened onto the row (see V004). Nullable because a client that
     * predates the envelope sends a bare event array and carries none of this.
     */
    val projectId: String? = null,
    val deviceId: String? = null,
    val installationId: String? = null,
    val userId: String? = null,
    @CreatedAt val createdAt: Long? = null,
)
