package controller.admin.pulse

import logic.PulseOverview
import logic.PulseQueryLogic
import neton.core.annotations.*

/** Overview dashboard API (analytics + apm headline). */
@Controller("/pulse/stats")
class PulseStatsController(private val query: PulseQueryLogic) {
    @Get("/overview")
    @Permission("pulse:stats:view")
    suspend fun overview(
        @Query projectId: String? = null,
        @Query days: Int = 7,
    ): PulseOverview = query.overview(projectId, days.coerceIn(7, 30))
}
