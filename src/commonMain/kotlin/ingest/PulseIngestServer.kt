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
import logic.UpdateAction
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
                val ok = try {
                    queue.enqueue(pending.payload.decodeToString(), nowMs())
                    true
                } catch (t: Throwable) {
                    log.warn("pulse ingest enqueue failed: ${t.message}")
                    false
                }
                pending.accepted.complete(ok)
            }
        }
        // Main-scope coroutine: answer update checks, which need the database.
        scope.launch {
            for (query in updateQueries) {
                val answer = try {
                    val req = IngestCodec.json.decodeFromString(UpdateCheckRequest.serializer(), query.payload.decodeToString())
                    val result = apps.checkUpdate(req.platform, req.appKey, req.packageName, req.buildNumber)
                    IngestCodec.json.encodeToString(UpdateCheckResult.serializer(), result)
                } catch (t: Throwable) {
                    // A malformed or failing check must not stop the client from starting, so it
                    // is answered with "no update" rather than an error the client has to handle.
                    log.warn("pulse update check failed: ${t.message}")
                    IngestCodec.json.encodeToString(UpdateCheckResult.serializer(), UpdateCheckResult(UpdateAction.NONE))
                }
                query.reply.complete(answer.encodeToByteArray())
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
                            PulseBizType.UPDATE_CHECK -> {
                                val reply = CompletableDeferred<ByteArray>()
                                if (queries.trySend(UpdateQuery(payload, reply)).isSuccess) reply.await()
                                else EMPTY_ACK   // overloaded: answer nothing rather than block the client
                            }
                            else -> {
                                // Acknowledge only once the batch is on the queue. Throwing here
                                // fails the client's request, which is exactly what makes it keep
                                // the batch and send it again — an ack has to mean "durable".
                                val accepted = CompletableDeferred<Boolean>()
                                val handed = channel.trySend(PendingBatch(payload, accepted)).isSuccess
                                if (!handed) throw IngestOverloadedException()
                                if (!accepted.await()) throw IngestOverloadedException()
                                EMPTY_ACK
                            }
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
    private class PendingBatch(val payload: ByteArray, val accepted: CompletableDeferred<Boolean>)

    /** Signals the client that the batch was not taken, so it keeps it and retries. */
    private class IngestOverloadedException :
        Exception("pulse ingest is overloaded; batch not accepted")

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        private val EMPTY_ACK = ByteArray(0)
        fun defaultPort(): Int = getenv("PULSE_INGEST_PORT")?.toKString()?.toIntOrNull() ?: 9600
    }
}
