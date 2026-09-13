package logic

import kotlinx.serialization.Serializable
import model.EventDomain
import model.PulseEvent
import neton.database.dsl.*
import neton.logging.Logger
import table.PulseEventTable

/** name × count, for "top events" / "top crashes" lists. */
@Serializable
data class NameCount(val name: String, val count: Long)

/** kind × count, for the overview cards. */
@Serializable
data class KindCount(val kind: String, val count: Long)

/** business domain × count, matching the analytics/apm/security split (see [EventDomain]). */
@Serializable
data class DomainCount(val domain: String, val count: Long)

/**
 * Overview dashboard payload (友盟 U-App + Bugly style): headline totals, per-kind counts, the
 * busiest event names, and the most recent crashes/errors.
 */
@Serializable
data class PulseOverview(
    val totalEvents: Long,
    val sessions: Long,
    val crashes: Long,
    val errors: Long,
    val byKind: List<KindCount>,
    /** analytics / apm / security totals, the same split the V003 views expose to SQL. */
    val byDomain: List<DomainCount>,
    val topEvents: List<NameCount>,
    val recentIssues: List<PulseEvent>,
)

/**
 * Read side. Like the telemetry query logic, aggregation is a bounded near-scan + in-memory merge
 * (no GROUP BY DSL); fine at this scale, push down to SQL / a column store when it grows.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-query")
class PulseQueryLogic(private val log: Logger) {

    private companion object {
        const val SCAN_LIMIT = 5000
        val KINDS = listOf("Analytics", "Crash", "Error", "Log", "Network", "Performance", "Runtime", "Breadcrumb")
    }

    suspend fun overview(): PulseOverview {
        val recent = recentEvents()
        val kindCounts = KINDS.associateWith { countKind(it) }
        val byKind = kindCounts.map { (k, c) -> KindCount(k, c) }.filter { it.count > 0 }
        // Folded from the per-kind counts already gathered, so the split costs no extra queries.
        val byDomain = EventDomain.entries.map { d ->
            DomainCount(d.id, d.kinds.sumOf { kindCounts[it] ?: 0L })
        }
        val topEvents = recent.groupBy { it.name }
            .map { (n, v) -> NameCount(n, v.size.toLong()) }
            .sortedByDescending { it.count }.take(10)
        val recentIssues = recent.filter { it.kind == "Crash" || it.kind == "Error" }.take(20)
        return PulseOverview(
            totalEvents = PulseEventTable.count(),
            sessions = recent.map { it.sessionId }.distinct().size.toLong(),
            crashes = kindCounts["Crash"] ?: 0L,
            errors = kindCounts["Error"] ?: 0L,
            byKind = byKind,
            byDomain = byDomain,
            topEvents = topEvents,
            recentIssues = recentIssues,
        )
    }

    private suspend fun countKind(kind: String): Long =
        PulseEventTable.query { where { PulseEvent::kind eq kind } }.count()

    private suspend fun recentEvents(): List<PulseEvent> =
        PulseEventTable.query {
            orderBy(PulseEvent::id.desc())
            limitOffset(SCAN_LIMIT, 0)
        }.list()
}
