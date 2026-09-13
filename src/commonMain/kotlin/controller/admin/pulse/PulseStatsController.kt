package controller.admin.pulse

import logic.PulseOverview
import logic.PulseQueryLogic
import neton.core.annotations.*

/** Overview dashboard API (analytics + apm headline). */
@Controller("/pulse/stats")
class PulseStatsController(private val query: PulseQueryLogic) {
    @Get("/overview")
    @Permission("pulse:stats:view")
    suspend fun overview(): PulseOverview = query.overview()
}
