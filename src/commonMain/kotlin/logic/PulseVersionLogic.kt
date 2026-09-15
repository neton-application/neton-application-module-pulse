package logic

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import neton.database.api.DbContext
import neton.logging.Logger

/**
 * How one build is doing, and how it compares to the one before it.
 *
 * The headline number is the crash-free **device** rate, not the crash count. A raw count rises
 * simply because a build reached more users, so it cannot answer "is this release worse than the
 * last one"; a rate can. Devices rather than sessions because a user who crashes on every launch
 * is one unhappy user, and counting their sessions would make one person look like a crisis —
 * both are reported, because the gap between them is itself informative.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VersionStats(
    val buildNumber: Long,
    val appVersion: String? = null,
    val devices: Long = 0,
    val sessions: Long = 0,
    val events: Long = 0,
    val crashes: Long = 0,
    val errors: Long = 0,
    /** Distinct devices that saw at least one crash. */
    val crashedDevices: Long = 0,
    /** Distinct sessions that saw at least one crash. */
    val crashedSessions: Long = 0,
    /** Issues whose very first sighting was on this build — what this release introduced. */
    val newIssues: Long = 0,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    /**
     * Percentage of devices that never crashed — the number a release decision is made on.
     *
     * A constructor property with a derived default rather than a computed `get()`, because
     * kotlinx.serialization only writes constructor properties: as a computed property this, the
     * headline metric of the whole page, silently never reached the client. The default keeps it
     * in step with the counts it is derived from.
     *
     * [EncodeDefault] because a value that equals its default is otherwise omitted unless the
     * encoder happens to be configured with encodeDefaults — which the framework's is, but relying
     * on that would mean this field silently disappears the day that setting changes.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val crashFreeDeviceRate: Double = if (devices == 0L) 100.0 else (devices - crashedDevices) * 100.0 / devices,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val crashFreeSessionRate: Double = if (sessions == 0L) 100.0 else (sessions - crashedSessions) * 100.0 / sessions,
)

/** Two builds side by side, with the deltas already computed so the console does no arithmetic. */
@Serializable
data class VersionComparison(
    val current: VersionStats,
    val baseline: VersionStats? = null,
    /** Percentage points, current minus baseline. Negative means the new build is worse. */
    val crashFreeDeviceDelta: Double = 0.0,
    val crashFreeSessionDelta: Double = 0.0,
    /** Issues present on the current build that were never seen on the baseline. */
    val regressions: List<IssueOnBuild> = emptyList(),
)

/** One crash issue as it appears on a particular build. */
@Serializable
data class IssueOnBuild(
    val issueId: Long,
    val title: String,
    val message: String,
    val fingerprint: String,
    val devices: Long,
    val events: Long,
)

/**
 * Version-dimension reporting.
 *
 * All aggregation is pushed into SQL. The other read paths in this module scan a bounded window
 * and merge in memory, which is fine for a "latest N events" list; it is not fine here, because a
 * crash-free rate computed from a window is simply wrong — it would silently answer for whatever
 * slice happened to be scanned rather than for the release.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-version")
class PulseVersionLogic(
    private val db: DbContext,
    private val log: Logger,
) {

    /** Every build that has reported anything, newest first. */
    suspend fun builds(projectId: String?, days: Int? = null): List<VersionStats> {
        val where = StringBuilder("build_number IS NOT NULL")
        val params = HashMap<String, Any?>()
        if (!projectId.isNullOrBlank()) {
            where.append(" AND project_id = :project_id")
            params["project_id"] = projectId
        }
        val sinceMs = days?.coerceIn(1, 30)?.let {
            kotlin.time.Clock.System.now().toEpochMilliseconds() - it * 86_400_000L
        }
        if (sinceMs != null) {
            where.append(" AND ts >= :since_ms")
            params["since_ms"] = sinceMs
        }

        val rows = db.fetchAll(
            """
            SELECT build_number,
                   MAX(app_version)                                    AS app_version,
                   COUNT(DISTINCT device_id)                           AS devices,
                   COUNT(DISTINCT session_id)                          AS sessions,
                   COUNT(*)                                            AS events,
                   COUNT(*) FILTER (WHERE kind = 'Crash')              AS crashes,
                   COUNT(*) FILTER (WHERE kind = 'Error')              AS errors,
                   COUNT(DISTINCT device_id)  FILTER (WHERE kind = 'Crash') AS crashed_devices,
                   COUNT(DISTINCT session_id) FILTER (WHERE kind = 'Crash') AS crashed_sessions,
                   MIN(ts) AS first_seen, MAX(ts) AS last_seen
            FROM pulse_events
            WHERE $where
            GROUP BY build_number
            ORDER BY build_number DESC
            """.trimIndent(),
            params,
        )

        val newPerBuild = newIssueCounts(projectId, sinceMs)
        return rows.map { r ->
            val build = r.long("build_number")
            VersionStats(
                buildNumber = build,
                appVersion = r.stringOrNull("app_version"),
                devices = r.long("devices"),
                sessions = r.long("sessions"),
                events = r.long("events"),
                crashes = r.long("crashes"),
                errors = r.long("errors"),
                crashedDevices = r.long("crashed_devices"),
                crashedSessions = r.long("crashed_sessions"),
                newIssues = newPerBuild[build] ?: 0L,
                firstSeenAt = r.longOrNull("first_seen") ?: 0,
                lastSeenAt = r.longOrNull("last_seen") ?: 0,
            )
        }
    }

    /**
     * Compare a build against another, defaulting to the build immediately before it.
     *
     * "The one before" means the next lower build number that actually reported, not
     * buildNumber - 1: builds that were never released have no data and comparing against an empty
     * one would report a 100% crash-free baseline and a catastrophic-looking regression.
     */
    suspend fun compare(
        projectId: String?,
        buildNumber: Long,
        baselineBuild: Long?,
        days: Int? = null,
    ): VersionComparison {
        val all = builds(projectId, days)
        val current = all.firstOrNull { it.buildNumber == buildNumber }
            ?: VersionStats(buildNumber = buildNumber)
        val baseline = when {
            baselineBuild != null -> all.firstOrNull { it.buildNumber == baselineBuild }
            else -> all.filter { it.buildNumber < buildNumber }.maxByOrNull { it.buildNumber }
        }

        return VersionComparison(
            current = current,
            baseline = baseline,
            crashFreeDeviceDelta = baseline?.let { current.crashFreeDeviceRate - it.crashFreeDeviceRate } ?: 0.0,
            crashFreeSessionDelta = baseline?.let { current.crashFreeSessionRate - it.crashFreeSessionRate } ?: 0.0,
            regressions = if (baseline == null) emptyList()
            else regressions(projectId, buildNumber, baseline.buildNumber, days),
        )
    }

    /**
     * Crash issues seen on [buildNumber] but never on [baselineBuild] — what this release broke.
     *
     * Matched through the crash sightings table, which records the build each device hit an issue
     * on. Going via the events would require re-deriving fingerprints on read.
     */
    suspend fun regressions(
        projectId: String?,
        buildNumber: Long,
        baselineBuild: Long,
        days: Int? = null,
    ): List<IssueOnBuild> {
        val params = HashMap<String, Any?>()
        params["build"] = buildNumber
        params["baseline"] = baselineBuild
        val projectFilter = if (!projectId.isNullOrBlank()) {
            params["project_id"] = projectId
            "AND i.project_id = :project_id"
        } else ""
        val sinceFilter = days?.coerceIn(1, 30)?.let {
            params["since_ms"] = kotlin.time.Clock.System.now().toEpochMilliseconds() - it * 86_400_000L
            "AND s.last_seen_at >= :since_ms"
        } ?: ""

        val rows = db.fetchAll(
            """
            SELECT i.id, i.title, i.message, i.fingerprint,
                   COUNT(DISTINCT s.device_id) AS devices,
                   i.event_count                AS events
            FROM pulse_crash_issues i
            JOIN pulse_crash_sightings s ON s.issue_id = i.id AND s.build_number = :build
            WHERE NOT EXISTS (
                SELECT 1 FROM pulse_crash_sightings b
                WHERE b.issue_id = i.id AND b.build_number = :baseline
            )
            $projectFilter
            $sinceFilter
            GROUP BY i.id, i.title, i.message, i.fingerprint, i.event_count
            ORDER BY devices DESC
            """.trimIndent(),
            params,
        )
        return rows.map {
            IssueOnBuild(
                issueId = it.long("id"),
                title = it.string("title"),
                message = it.stringOrNull("message") ?: "",
                fingerprint = it.string("fingerprint"),
                devices = it.long("devices"),
                events = it.long("events"),
            )
        }
    }

    /** Per build, how many issues were first seen there. */
    private suspend fun newIssueCounts(projectId: String?, sinceMs: Long?): Map<Long, Long> {
        val params = HashMap<String, Any?>()
        val filters = mutableListOf<String>()
        if (!projectId.isNullOrBlank()) {
            params["project_id"] = projectId
            filters += "i.project_id = :project_id"
        }
        if (sinceMs != null) {
            params["since_ms"] = sinceMs
            filters += "s.first_seen_at >= :since_ms"
        }
        val filterSql = if (filters.isEmpty()) "" else "WHERE ${filters.joinToString(" AND ")}"

        // The build an issue was first seen on is the lowest build among its sightings; an issue
        // that pre-dates build reporting has none and is counted against no build.
        val rows = db.fetchAll(
            """
            SELECT first_build, COUNT(*) AS issues FROM (
                SELECT s.issue_id, MIN(s.build_number) AS first_build
                FROM pulse_crash_sightings s
                JOIN pulse_crash_issues i ON i.id = s.issue_id
                $filterSql
                GROUP BY s.issue_id
            ) t
            WHERE first_build IS NOT NULL
            GROUP BY first_build
            """.trimIndent(),
            params,
        )
        return rows.associate { it.long("first_build") to it.long("issues") }
    }
}
