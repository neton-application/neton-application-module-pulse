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
import msgtrans.transport.ConnectionConfig
import neton.core.component.NetonLifecycle
import neton.logging.Logger
import logic.PulseAppLogic
import logic.PulseConnectionLogic
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
    private val connections: PulseConnectionLogic,
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
    private val clientConnectRequests = Channel<ClientConnectQuery>(capacity = 256)
    private val heartbeats = Channel<HeartbeatQuery>(capacity = 1024)
    private val disconnects = Channel<String>(capacity = 1024)
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
                pending.connectedClient.batchIdentityRejection(decoded.identity)?.let { rejection ->
                    log.warn("pulse ingest rejected connection identity mismatch: $rejection")
                    pending.reply.complete(PulseWireResponse(403, rejection, false))
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
        // CLIENT_CONNECT is the first request on a connection. Database-backed App/package policy
        // stays on the main scope; the reactor receives only the immutable accepted identity.
        scope.launch {
            for (query in clientConnectRequests) {
                val decision = try {
                    val request = IngestCodec.decodeClientConnect(query.payload)
                    val invalid = clientConnectRejection(request)
                        ?: apps.ingestRejection(request.appId, request.platform, request.packageName)
                    if (invalid != null) {
                        ClientConnectDecision(
                            IngestCodec.encodeClientConnectResponse(
                                PulseWireResponse(code = 403, msg = invalid),
                            ),
                        )
                    } else {
                        val collectKinds = apps.collectKinds(request.appId, request.platform)
                        val connectionId = newConnectionId()
                        connections.connected(request, connectionId, nowMs())
                        val connectedClient = ConnectedClient(request, connectionId)
                        ClientConnectDecision(
                            IngestCodec.encodeClientConnectResponse(
                                PulseWireResponse(
                                    code = 0,
                                    data = ClientConnectResult(
                                        connected = true,
                                        protocolVersion = request.protocolVersion,
                                        connectionId = connectionId,
                                        serverTimeMs = nowMs(),
                                        collectKinds = collectKinds,
                                    ),
                                ),
                            ),
                            connectedClient,
                        )
                    }
                } catch (t: Throwable) {
                    ClientConnectDecision(
                        IngestCodec.encodeClientConnectResponse(
                            PulseWireResponse(code = 400, msg = "invalid CLIENT_CONNECT payload"),
                        ),
                    )
                }
                query.reply.complete(decision)
            }
        }
        scope.launch {
            for (query in heartbeats) {
                val response = try {
                    val heartbeat = IngestCodec.decodeHeartbeat(query.payload)
                    val data = connections.heartbeat(query.connectedClient, heartbeat, nowMs())
                    // The policy rides the heartbeat as well as the connect, so turning a data
                    // kind off in the console takes effect within one heartbeat instead of
                    // waiting for every device to reconnect.
                    PulseWireResponse(
                        code = 0,
                        data = data.copy(
                            collectKinds = apps.collectKinds(
                                query.connectedClient.request.appId,
                                query.connectedClient.request.platform,
                            ),
                        ),
                    )
                } catch (t: IllegalArgumentException) {
                    PulseWireResponse(code = 403, msg = t.message)
                } catch (t: Throwable) {
                    log.warn("pulse heartbeat failed: ${t.message}")
                    PulseWireResponse(code = 503, msg = "heartbeat service unavailable")
                }
                query.reply.complete(IngestCodec.encodeHeartbeatResponse(response))
            }
        }
        scope.launch {
            for (connectionId in disconnects) {
                runCatching { connections.disconnected(connectionId, nowMs()) }
                    .onFailure { log.warn("pulse disconnect tracking failed: ${it.message}") }
            }
        }
        // Main-scope coroutine: answer update checks, which need the database.
        scope.launch {
            for (query in updateQueries) {
                val answer = try {
                    val req = IngestCodec.json.decodeFromString(UpdateCheckRequest.serializer(), query.payload.decodeToString())
                    val rejection = query.connectedClient.updateRequestRejection(req)
                    if (rejection != null) PulseWireResponse(code = 403, msg = rejection)
                    else {
                        // 版本名优先用请求里的,缺省回退到 connect 身份上报的 appVersion
                        // （存量客户端不发 versionName,但连上时带了 appVersion=当前版本名）。
                        val clientVersionName = req.versionName ?: query.connectedClient.request.appVersion
                        val result = apps.checkUpdate(req.platform, req.appKey, req.packageName, req.buildNumber, clientVersionName)
                        PulseWireResponse(code = 0, data = result)
                    }
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
        val ch = batches; val uq = updateQueries; val rq = clientConnectRequests
        val hb = heartbeats; val dc = disconnects; val h = host; val p = port
        w.execute(TransferMode.SAFE, { Bind(ch, uq, rq, hb, dc, h, p) }) { binding ->
            val channel = binding.batches
            val queries = binding.updateQueries
            val connectRequests = binding.clientConnectRequests
            val heartbeatRequests = binding.heartbeats
            val disconnectRequests = binding.disconnects
            val bindHost = binding.host
            val bindPort = binding.port
            neton.io.net.runReactor {
                val server = Transport.bind(
                    this,
                    bindHost,
                    bindPort,
                    ConnectionConfig(requiredFirstRequestBizType = PulseBizType.CLIENT_CONNECT),
                ) { conn ->
                    var connectedClient: ConnectedClient? = null
                    conn.onClose {
                        connectedClient?.connectionId?.let { disconnectRequests.trySend(it) }
                    }
                    conn.onRequest { payload, bizType ->
                        when (bizType) {
                            PulseBizType.CLIENT_CONNECT -> {
                                if (connectedClient != null) return@onRequest IngestCodec.encodeClientConnectResponse(
                                    PulseWireResponse(code = 409, msg = "CLIENT_CONNECT already completed"),
                                )
                                val reply = CompletableDeferred<ClientConnectDecision>()
                                if (connectRequests.trySend(ClientConnectQuery(payload, reply)).isFailure) {
                                    IngestCodec.encodeClientConnectResponse(
                                        PulseWireResponse(code = 429, msg = "CLIENT_CONNECT service overloaded"),
                                    )
                                } else {
                                    val decision = reply.await()
                                    decision.connectedClient?.let { connectedClient = it }
                                    decision.payload
                                }
                            }
                            PulseBizType.CLIENT_HEARTBEAT -> {
                                val auth = connectedClient ?: return@onRequest IngestCodec.encodeEmptyResponse(
                                    PulseWireResponse(code = 401, msg = "CLIENT_CONNECT is required before this request"),
                                )
                                val reply = CompletableDeferred<ByteArray>()
                                if (heartbeatRequests.trySend(HeartbeatQuery(payload, auth, reply)).isSuccess) reply.await()
                                else IngestCodec.encodeHeartbeatResponse(
                                    PulseWireResponse(code = 429, msg = "heartbeat service overloaded"),
                                )
                            }
                            PulseBizType.CLIENT_APP_UPDATE_CHECK -> {
                                val auth = connectedClient ?: return@onRequest IngestCodec.encodeEmptyResponse(
                                    PulseWireResponse(code = 401, msg = "CLIENT_CONNECT is required before this request"),
                                )
                                val reply = CompletableDeferred<ByteArray>()
                                if (queries.trySend(UpdateQuery(payload, auth, reply)).isSuccess) reply.await()
                                else IngestCodec.encodeUpdateResponse(
                                    PulseWireResponse(code = 429, msg = "update service overloaded"),
                                )
                            }
                            PulseBizType.CLIENT_EVENT_BATCH_UPLOAD -> {
                                val auth = connectedClient ?: return@onRequest IngestCodec.encodeEmptyResponse(
                                    PulseWireResponse(code = 401, msg = "CLIENT_CONNECT is required before this request"),
                                )
                                // Acknowledge only once the batch is on the queue. Throwing here
                                // fails the client's request, which is exactly what makes it keep
                                // the batch and send it again — an ack has to mean "durable".
                                val reply = CompletableDeferred<PulseWireResponse<Boolean>>()
                                val handed = channel.trySend(PendingBatch(payload, auth, reply)).isSuccess
                                val response = if (handed) reply.await() else
                                    PulseWireResponse(code = 429, msg = "ingest overloaded", data = false)
                                IngestCodec.encodeUploadResponse(response)
                            }
                            PulseBizType.CLIENT_CRASH_BATCH_UPLOAD,
                            PulseBizType.CLIENT_CONFIG_PULL,
                            PulseBizType.BIDIRECTIONAL_CONTROL_RPC,
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
        clientConnectRequests.close()
        heartbeats.close()
        disconnects.close()
        worker?.requestTermination(processScheduledJobs = false)
        worker = null
        log.info("pulse msgtrans ingest stopped")
    }

    private data class Bind(
        val batches: Channel<PendingBatch>,
        val updateQueries: Channel<UpdateQuery>,
        val clientConnectRequests: Channel<ClientConnectQuery>,
        val heartbeats: Channel<HeartbeatQuery>,
        val disconnects: Channel<String>,
        val host: String,
        val port: Int,
    )

    /** One update check in flight: the raw request and where to put the answer. */
    private class UpdateQuery(
        val payload: ByteArray,
        val connectedClient: ConnectedClient,
        val reply: CompletableDeferred<ByteArray>,
    )

    /** One batch in flight: the raw payload and whether it made it onto the queue. */
    private class PendingBatch(
        val payload: ByteArray,
        val connectedClient: ConnectedClient,
        val reply: CompletableDeferred<PulseWireResponse<Boolean>>,
    )

    private class ClientConnectQuery(
        val payload: ByteArray,
        val reply: CompletableDeferred<ClientConnectDecision>,
    )

    private class HeartbeatQuery(
        val payload: ByteArray,
        val connectedClient: ConnectedClient,
        val reply: CompletableDeferred<ByteArray>,
    )

    private data class ClientConnectDecision(
        val payload: ByteArray,
        val connectedClient: ConnectedClient? = null,
    )

    private fun clientConnectRejection(request: ClientConnectRequest): String? = when {
        request.protocolVersion != 2 -> "unsupported Pulse protocol version ${request.protocolVersion}"
        request.appId.isBlank() -> "missing App ID"
        request.deviceId.isBlank() -> "missing device ID"
        request.installationId.isBlank() -> "missing installation ID"
        request.platform.isBlank() -> "missing platform"
        request.deviceType.isBlank() -> "missing device type"
        else -> null
    }

    private fun newConnectionId(): String = buildString(32) {
        val alphabet = "0123456789abcdef"
        repeat(32) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        fun defaultPort(): Int = getenv("PULSE_INGEST_PORT")?.toKString()?.toIntOrNull() ?: 6000
    }
}
