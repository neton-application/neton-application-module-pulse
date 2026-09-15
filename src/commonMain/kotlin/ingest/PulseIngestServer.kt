@file:OptIn(ExperimentalForeignApi::class)

package ingest

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import msgtrans.transport.Transport
import neton.core.component.NetonLifecycle
import neton.logging.Logger
import logic.PulseAppLogic
import queue.PulseQueue
import logic.UpdateCheckResult
import platform.posix.getenv
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * The msgtrans long-connection ingest endpoint. Registered on the Neton lifecycle:
 * - start(): spins a dedicated worker thread running the neton-io reactor and a msgtrans server;
 *   each client request (an event batch) is handed to the main-scope enqueue coroutine over a
 *   thread-safe channel and acknowledged immediately. The enqueue coroutine RPUSHes the batch onto
 *   Redis (the client's Redis rethis client is used only on the main scope, never on the reactor
 *   thread).
 * - stop(): closes the enqueue scope and terminates the reactor worker.
 *
 * Two network stacks coexist in one process (hyper4k/Tokio for HTTP, neton-io for ingest); each
 * owns its own threads.
 */
class PulseIngestServer(
    private val queue: PulseQueue,
    private val apps: PulseAppLogic,
    private val log: Logger,
    private val host: String = "0.0.0.0",
    private val port: Int = defaultPort(),
) : NetonLifecycle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /**
     * Batches waiting to be put on Redis, each carrying the deferred the reactor is suspended on.
     *
     * The reactor cannot touch Redis — the client belongs to the main scope — so a request crosses
     * over, waits for the enqueue to actually succeed, and only then acknowledges. The previous
     * version fired the payload at a channel and acknowledged unconditionally, which meant that
     * under load a client was told its events were delivered while they were being dropped on the
     * floor: the SDK's retry-on-failure design was defeated by an ack that promised nothing.
     */
    private val batches = Channel<PendingBatch>(capacity = 1024)

    /**
     * Update checks need the database, and the database client belongs to the main scope — the
     * reactor thread must never touch it. So the reactor hands the question over with a deferred
     * attached and suspends on it; the main scope answers and completes it, which resumes the
     * reactor coroutine back on its own thread.
     */
    private val updateQueries = Channel<UpdateQuery>(capacity = 256)
    private var worker: Worker? = null

    override suspend fun start() {
        // Main-scope coroutine: put received batches on the queue and report the outcome back.
        scope.launch {
            for (pending in batches) {
                val decoded = try {
                    IngestCodec.decodeBatch(pending.payload)
                } catch (t: Throwable) {
                    log.warn("pulse ingest rejected malformed batch: ${t.message}")
                    pending.reply.complete(PulseWireResponse(400, "invalid event batch", false))
                    continue
                }
                val response = try {
                    val text = pending.payload.decodeToString()
                    val identity = decoded.identity
                    val rejected = apps.ingestRejection(
                        identity.projectId, identity.platform, identity.packageName,
                    )
                    if (rejected == null) {
                        queue.enqueue(text, nowMs())
                    } else {
                        // Permanent configuration rejection. Keep the raw batch in dead letters as
                        // evidence, acknowledge it so the client does not retry forever, and let an
                        // operator loosen the policy then replay if the package is legitimate.
                        queue.reject(text, rejected, nowMs())
                        log.warn("pulse ingest rejected: $rejected")
                    }
                    PulseWireResponse(code = 0, data = true)
                } catch (t: Throwable) {
                    log.warn("pulse ingest enqueue failed: ${t.message}")
                    PulseWireResponse(code = 503, msg = "event queue unavailable", data = false)
                }
                pending.reply.complete(response)
            }
        }
        // Main-scope coroutine: answer update checks, which need the database.
        scope.launch {
            for (query in updateQueries) {
                val answer = try {
                    val req = IngestCodec.json.decodeFromString(UpdateCheckRequest.serializer(), query.payload.decodeToString())
                    val result = apps.checkUpdate(req.platform, req.appKey, req.packageName, req.buildNumber)
                    PulseWireResponse(code = 0, data = result)
                } catch (t: Throwable) {
                    log.warn("pulse update check failed: ${t.message}")
                    // The client treats any non-zero response as "no update", so startup remains
                    // fail-open while the wire still reports the failure honestly.
                    PulseWireResponse<UpdateCheckResult>(code = 500, msg = "update check failed")
                }
                query.reply.complete(IngestCodec.encodeUpdateResponse(answer))
            }
        }
        // Reactor worker: msgtrans server. onRequest hands the payload to the channel and acks.
        val w = Worker.start(name = "pulse-msgtrans")
        worker = w
        val ch = batches; val uq = updateQueries; val h = host; val p = port
        w.execute(TransferMode.SAFE, { Bind(ch, uq, h, p) }) { (channel, queries, bindHost, bindPort) ->
            neton.io.net.runReactor {
                val server = Transport.bind(this, bindHost, bindPort) { conn ->
                    conn.onRequest { payload, bizType ->
                        when (bizType) {
                            PulseBizType.APP_UPDATE_CHECK -> {
                                val reply = CompletableDeferred<ByteArray>()
                                if (queries.trySend(UpdateQuery(payload, reply)).isSuccess) reply.await()
                                else IngestCodec.encodeUpdateResponse(
                                    PulseWireResponse(code = 429, msg = "update service overloaded"),
                                )
                            }
                            PulseBizType.EVENT_BATCH_UPLOAD -> {
                                // Acknowledge only once the batch is on the queue. Throwing here
                                // fails the client's request, which is exactly what makes it keep
                                // the batch and send it again — an ack has to mean "durable".
                                val reply = CompletableDeferred<PulseWireResponse<Boolean>>()
                                val handed = channel.trySend(PendingBatch(payload, reply)).isSuccess
                                val response = if (handed) reply.await() else
                                    PulseWireResponse(code = 429, msg = "ingest overloaded", data = false)
                                IngestCodec.encodeUploadResponse(response)
                            }
                            PulseBizType.SESSION_REGISTER,
                            PulseBizType.CRASH_BATCH_UPLOAD,
                            PulseBizType.CONFIG_PULL,
                            PulseBizType.HEARTBEAT,
                            PulseBizType.CONTROL_RPC,
                            -> IngestCodec.encodeEmptyResponse(
                                PulseWireResponse(code = 501, msg = "pulse biz_type=$bizType is allocated but not implemented"),
                            )
                            else -> IngestCodec.encodeEmptyResponse(
                                PulseWireResponse(code = 404, msg = "unsupported pulse biz_type=$bizType"),
                            )
                        }
                    }
                }
                server.acceptLoop()
            }
        }
        log.info("pulse msgtrans ingest listening on $host:$port (queue=${PulseQueue.WORK})")
    }

    override suspend fun stop() {
        scope.cancel()
        batches.close()
        updateQueries.close()
        worker?.requestTermination(processScheduledJobs = false)
        worker = null
        log.info("pulse msgtrans ingest stopped")
    }

    private data class Bind(
        val batches: Channel<PendingBatch>,
        val updateQueries: Channel<UpdateQuery>,
        val host: String,
        val port: Int,
    )

    /** One update check in flight: the raw request and where to put the answer. */
    private class UpdateQuery(val payload: ByteArray, val reply: CompletableDeferred<ByteArray>)

    /** One batch in flight: the raw payload and whether it made it onto the queue. */
    private class PendingBatch(
        val payload: ByteArray,
        val reply: CompletableDeferred<PulseWireResponse<Boolean>>,
    )

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        fun defaultPort(): Int = getenv("PULSE_INGEST_PORT")?.toKString()?.toIntOrNull() ?: 6000
    }
}
