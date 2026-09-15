package logic

import kotlinx.serialization.Serializable
import model.EventDomain
import model.PulseEvent
import neton.database.api.DbContext
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

/** One calendar day in the database reporting timezone. */
@Serializable
data class ActiveTrendPoint(
    val date: String,
    val activeUsers: Long,
    val newUsers: Long,
    val sessions: Long,
    val launches: Long,
)

/**
 * Overview dashboard payload (友盟 U-App + Bugly style): headline totals, per-kind counts, the
 * busiest event names, and the most recent crashes/errors.
 */
@Serializable
data class PulseOverview(
    val totalEvents: Long,
    /** Distinct persistent device IDs. This is the standard anonymous analytics user metric. */
    val totalUsers: Long,
    /** Distinct account IDs explicitly supplied through PulseKit identify(). */
    val identifiedUsers: Long,
    val sessions: Long,
    val todayActiveUsers: Long,
    val todayNewUsers: Long,
    val todaySessions: Long,
    val todayLaunches: Long,
    val crashes: Long,
    val errors: Long,
    val timezone: String,
    val activeTrend: List<ActiveTrendPoint>,
    val byKind: List<KindCount>,
    /** analytics / apm / security totals, the same split the V003 views expose to SQL. */
    val byDomain: List<DomainCount>,
    val topEvents: List<NameCount>,
    val recentIssues: List<PulseEvent>,
)

/**
 * Read side. Headline user metrics are always aggregated by PostgreSQL over the requested App.
 * A bounded near-scan remains only for the recent issue preview, where "latest N" is the intended
 * result rather than an approximation.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-query")
class PulseQueryLogic(
    private val db: DbContext,
    private val log: Logger,
) {

    private companion object {
        const val SCAN_LIMIT = 5000
        const val REPORTING_TIMEZONE = "Asia/Shanghai"
        val KINDS = listOf("Analytics", "Crash", "Error", "Log", "Network", "Performance", "Runtime", "Breadcrumb")
    }

    suspend fun overview(projectId: String? = null, days: Int = 7): PulseOverview {
        val appKey = projectId?.trim()?.takeIf { it.isNotEmpty() }
        val params = HashMap<String, Any?>()
        val projectWhere = if (appKey != null) {
            params["project_id"] = appKey
            "WHERE project_id = :project_id"
        } else ""

        val headline = db.fetchAll(
            """
            WITH filtered AS (
                SELECT * FROM pulse_events $projectWhere
            ), bounds AS (
                SELECT (extract(epoch FROM (
                    date_trunc('day', now() AT TIME ZONE '${REPORTING_TIMEZONE}')
                    AT TIME ZONE '${REPORTING_TIMEZONE}'
                )) * 1000)::bigint AS today_ms
            ), first_seen AS (
                SELECT device_id, MIN(ts) AS first_ts
                FROM filtered
                WHERE device_id IS NOT NULL AND device_id <> ''
                GROUP BY device_id
            )
            SELECT COUNT(*) AS total_events,
                   COUNT(DISTINCT device_id) AS total_users,
                   COUNT(DISTINCT user_id) FILTER (WHERE user_id IS NOT NULL AND user_id <> '') AS identified_users,
                   COUNT(DISTINCT session_id) AS sessions,
                   COUNT(DISTINCT device_id) FILTER (WHERE ts >= bounds.today_ms) AS today_active_users,
                   (SELECT COUNT(*) FROM first_seen WHERE first_ts >= bounds.today_ms) AS today_new_users,
                   COUNT(DISTINCT session_id) FILTER (WHERE ts >= bounds.today_ms) AS today_sessions,
                   COUNT(*) FILTER (WHERE ts >= bounds.today_ms AND name = 'app_launch') AS today_launches,
                   COUNT(*) FILTER (WHERE kind = 'Crash') AS crashes,
                   COUNT(*) FILTER (WHERE kind = 'Error') AS errors,
                   '${REPORTING_TIMEZONE}' AS timezone
            FROM filtered CROSS JOIN bounds
            GROUP BY bounds.today_ms
            """.trimIndent(),
            params,
        ).first()

        val kindCounts = KINDS.associateWith { 0L }.toMutableMap()
        db.fetchAll(
            "SELECT kind, COUNT(*) AS count FROM pulse_events $projectWhere GROUP BY kind",
            params,
        ).forEach { kindCounts[it.string("kind")] = it.long("count") }
        val byKind = kindCounts.map { (k, c) -> KindCount(k, c) }.filter { it.count > 0 }
        // Folded from the per-kind counts already gathered, so the split costs no extra queries.
        val byDomain = EventDomain.entries.map { d ->
            DomainCount(d.id, d.kinds.sumOf { kindCounts[it] ?: 0L })
        }

        val daysSafe = days.coerceIn(1, 30)
        val trendParams = HashMap(params)
        trendParams["days_minus_one"] = daysSafe - 1
        val activeTrend = db.fetchAll(
            """
            WITH filtered AS (
                SELECT * FROM pulse_events $projectWhere
            ), first_seen AS (
                SELECT device_id, MIN(ts) AS first_ts
                FROM filtered
                WHERE device_id IS NOT NULL AND device_id <> ''
                GROUP BY device_id
            ), days AS (
                SELECT generate_series(
                    date_trunc('day', now() AT TIME ZONE '${REPORTING_TIMEZONE}') - (:days_minus_one * interval '1 day'),
                    date_trunc('day', now() AT TIME ZONE '${REPORTING_TIMEZONE}'), interval '1 day'
                ) AS day
            )
            SELECT to_char(day, 'YYYY-MM-DD') AS date,
                   (SELECT COUNT(DISTINCT device_id) FROM filtered
                    WHERE ts >= (extract(epoch FROM (day AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint
                      AND ts <  (extract(epoch FROM ((day + interval '1 day') AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint) AS active_users,
                   (SELECT COUNT(*) FROM first_seen
                    WHERE first_ts >= (extract(epoch FROM (day AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint
                      AND first_ts <  (extract(epoch FROM ((day + interval '1 day') AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint) AS new_users,
                   (SELECT COUNT(DISTINCT session_id) FROM filtered
                    WHERE ts >= (extract(epoch FROM (day AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint
                      AND ts <  (extract(epoch FROM ((day + interval '1 day') AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint) AS sessions,
                   (SELECT COUNT(*) FROM filtered
                    WHERE name = 'app_launch'
                      AND ts >= (extract(epoch FROM (day AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint
                      AND ts <  (extract(epoch FROM ((day + interval '1 day') AT TIME ZONE '${REPORTING_TIMEZONE}')) * 1000)::bigint) AS launches
            FROM days ORDER BY day
            """.trimIndent(),
            trendParams,
        ).map {
            ActiveTrendPoint(
                date = it.string("date"),
                activeUsers = it.long("active_users"),
                newUsers = it.long("new_users"),
                sessions = it.long("sessions"),
                launches = it.long("launches"),
            )
        }

        val topParams = HashMap(params)
        topParams["days"] = daysSafe
        val topFilter = if (appKey != null) "project_id = :project_id AND" else ""
        val topEvents = db.fetchAll(
            """
            SELECT name, COUNT(*) AS count
            FROM pulse_events
            WHERE $topFilter kind = 'Analytics'
              AND ts >= (extract(epoch FROM (
                    (date_trunc('day', now() AT TIME ZONE '${REPORTING_TIMEZONE}') - ((:days - 1) * interval '1 day'))
                    AT TIME ZONE '${REPORTING_TIMEZONE}'
                  )) * 1000)::bigint
            GROUP BY name ORDER BY count DESC LIMIT 10
            """.trimIndent(),
            topParams,
        ).map { NameCount(it.string("name"), it.long("count")) }

        val recent = recentEvents(appKey)
        val recentIssues = recent.filter { it.kind == "Crash" || it.kind == "Error" }.take(20)
        return PulseOverview(
            totalEvents = headline.long("total_events"),
            totalUsers = headline.long("total_users"),
            identifiedUsers = headline.long("identified_users"),
            sessions = headline.long("sessions"),
            todayActiveUsers = headline.long("today_active_users"),
            todayNewUsers = headline.long("today_new_users"),
            todaySessions = headline.long("today_sessions"),
            todayLaunches = headline.long("today_launches"),
            crashes = headline.long("crashes"),
            errors = headline.long("errors"),
            timezone = headline.string("timezone"),
            activeTrend = activeTrend,
            byKind = byKind,
            byDomain = byDomain,
            topEvents = topEvents,
            recentIssues = recentIssues,
        )
    }

    private suspend fun recentEvents(projectId: String?): List<PulseEvent> =
        PulseEventTable.query {
            if (projectId != null) where { PulseEvent::projectId eq projectId }
            orderBy(PulseEvent::id.desc())
            limitOffset(SCAN_LIMIT, 0)
        }.list()
}
