package controller.open.pulse

import ingest.IngestCodec
import kotlinx.serialization.Serializable
import logic.PulseAppLogic
import logic.UpdateCheckResult
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Post
import neton.core.annotations.RateLimit
import neton.core.annotations.RateLimitScope
import neton.logging.Logger
import neton.core.http.BadRequestException
import queue.PulseQueue

/**
 * HTTP ingest, for clients that cannot hold a msgtrans long connection.
 *
 * The native SDK uses msgtrans and should keep doing so: one connection, request-confirmed
 * delivery, natural backpressure. A browser has none of that, and Web is one of the platforms the
 * app registry supports, so the same two operations are also reachable over plain HTTP.
 *
 * The package is `controller.open.*`, which puts this in the `open` route group at `/platform/...`
 * rather than under `/admin` or `/app` — see routing.conf. It is deliberately anonymous: a
 * telemetry endpoint that required a session could not report the crash that stopped the user from
 * getting one. It is rate limited by IP instead.
 *
 * The wire body is identical to the msgtrans payload, so both paths decode with the same codec and
 * land in the same queue; there is one ingest format, not a native one and a web one.
 */
@Controller("/pulse")
class PulseIngestHttpController(
    private val queue: PulseQueue,
    private val apps: PulseAppLogic,
    private val log: Logger,
) {

    @Serializable
    data class IngestResponse(val accepted: Int)

    /**
     * Accept one event batch.
     *
     * Answers only after the batch is on the queue, the same contract as the msgtrans path: a
     * client that gets a 2xx may drop its copy, so the response must not outrun durability.
     */
    @Post("/ingest")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 120, scope = RateLimitScope.IP,
        message = "too many ingest requests")
    suspend fun ingest(@Body batch: ingest.IngestBatch): IngestResponse {
        // Bound as the typed batch rather than a raw string: the framework decodes and validates
        // it, so a body that would only fail later in the consumer is rejected here with a 4xx
        // instead of being queued and quietly dead-lettered.
        if (batch.events.isEmpty()) return IngestResponse(0)
        apps.ingestRejection(
            batch.identity.projectId, batch.identity.platform, batch.identity.packageName,
        )?.let { throw BadRequestException(it) }
        // Re-encoded rather than forwarding the original bytes, so exactly one shape reaches the
        // queue no matter which entry point produced it.
        queue.enqueue(IngestCodec.json.encodeToString(ingest.IngestBatch.serializer(), batch), nowMs())
        return IngestResponse(batch.events.size)
    }

    /** The same update check the native SDK makes over msgtrans. */
    @Post("/update-check")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 60, scope = RateLimitScope.IP,
        message = "too many update checks")
    suspend fun updateCheck(@Body request: ingest.UpdateCheckRequest): UpdateCheckResult =
        try {
            apps.checkUpdate(request.platform, request.appKey, request.packageName, request.buildNumber, request.versionName)
        } catch (t: Throwable) {
            // Fails open, exactly as the msgtrans path does: an update check must never be the
            // reason a client refuses to start.
            log.warn("pulse http update check failed: ${t.message}")
            UpdateCheckResult(logic.UpdateAction.NONE)
        }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
