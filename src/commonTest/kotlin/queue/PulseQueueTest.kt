package queue

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue is where events can be lost, so these cover the paths that decide whether they are:
 * retry ordering, the attempt budget, dead-lettering, and replay.
 */
class PulseQueueTest {

    private fun queue() = FakeRedis().let { it to PulseQueue(it) }

    @Test
    fun anEnqueuedBatchComesBackOut() = runTest {
        val (_, q) = queue()
        q.enqueue("""{"events":[]}""", nowMs = 1000)

        val got = q.dequeue()
        assertEquals("""{"events":[]}""", got?.payload)
        assertEquals(0, got?.attempts)
        assertEquals(1000, got?.firstSeenMs)
        assertNull(q.dequeue(), "the queue should be empty after one dequeue")
    }

    @Test
    fun retriesAreServedBeforeNewWork() = runTest {
        val (_, q) = queue()
        q.enqueue("fresh", nowMs = 1)
        q.onFailed(PulseQueue.Envelope("stuck", attempts = 0), "boom", maxAttempts = 5)

        // A batch that already failed must not be starved by a steady stream of new traffic, so
        // the retry list is drained first.
        assertEquals("stuck", q.dequeue()?.payload)
        assertEquals("fresh", q.dequeue()?.payload)
    }

    @Test
    fun aBatchIsDeadLetteredOnlyAfterItsAttemptsAreSpent() = runTest {
        val (redis, q) = queue()
        var envelope = PulseQueue.Envelope("bad", attempts = 0, firstSeenMs = 7)

        repeat(4) {
            q.onFailed(envelope, "parse error", maxAttempts = 5)
            envelope = q.dequeue()!!
        }
        // Four failures so far: still being retried, nothing given up on.
        assertEquals(4, envelope.attempts)
        assertEquals(0, redis.lists[PulseQueue.DEAD]?.size ?: 0)

        q.onFailed(envelope, "parse error", maxAttempts = 5)
        val dead = q.peekDeadLetters()
        assertEquals(1, dead.size)
        assertEquals(5, dead[0].attempts)
        assertEquals("bad", dead[0].payload)
        // The reason is kept, because a dead letter with no explanation is not actionable.
        assertEquals("parse error", dead[0].lastError)
        // And it is no longer in line for another attempt.
        assertNull(q.dequeue())
    }

    @Test
    fun replayPutsDeadLettersBackWithAFreshBudget() = runTest {
        val (_, q) = queue()
        var envelope = PulseQueue.Envelope("payload", attempts = 0)
        repeat(5) { q.onFailed(envelope, "db down", maxAttempts = 5); envelope = q.dequeue() ?: envelope }
        assertEquals(1, q.peekDeadLetters().size)

        assertEquals(1, q.replayDeadLetters())
        assertEquals(0, q.peekDeadLetters().size)

        val back = q.dequeue()
        assertEquals("payload", back?.payload)
        // Attempts reset: an operator replaying these has fixed the cause, and carrying the old
        // count would send the batch straight back to the dead letter list on one more hiccup.
        assertEquals(0, back?.attempts)
        assertNull(back?.lastError)
    }

    @Test
    fun purgeRemovesDeadLettersAndReportsHowMany() = runTest {
        val (_, q) = queue()
        repeat(3) { i ->
            q.onFailed(PulseQueue.Envelope("p$i", attempts = 4), "boom", maxAttempts = 5)
        }
        assertEquals(3, q.peekDeadLetters().size)
        assertEquals(3, q.purgeDeadLetters())
        assertEquals(0, q.peekDeadLetters().size)
    }

    @Test
    fun countersFollowWhatActuallyHappened() = runTest {
        val (_, q) = queue()
        q.enqueue("a", nowMs = 1)
        q.enqueue("b", nowMs = 2)
        q.dequeue(); q.onPersisted()
        val second = q.dequeue()!!
        q.onFailed(second, "boom", maxAttempts = 2)     // one retry
        q.onFailed(q.dequeue()!!, "boom", maxAttempts = 2)  // now dead

        val c = q.counters()
        assertEquals(2, c.enqueued)
        assertEquals(1, c.consumed)
        assertEquals(1, c.failed)
        assertEquals(1, c.dead)
    }

    @Test
    fun depthReportsEachListSeparately() = runTest {
        val (_, q) = queue()
        q.enqueue("w1", nowMs = 1)
        q.enqueue("w2", nowMs = 1)
        q.onFailed(PulseQueue.Envelope("r", attempts = 0), "x", maxAttempts = 5)
        q.onFailed(PulseQueue.Envelope("d", attempts = 9), "x", maxAttempts = 5)

        val depth = q.depth()
        assertEquals(2, depth.work)
        assertEquals(1, depth.retry)
        assertEquals(1, depth.dead)
    }

    @Test
    fun aPayloadWrittenBeforeEnvelopesExistedIsStillReadable() = runTest {
        val (redis, q) = queue()
        // A batch left on the queue by the previous version was the bare JSON, with no envelope
        // around it. Discarding what we cannot parse would lose exactly the data an upgrade is
        // supposed to preserve, so it is treated as a payload with no history.
        redis.lists.getOrPut(PulseQueue.WORK) { mutableListOf() }.add("""{"events":[{"id":"e1"}]}""")

        val got = q.dequeue()
        assertEquals("""{"events":[{"id":"e1"}]}""", got?.payload)
        assertEquals(0, got?.attempts)
    }

    @Test
    fun replayIsBoundedByItsLimit() = runTest {
        val (_, q) = queue()
        repeat(5) { i -> q.onFailed(PulseQueue.Envelope("p$i", attempts = 4), "x", maxAttempts = 5) }
        assertEquals(2, q.replayDeadLetters(limit = 2))
        assertEquals(3, q.peekDeadLetters().size)
        assertTrue(q.dequeue() != null)
    }
}
