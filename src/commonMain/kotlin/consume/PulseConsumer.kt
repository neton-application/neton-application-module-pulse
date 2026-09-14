package consume

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ingest.IngestCodec
import logic.PulseAuditLogic
import logic.PulseCrashLogic
import logic.PulseIngestLogic
import neton.core.component.NetonLifecycle
import neton.logging.Logger
import queue.PulseQueue

/**
 * Drains the ingest queue into the database.
 *
 * neton-redis exposes non-blocking LMOVE — no blocking stream consumer — so this is a poll loop:
 * atomically claim into PROCESSING, drain up to a burst per tick, and back off when idle.
 *
 * What it does *not* do any more is drop a batch it failed to write. The previous version logged a
 * warning and moved on, which turned every transient database hiccup into permanent data loss for
 * events the client had already been told were delivered. A failed batch now goes back for another
 * attempt, and after [maxAttempts] lands in the dead letter list where an operator can see it and
 * replay it. Retries are safe because the insert is idempotent on event_id.
 *
 * The current PROCESSING recovery contract assumes one active consumer instance. Before running
 * multiple server nodes, replace startup-wide recovery with per-consumer leases (or Redis Streams)
 * so one live node cannot reclaim another live node's in-flight batch.
 */
class PulseConsumer(
    private val queue: PulseQueue,
    private val ingest: PulseIngestLogic,
    private val audit: PulseAuditLogic,
    private val crashes: PulseCrashLogic,
    private val log: Logger,
    private val idleDelayMs: Long = 200,
    private val burst: Int = 64,
    private val maxAttempts: Int = 5,
) : NetonLifecycle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun start() {
        scope.launch {
            val recovered = queue.recoverProcessing()
            if (recovered > 0) log.warn("pulse recovered $recovered unacknowledged batches")
            log.info("pulse consumer started (work=${PulseQueue.WORK} retry=${PulseQueue.RETRY} dead=${PulseQueue.DEAD})")
            while (isActive) {
                var handled = 0
                while (handled < burst && isActive) {
                    val envelope = queue.dequeue() ?: break
                    handled++
                    try {
                        val batch = IngestCodec.decodeBatch(envelope.payload.encodeToByteArray())
                        val rows = IngestCodec.toRows(batch)
                        ingest.persist(rows)
                        queue.onPersisted(envelope)
                        // Derived from the events, after they are safely written. A failure here
                        // must not cost the batch: the raw event is the record, the audit alert is
                        // a view of it that can always be rebuilt.
                        val now = nowMs()
                        try {
                            audit.evaluate(rows, now)
                        } catch (t: Throwable) {
                            log.warn("pulse audit evaluation failed: ${t.message}")
                        }
                        try {
                            crashes.aggregate(rows, now)
                        } catch (t: Throwable) {
                            log.warn("pulse crash aggregation failed: ${t.message}")
                        }
                    } catch (t: Throwable) {
                        // Both failure modes land here on purpose. A malformed payload will never
                        // parse no matter how often it is retried, but it still goes through the
                        // same attempt budget rather than being discarded, so it ends up visible in
                        // the dead letter list instead of vanishing into a log line.
                        val reason = t.message ?: t.toString()
                        queue.onFailed(envelope, reason, maxAttempts)
                        log.warn("pulse consumer failed a batch (attempt ${envelope.attempts + 1}/$maxAttempts): $reason")
                    }
                }
                if (handled == 0) delay(idleDelayMs)
            }
        }
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    override suspend fun stop() {
        scope.cancel()
        log.info("pulse consumer stopped")
    }
}
