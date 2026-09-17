package logic

import model.PulseEvent
import neton.database.api.DbContext
import neton.logging.Logger

/**
 * Write side of Pulse ingest.
 *
 * Inserts are idempotent on `event_id`. That is not a nicety: the delivery path retries — a batch
 * whose database write failed goes back on the queue — and `pulse_events` has a unique index on
 * event_id, so a plain re-insert would fail the whole batch forever and the retry could never
 * drain. `ON CONFLICT DO NOTHING` makes replay safe, which is what lets the queue guarantee
 * at-least-once instead of at-most-once.
 *
 * Raw SQL rather than the ORM's insertBatch because that has no conflict clause. One statement with
 * all rows bound as parameters, so a batch is still a single round trip and a single transaction.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-ingest")
class PulseIngestLogic(
    private val db: DbContext,
    private val log: Logger,
) {

    /** @return rows actually written; a smaller number than [rows] means duplicates were skipped. */
    suspend fun persist(rows: List<PulseEvent>): Int {
        if (rows.isEmpty()) return 0

        val columns = "event_id, session_id, ts, kind, name, source_module, source_image_uuid, attributes, " +
            "project_id, device_id, installation_id, user_id, app_version, build_number, " +
            "sdk_version, platform, package_name, created_at"
        val params = HashMap<String, Any?>(rows.size * 18)
        val values = rows.mapIndexed { i, r ->
            params["event_id_$i"] = r.eventId
            params["session_id_$i"] = r.sessionId
            params["ts_$i"] = r.ts
            params["kind_$i"] = r.kind
            params["name_$i"] = r.name
            params["source_module_$i"] = r.sourceModule
            params["source_image_uuid_$i"] = r.sourceImageUuid
            params["attributes_$i"] = r.attributes
            params["project_id_$i"] = r.projectId
            params["device_id_$i"] = r.deviceId
            params["installation_id_$i"] = r.installationId
            params["user_id_$i"] = r.userId
            params["app_version_$i"] = r.appVersion
            params["build_number_$i"] = r.buildNumber
            params["sdk_version_$i"] = r.sdkVersion
            params["platform_$i"] = r.platform
            params["package_name_$i"] = r.packageName
            params["created_at_$i"] = r.createdAt ?: nowMs()
            "(:event_id_$i, :session_id_$i, :ts_$i, :kind_$i, :name_$i, :source_module_$i, " +
                ":source_image_uuid_$i, :attributes_$i, :project_id_$i, :device_id_$i, :installation_id_$i, " +
                ":user_id_$i, :app_version_$i, :build_number_$i, :sdk_version_$i, :platform_$i, " +
                ":package_name_$i, :created_at_$i)"
        }.joinToString(", ")

        val written = db.execute(
            "INSERT INTO pulse_events ($columns) VALUES $values ON CONFLICT (event_id) DO NOTHING",
            params,
        ).toInt()

        if (written < rows.size) {
            // Expected whenever a batch is replayed; worth a line because a persistently high
            // duplicate rate means something upstream is retrying when it should not.
            log.debug("pulse ingest wrote $written of ${rows.size} events (${rows.size - written} duplicates)")
        } else {
            log.debug("pulse ingest persisted $written events")
        }
        return written
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
