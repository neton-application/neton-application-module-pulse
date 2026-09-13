package controller.admin.pulse

import dto.PageResponse
import model.PulseEvent
import neton.core.annotations.*
import neton.database.dsl.*
import table.PulseEventTable

/** Analytics event browser: paginated pulse_events, filterable by kind / name / session. */
@Controller("/pulse/event")
class PulseEventController {
    @Get("/page")
    @Permission("pulse:event:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query kind: String? = null,
        @Query name: String? = null,
        @Query sessionId: String? = null,
    ): PageResponse<PulseEvent> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val r = PulseEventTable.query {
            where {
                and(
                    whenPresent(kind?.takeIf { it.isNotBlank() }) { PulseEvent::kind eq it },
                    whenPresent(name?.takeIf { it.isNotBlank() }) { PulseEvent::name eq it },
                    whenPresent(sessionId?.takeIf { it.isNotBlank() }) { PulseEvent::sessionId eq it },
                )
            }
            orderBy(PulseEvent::id.desc())
        }.page(no, size)
        return PageResponse(r.items, r.total, no, size, r.totalPages)
    }

    @Get("/get/{id}")
    @Permission("pulse:event:list")
    suspend fun get(@PathVariable id: Long): PulseEvent? = PulseEventTable.get(id)
}
