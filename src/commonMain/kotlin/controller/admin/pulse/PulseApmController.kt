package controller.admin.pulse

import dto.PageResponse
import model.PulseEvent
import neton.core.annotations.*
import neton.database.dsl.*
import table.PulseEventTable

/**
 * APM browser (Bugly / U-APM style). Crashes and errors, and performance samples, are the same
 * pulse_events rows filtered by kind. Defaults to Crash; the UI switches kind via a tab.
 */
@Controller("/pulse/apm")
class PulseApmController {
    @Get("/page")
    @Permission("pulse:apm:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query kind: String = "Crash",  // Crash | Error | Performance | Network
        @Query sessionId: String? = null,
    ): PageResponse<PulseEvent> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val r = PulseEventTable.query {
            where {
                and(
                    PulseEvent::kind eq kind,
                    whenPresent(sessionId?.takeIf { it.isNotBlank() }) { PulseEvent::sessionId eq it },
                )
            }
            orderBy(PulseEvent::id.desc())
        }.page(no, size)
        return PageResponse(r.items, r.total, no, size, r.totalPages)
    }
}
