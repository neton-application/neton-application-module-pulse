package job

import logic.PulseAlertLogic
import neton.jobs.Job
import neton.jobs.JobContext
import neton.jobs.JobExecutor
import neton.logging.Logger

/**
 * Evaluates the alert rules on a schedule.
 *
 * Scheduled rather than driven by ingest, for two reasons. A rate rule has to be able to fire when
 * data *stops* arriving — to an ingest-triggered check, "the new build crashed on launch for
 * everyone" and "the new build is fine" both look like silence. And evaluating on every batch would
 * query the database continuously without making anything more timely, since the cooldown means
 * at most one alert per rule per window anyway.
 *
 * Every five minutes: fast enough that a bad release is caught while it is still rolling out, slow
 * enough that the aggregate queries cost nothing worth measuring.
 */
@Job(id = "pulse-alert-evaluate", fixedRate = 300_000, initialDelay = 60_000)
class PulseAlertJob(
    private val alerts: PulseAlertLogic,
    private val log: Logger,
) : JobExecutor {

    override suspend fun run(ctx: JobContext) {
        val fired = alerts.evaluateAll(nowMs())
        if (fired > 0) log.info("pulse alert evaluation fired $fired alert(s)")
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
