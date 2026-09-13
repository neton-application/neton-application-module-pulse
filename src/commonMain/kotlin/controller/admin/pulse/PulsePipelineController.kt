package controller.admin.pulse

import kotlinx.serialization.Serializable
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Query
import queue.PulseQueue

/**
 * Ingest pipeline operations.
 *
 * The pipeline is the part of this module that can silently lose data, so it is the part that needs
 * to be visible: how deep the queues are, how many batches have been written versus retried versus
 * given up on, and what exactly the failures were. Dead letters are kept precisely so that a bad
 * deploy or a database outage is recoverable by hand rather than only apologised for.
 */
@Controller("/pulse/pipeline")
class PulsePipelineController(private val queue: PulseQueue) {

    @Serializable
    data class PipelineStatus(
        val depth: PulseQueue.QueueDepth,
        val counters: PulseQueue.QueueCounters,
        /** Convenience for the console: something needs attention. */
        val hasDeadLetters: Boolean,
    )

    @Get("/status")
    @Permission("pulse:pipeline:view")
    suspend fun status(): PipelineStatus {
        val depth = queue.depth()
        return PipelineStatus(depth, queue.counters(), depth.dead > 0)
    }

    @Get("/dead-letters")
    @Permission("pulse:pipeline:view")
    suspend fun deadLetters(@Query limit: Int = 50): List<PulseQueue.Envelope> =
        queue.peekDeadLetters(limit.coerceIn(1, 500))

    /**
     * Put dead letters back in line. Separate permission from viewing: replaying is a write to the
     * ingest path, and doing it before the underlying cause is fixed just refills the list.
     */
    @Post("/replay")
    @Permission("pulse:pipeline:operate")
    suspend fun replay(@Query limit: Int = 1000): Int = queue.replayDeadLetters(limit.coerceIn(1, 10_000))

    /** Discard dead letters. Irreversible, hence its own call rather than a flag on replay. */
    @Post("/purge")
    @Permission("pulse:pipeline:operate")
    suspend fun purge(): Int = queue.purgeDeadLetters()
}
