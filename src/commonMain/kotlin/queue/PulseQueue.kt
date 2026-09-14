package queue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.redis.RedisClient

/**
 * The ingest queue: one Redis list for work, one for retries, one for what could not be written.
 *
 * Redis rather than an in-process channel because ingest and persistence must survive each other.
 * The msgtrans endpoint accepts batches at connection speed; the database writes at database speed;
 * and a restart in between must not lose what was already acknowledged to a client.
 *
 * Delivery is at-least-once. A batch is acknowledged to the client only once it is on [WORK], and
 * a batch whose write fails goes back for another attempt rather than being dropped. Duplicates
 * are therefore expected, and are absorbed by the idempotent insert on `event_id`.
 */
class PulseQueue(private val redis: RedisClient) {

    /**
     * One queued batch plus its delivery history. The payload is the raw client JSON, kept verbatim
     * so a dead-lettered batch can be inspected and replayed exactly as it arrived.
     */
    @Serializable
    data class Envelope(
        val payload: String,
        /** How many times persistence has been attempted and failed. */
        val attempts: Int = 0,
        /** epoch ms of first enqueue, so age can be reported without a separate index. */
        val firstSeenMs: Long = 0,
        /** Why the last attempt failed; only set once something has gone wrong. */
        val lastError: String? = null,
    )

    suspend fun enqueue(payload: String, nowMs: Long) {
        redis.rpush(WORK, json.encodeToString(Envelope.serializer(), Envelope(payload, 0, nowMs)))
        redis.incr(COUNTER_ENQUEUED)
    }

    /** Take the next batch, preferring retries so a failing batch cannot be starved by new traffic. */
    suspend fun dequeue(): Envelope? {
        val raw = redis.lpop(RETRY) ?: redis.lpop(WORK) ?: return null
        return decode(raw)
    }

    /** Counted per batch; per-event totals are a database question, not a queue one. */
    suspend fun onPersisted() {
        redis.incr(COUNTER_CONSUMED)
    }

    /**
     * Record a failed attempt. Below [maxAttempts] the batch goes back on the retry list; at the
     * limit it is dead-lettered instead of being retried forever, because a batch that has failed
     * this many times is failing for a reason a retry will not fix, and spinning on it would block
     * everything behind it.
     */
    suspend fun onFailed(envelope: Envelope, error: String, maxAttempts: Int) {
        val next = envelope.copy(attempts = envelope.attempts + 1, lastError = error.take(500))
        if (next.attempts >= maxAttempts) {
            redis.rpush(DEAD, json.encodeToString(Envelope.serializer(), next))
            redis.incr(COUNTER_DEAD)
        } else {
            redis.rpush(RETRY, json.encodeToString(Envelope.serializer(), next))
            redis.incr(COUNTER_FAILED)
        }
    }

    /**
     * Keep a permanently rejected submission as evidence without retrying it. Package-policy
     * failures cannot heal through retries; an operator can change the App policy and replay it.
     */
    suspend fun reject(payload: String, error: String, nowMs: Long) {
        val envelope = Envelope(payload, attempts = 1, firstSeenMs = nowMs, lastError = error.take(500))
        redis.rpush(DEAD, json.encodeToString(Envelope.serializer(), envelope))
        redis.incr(COUNTER_REJECTED)
        redis.incr(COUNTER_DEAD)
    }

    /** Move every dead-lettered batch back to the retry list. Returns how many were moved. */
    suspend fun replayDeadLetters(limit: Int = 1000): Int {
        var moved = 0
        while (moved < limit) {
            val raw = redis.lpop(DEAD) ?: break
            // Attempts reset: an operator replaying these has presumably fixed the cause, and
            // carrying the old count would send them straight back to the dead letter list.
            val envelope = decode(raw)?.copy(attempts = 0, lastError = null) ?: continue
            redis.rpush(RETRY, json.encodeToString(Envelope.serializer(), envelope))
            moved++
        }
        return moved
    }

    suspend fun purgeDeadLetters(): Int {
        var removed = 0
        while (redis.lpop(DEAD) != null) removed++
        return removed
    }

    /** Newest-first is wrong here: the oldest dead letters are the ones an operator needs to see. */
    suspend fun peekDeadLetters(limit: Int = 50): List<Envelope> =
        redis.lrange(DEAD, 0, limit - 1).mapNotNull { decode(it) }

    suspend fun depth(): QueueDepth = QueueDepth(
        // No LLEN in the client contract, so depth comes from LRANGE. Bounded on purpose: an exact
        // count of a runaway queue is not worth the memory to materialise it.
        work = redis.lrange(WORK, 0, DEPTH_PROBE - 1).size,
        retry = redis.lrange(RETRY, 0, DEPTH_PROBE - 1).size,
        dead = redis.lrange(DEAD, 0, DEPTH_PROBE - 1).size,
        truncatedAt = DEPTH_PROBE,
    )

    suspend fun counters(): QueueCounters = QueueCounters(
        enqueued = counter(COUNTER_ENQUEUED),
        consumed = counter(COUNTER_CONSUMED),
        failed = counter(COUNTER_FAILED),
        dead = counter(COUNTER_DEAD),
        rejected = counter(COUNTER_REJECTED),
    )

    private suspend fun counter(key: String): Long = redis.getValue(key)?.toLongOrNull() ?: 0L

    private fun decode(raw: String): Envelope? = try {
        json.decodeFromString(Envelope.serializer(), raw)
    } catch (_: Throwable) {
        // A payload written before the envelope existed, or by something else entirely. Treat the
        // whole string as the payload rather than discarding data we cannot parse.
        Envelope(payload = raw)
    }

    @Serializable
    data class QueueDepth(val work: Int, val retry: Int, val dead: Int, val truncatedAt: Int)

    @Serializable
    data class QueueCounters(
        val enqueued: Long,
        val consumed: Long,
        val failed: Long,
        val dead: Long,
        val rejected: Long = 0,
    )

    companion object {
        /** Batches waiting for a first attempt. */
        const val WORK = "pulse:ingest:q"
        /** Batches whose write failed and that are due another attempt. */
        const val RETRY = "pulse:ingest:retry"
        /** Batches that exhausted their attempts; kept for inspection and manual replay. */
        const val DEAD = "pulse:ingest:dead"

        private const val COUNTER_ENQUEUED = "pulse:ingest:stat:enqueued"
        private const val COUNTER_CONSUMED = "pulse:ingest:stat:consumed"
        private const val COUNTER_FAILED = "pulse:ingest:stat:failed"
        private const val COUNTER_DEAD = "pulse:ingest:stat:dead"
        private const val COUNTER_REJECTED = "pulse:ingest:stat:rejected"

        private const val DEPTH_PROBE = 5_000

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
