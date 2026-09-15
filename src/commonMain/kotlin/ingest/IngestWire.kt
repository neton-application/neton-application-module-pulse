package ingest

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import model.PulseEvent

/**
 * Server-side mirror of the PulseKit client Event JSON (pulse-core Event). Kept independent of the
 * client SDK: the wire is the contract, not a shared class. A batch is a JSON array of these.
 */
@Serializable
data class IngestEvent(
    val id: String,
    val timestamp: Long,
    val sessionId: String,
    val kind: String,
    val name: String,
    val source: IngestSource = IngestSource(),
    val attributes: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class IngestSource(val module: String? = null, val imageUuid: String? = null)

/**
 * The batch envelope the client sends: one identity for the whole batch plus its events. Identity
 * is per batch rather than per event because it is identical across the batch by construction.
 */
@Serializable
data class IngestBatch(
    val identity: IngestIdentity = IngestIdentity(),
    val events: List<IngestEvent> = emptyList(),
    /** Stable client outbox identity; optional for SDKs released before durable batching. */
    val batchId: String? = null,
)

@Serializable
data class IngestIdentity(
    val projectId: String? = null,
    val installationId: String? = null,
    val deviceId: String? = null,
    val userId: String? = null,
    val appVersion: String? = null,
    val buildNumber: Long = 0,
    val platform: String? = null,
    val packageName: String? = null,
)

/**
 * Response envelope for every Pulse msgtrans request. This belongs to the Pulse application
 * protocol; msgtrans itself deliberately transports opaque bytes.
 */
@Serializable
data class PulseWireResponse<T>(
    val code: Int = 0,
    val msg: String? = null,
    val data: T? = null,
)

/**
 * Update response keeps the old root-level fields during the beta migration. Old SDKs read those
 * fields; new SDKs read [code]/[msg]/[data]. Every response still has the common envelope.
 */
@Serializable
data class PulseUpdateResponse(
    val code: Int = 0,
    val msg: String? = null,
    val data: logic.UpdateCheckResult? = null,
    val action: String = data?.action ?: logic.UpdateAction.NONE,
    val latestVersionName: String? = data?.latestVersionName,
    val latestBuildNumber: Long? = data?.latestBuildNumber,
    val releaseNotes: String = data?.releaseNotes ?: "",
    val downloadUrl: String? = data?.downloadUrl,
)

/** Decodes a batch and maps it to persistable rows. */
object IngestCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(IngestEvent.serializer())

    /**
     * Accepts both wire shapes: the current envelope `{identity, events}` and the bare event array
     * older clients send. A deployed SDK cannot be upgraded in lockstep with the server, so the
     * server reads both and simply leaves the identity columns null for the older one.
     *
     * Which shape it is comes from the first non-whitespace byte — `{` or `[`. Cheaper and less
     * ambiguous than decoding twice and catching.
     */
    fun decodeBatch(bytes: ByteArray): IngestBatch {
        val text = bytes.decodeToString()
        val first = text.firstOrNull { !it.isWhitespace() }
        return if (first == '[') IngestBatch(events = json.decodeFromString(listSer, text))
        else json.decodeFromString(IngestBatch.serializer(), text)
    }

    fun toRows(batch: IngestBatch): List<PulseEvent> = batch.events.map { e ->
        PulseEvent(
            eventId = e.id,
            sessionId = e.sessionId,
            ts = e.timestamp,
            kind = e.kind,
            name = e.name,
            sourceModule = e.source.module,
            sourceImageUuid = e.source.imageUuid
                ?.lowercase()
                ?.takeIf(IMAGE_UUID::matches),
            attributes = json.encodeToString(JsonObject.serializer(), JsonObject(e.attributes)),
            projectId = batch.identity.projectId,
            deviceId = batch.identity.deviceId,
            installationId = batch.identity.installationId,
            userId = batch.identity.userId,
            appVersion = batch.identity.appVersion,
            buildNumber = batch.identity.buildNumber.takeIf { it > 0 },
            platform = batch.identity.platform,
            packageName = batch.identity.packageName,
        )
    }

    fun encodeUploadResponse(response: PulseWireResponse<Boolean>): ByteArray =
        json.encodeToString(
            PulseWireResponse.serializer(Boolean.serializer()),
            response,
        ).encodeToByteArray()

    fun encodeUpdateResponse(response: PulseWireResponse<logic.UpdateCheckResult>): ByteArray =
        json.encodeToString(
            PulseUpdateResponse.serializer(),
            PulseUpdateResponse(response.code, response.msg, response.data),
        ).encodeToByteArray()

    fun encodeEmptyResponse(response: PulseWireResponse<Unit>): ByteArray =
        json.encodeToString(
            PulseWireResponse.serializer(Unit.serializer()),
            response,
        ).encodeToByteArray()

    private val IMAGE_UUID = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
}

/**
 * Startup update check, sent over the same msgtrans connection as ingest but under
 * [PulseBizType.APP_UPDATE_CHECK]. The client knows its platform and package name from the build itself and its
 * app key from SDK configuration; the server needs whichever of the two it can get.
 */
@Serializable
data class UpdateCheckRequest(
    val platform: String,
    val appKey: String? = null,
    val packageName: String? = null,
    val buildNumber: Long = 0,
)

/** Business types on the ingest connection. */
object PulseBizType {
    /** Upload one identity envelope containing an ordered event array. */
    const val EVENT_BATCH_UPLOAD = 1
    /** A startup update check. */
    const val APP_UPDATE_CHECK = 2
}
