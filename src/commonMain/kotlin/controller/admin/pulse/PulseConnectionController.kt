package controller.admin.pulse

import logic.PulseConnectionLogic
import logic.PulseConnectionSummary
import logic.PulseOnlineConnection
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Query

@Controller("/pulse/connection")
class PulseConnectionController(private val connections: PulseConnectionLogic) {
    @Get("/summary")
    @Permission("pulse:pipeline:view")
    suspend fun summary(@Query projectId: String? = null): PulseConnectionSummary =
        connections.summary(projectId)

    @Get("/online")
    @Permission("pulse:pipeline:view")
    suspend fun online(@Query projectId: String? = null): List<PulseOnlineConnection> =
        connections.listOnline(projectId)
}
