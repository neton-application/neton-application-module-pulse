package init

import consume.PulseConsumer
import ingest.PulseIngestServer
import logic.PulseAppLogic
import logic.PulseAuditLogic
import logic.PulseCrashLogic
import logic.PulseIngestLogic
import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import neton.redis.RedisClient
import queue.PulseQueue

/**
 * Side-effecting assembly for the Pulse ingest module (KSP calls this after @Logic assembly, before
 * routes). Builds the msgtrans ingest server and the Redis consumer, pulling RedisClient / the
 * ingest logic / a logger from the context, and registers both on the framework lifecycle so the
 * engine starts them (before the HTTP adapter) and stops them in reverse on shutdown.
 */
object PulseRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val loggerFactory = ctx.get(LoggerFactory::class)
        val redis = ctx.get(RedisClient::class)
        val ingestLogic = ctx.get(PulseIngestLogic::class)
        val appLogic = ctx.get(PulseAppLogic::class)
        val auditLogic = ctx.get(PulseAuditLogic::class)
        val crashLogic = ctx.get(PulseCrashLogic::class)

        // One queue instance shared by both ends, and bound into the context so the admin
        // controller can report on and operate the very same lists the pipeline uses.
        val queue = PulseQueue(redis)
        ctx.bind(PulseQueue::class, queue)

        val server = PulseIngestServer(queue, appLogic, loggerFactory.get("pulse.ingest-server"))
        val consumer = PulseConsumer(queue, ingestLogic, auditLogic, crashLogic, loggerFactory.get("pulse.consumer"))

        ctx.lifecycle.register("pulse-msgtrans-ingest", server)
        ctx.lifecycle.register("pulse-consumer", consumer)
    }
}
