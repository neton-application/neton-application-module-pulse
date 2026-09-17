package logic

import ingest.ClientConnectRequest
import ingest.ClientHeartbeatRequest
import ingest.ClientHeartbeatResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import neton.database.api.DbContext
import neton.redis.RedisClient
import kotlin.time.Duration.Companion.seconds

@Serializable
data class PulseOnlineConnection(
    val connectionId: String,
    val projectId: String,
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceType: String,
    val packageName: String? = null,
    val appVersion: String? = null,
    val buildNumber: Long = 0,
    val sdkVersion: String,
    val capabilities: List<String> = emptyList(),
    val connectedAt: Long,
    val lastHeartbeatAt: Long,
)

@Serializable
data class PulseSdkVersionCount(val sdkVersion: String, val devices: Long, val sessions: Long)

@Serializable
data class PulseConnectionSummary(
    val online: Int,
    val activeLast5Minutes: Long,
    val sdkVersions: List<PulseSdkVersionCount>,
)

/** Durable connection history plus a short-lived Redis view of clients that are online now. */
@neton.core.annotations.Logic(logger = "logic.pulse-connections")
class PulseConnectionLogic(
    private val db: DbContext,
    private val redis: RedisClient,
) {
    suspend fun connected(request: ClientConnectRequest, connectionId: String, nowMs: Long) {
        val online = request.toOnline(connectionId, nowMs)
        db.execute(
            """
            INSERT INTO pulse_client_connections
              (connection_id, project_id, device_id, installation_id, platform, device_type,
               package_name, app_version, build_number, sdk_version, capabilities,
               connected_at, last_heartbeat_at)
            VALUES
              (:connection_id, :project_id, :device_id, :installation_id, :platform, :device_type,
               :package_name, :app_version, :build_number, :sdk_version, :capabilities,
               :connected_at, :last_heartbeat_at)
            ON CONFLICT (connection_id) DO NOTHING
            """.trimIndent(),
            online.params(),
        )
        putOnline(online)
    }

    suspend fun heartbeat(
        connected: ingest.ConnectedClient,
        heartbeat: ClientHeartbeatRequest,
        nowMs: Long,
    ): ClientHeartbeatResult {
        require(heartbeat.connectionId == connected.connectionId) { "heartbeat connection ID mismatch" }
        require(heartbeat.sdkVersion.isBlank() || heartbeat.sdkVersion == connected.request.sdkVersion) {
            "heartbeat SDK version mismatch"
        }
        val online = connected.request.toOnline(connected.connectionId, nowMs).copy(
            connectedAt = online(connected.connectionId)?.connectedAt ?: nowMs,
        )
        putOnline(online)
        db.execute(
            "UPDATE pulse_client_connections SET last_heartbeat_at=:now WHERE connection_id=:id",
            mapOf("now" to nowMs, "id" to connected.connectionId),
        )
        return ClientHeartbeatResult(true, nowMs, heartbeatIntervalMs = HEARTBEAT_MS)
    }

    suspend fun disconnected(connectionId: String, nowMs: Long, reason: String = "socket_closed") {
        redis.delete(key(connectionId))
        db.execute(
            """UPDATE pulse_client_connections
               SET disconnected_at=COALESCE(disconnected_at,:now), disconnect_reason=COALESCE(disconnect_reason,:reason)
               WHERE connection_id=:id""",
            mapOf("now" to nowMs, "reason" to reason, "id" to connectionId),
        )
    }

    suspend fun listOnline(projectId: String? = null): List<PulseOnlineConnection> {
        val result = ArrayList<PulseOnlineConnection>()
        redis.collectScanKeys("$ONLINE_PREFIX*") { redisKey ->
            redis.getValue(redisKey)?.let { raw ->
                runCatching { json.decodeFromString(PulseOnlineConnection.serializer(), raw) }
                    .getOrNull()
                    ?.takeIf { projectId.isNullOrBlank() || it.projectId == projectId }
                    ?.let(result::add)
            }
        }
        return result.sortedByDescending { it.lastHeartbeatAt }
    }

    suspend fun summary(projectId: String? = null): PulseConnectionSummary {
        val online = listOnline(projectId)
        val params = HashMap<String, Any?>()
        val projectWhere = if (projectId.isNullOrBlank()) "" else {
            params["project_id"] = projectId
            "AND project_id=:project_id"
        }
        val active = db.fetchAll(
            """SELECT COUNT(DISTINCT device_id) AS count FROM pulse_events
               WHERE ts >= :since $projectWhere AND device_id IS NOT NULL AND device_id <> ''""",
            params.apply { put("since", nowMs() - 5 * 60_000) },
        ).first().long("count")
        val sdkRows = db.fetchAll(
            """SELECT sdk_version, COUNT(DISTINCT device_id) AS devices,
                      COUNT(DISTINCT session_id) AS sessions
               FROM pulse_events
               WHERE ts >= :sdk_since $projectWhere AND sdk_version IS NOT NULL AND sdk_version <> ''
               GROUP BY sdk_version ORDER BY devices DESC, sdk_version DESC""",
            HashMap(params).apply { put("sdk_since", nowMs() - 30L * 24 * 60 * 60_000) },
        )
        return PulseConnectionSummary(
            online.size,
            active,
            sdkRows.map { PulseSdkVersionCount(it.string("sdk_version"), it.long("devices"), it.long("sessions")) },
        )
    }

    private suspend fun online(connectionId: String): PulseOnlineConnection? =
        redis.getValue(key(connectionId))?.let {
            runCatching { json.decodeFromString(PulseOnlineConnection.serializer(), it) }.getOrNull()
        }

    private suspend fun putOnline(value: PulseOnlineConnection) {
        redis.set(key(value.connectionId), json.encodeToString(PulseOnlineConnection.serializer(), value), ONLINE_TTL_SECONDS.seconds)
    }

    private fun ClientConnectRequest.toOnline(connectionId: String, nowMs: Long) = PulseOnlineConnection(
        connectionId, appId, deviceId, installationId, platform, deviceType, packageName,
        appVersion, buildNumber, sdkVersion.ifBlank { "unknown" }, capabilities, nowMs, nowMs,
    )

    private fun PulseOnlineConnection.params(): Map<String, Any?> = mapOf(
        "connection_id" to connectionId, "project_id" to projectId, "device_id" to deviceId,
        "installation_id" to installationId, "platform" to platform, "device_type" to deviceType,
        "package_name" to packageName, "app_version" to appVersion, "build_number" to buildNumber,
        "sdk_version" to sdkVersion,
        "capabilities" to json.encodeToString(ListSerializer(String.serializer()), capabilities),
        "connected_at" to connectedAt, "last_heartbeat_at" to lastHeartbeatAt,
    )

    private fun key(connectionId: String) = "$ONLINE_PREFIX$connectionId"
    private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        const val HEARTBEAT_MS = 60_000L
        const val ONLINE_TTL_SECONDS = 150
        private const val ONLINE_PREFIX = "pulse:online:"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
