package consume

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ingest.IngestCodec
import logic.PulseIngestLogic
import neton.core.component.NetonLifecycle
import neton.logging.Logger
import queue.PulseQueue

/**
 * Drains the ingest queue into the database.
 *
 * neton-redis exposes only non-blocking LPOP — no BLPOP, no streams — so this is a poll loop:
 * drain up to a burst per tick, back off briefly when there is nothing to do.
 *
 * What it does *not* do any more is drop a batch it failed to write. The previous version logged a
 * warning and moved on, which turned every transient database hiccup into permanent data loss for
 * events the client had already been told were delivered. A failed batch now goes back for another
 * attempt, and after [maxAttempts] lands in the dead letter list where an operator can see it and
 * replay it. Retries are safe because the insert is idempotent on event_id.
 *
 * A single consumer per process is enough at this scale; Redis being the queue means more
 * consumers or more nodes can be added later without changing the ingest side.
 */
class PulseConsumer(
    private val queue: PulseQueue,
    private val ingest: PulseIngestLogic,
    private val log: Logger,
    private val idleDelayMs: Long = 200,
    private val burst: Int = 64,
    private val maxAttempts: Int = 5,
) : NetonLifecycle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun start() {
        scope.launch {
            log.info("pulse consumer started (work=${PulseQueue.WORK} retry=${PulseQueue.RETRY} dead=${PulseQueue.DEAD})")
            while (isActive) {
                var handled = 0
                while (handled < burst && isActive) {
                    val envelope = queue.dequeue() ?: break
                    handled++
                    try {
                        val batch = IngestCodec.decodeBatch(envelope.payload.encodeToByteArray())
                        ingest.persist(IngestCodec.toRows(batch))
                        queue.onPersisted()
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

    override suspend fun stop() {
        scope.cancel()
        log.info("pulse consumer stopped")
    }
}
