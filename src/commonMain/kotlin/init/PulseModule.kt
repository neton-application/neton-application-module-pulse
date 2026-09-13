package init

import neton.core.annotations.Module

/**
 * Pulse ingest module: receives PulseKit client event batches over a msgtrans long connection,
 * queues them into Redis, and a consumer persists them into the analytics/apm store. This is the
 * server half of the PulseKit SDK (analytics + APM, 友盟/U-APM class). No IM/PrivChat logic.
 *
 * The msgtrans server and the Redis consumer are long-running background services, so they are
 * wired in [PulseRuntimeBootstrap] (side-effecting assembly) and registered on the framework
 * lifecycle — not as @Logic (which forbids constructor side effects).
 */
@Module(dependsOn = ["system"], migrations = true)
object PulseModule {
    const val MODULE_ID = "pulse"
}
