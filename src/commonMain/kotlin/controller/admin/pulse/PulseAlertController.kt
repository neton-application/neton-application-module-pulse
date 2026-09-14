package controller.admin.pulse

import dto.PageResponse
import kotlinx.serialization.Serializable
import logic.PulseAlertLogic
import model.AlertType
import model.PulseAlertRecord
import model.PulseAlertRule
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Query

/**
 * Alert rules and what they have fired.
 *
 * The history is not decoration: an alerting system nobody can verify actually sent anything is
 * indistinguishable from no alerting at all, so every firing and its delivery outcome is listed.
 */
@Controller("/pulse/alert")
class PulseAlertController(private val alerts: PulseAlertLogic) {

    /**
     * Named for the controller it belongs to rather than just "SaveRuleRequest": the route
     * generator imports nested request types by simple name into one flat namespace, so two
     * controllers each declaring a SaveRuleRequest collide at code-generation time.
     */
    @Serializable
    data class SaveAlertRuleRequest(
        val id: Long = 0,
        val name: String,
        val type: String,
        val projectId: String? = null,
        val threshold: Double = 0.0,
        val minDevices: Long = 10,
        val cooldownMs: Long = 3_600_000,
        val webhookUrl: String? = null,
        val status: Int = 1,
    ) {
        fun toModel(existing: PulseAlertRule?) = PulseAlertRule(
            id = id, name = name, type = type,
            projectId = projectId?.takeIf { it.isNotBlank() },
            threshold = threshold, minDevices = minDevices, cooldownMs = cooldownMs,
            webhookUrl = webhookUrl?.takeIf { it.isNotBlank() }, status = status,
            // Preserved across edits: clearing it would let a rule fire again immediately after
            // any unrelated change to it, which is how an edit turns into a burst of alerts.
            lastFiredAt = existing?.lastFiredAt,
        )
    }

    @Get("/types")
    @Permission("pulse:alert:list")
    suspend fun types(): List<String> = AlertType.ALL

    @Get("/rule/page")
    @Permission("pulse:alert:list")
    suspend fun rulePage(@Query pageNo: Int = 1, @Query pageSize: Int = 20): PageResponse<PulseAlertRule> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = alerts.listRules()
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
    }

    @Get("/rule/get/{id}")
    @Permission("pulse:alert:list")
    suspend fun getRule(@PathVariable id: Long): PulseAlertRule? = alerts.getRule(id)

    @Post("/rule/create")
    @Permission("pulse:alert:save")
    suspend fun createRule(@Body request: SaveAlertRuleRequest): Long = alerts.saveRule(request.toModel(null))

    @Put("/rule/update")
    @Permission("pulse:alert:save")
    suspend fun updateRule(@Body request: SaveAlertRuleRequest) {
        alerts.saveRule(request.toModel(alerts.getRule(request.id)))
    }

    @Delete("/rule/delete/{id}")
    @Permission("pulse:alert:save")
    suspend fun deleteRule(@PathVariable id: Long) = alerts.deleteRule(id)

    @Get("/record/page")
    @Permission("pulse:alert:list")
    suspend fun recordPage(@Query pageNo: Int = 1, @Query pageSize: Int = 20): PageResponse<PulseAlertRecord> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = alerts.records(limit = 500)
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
    }

    /**
     * Run every rule now instead of waiting for the schedule.
     *
     * Its own permission because it sends real notifications: someone testing a rule should not be
     * able to page the on-call by accident from a read-only account.
     */
    @Post("/evaluate")
    @Permission("pulse:alert:save")
    suspend fun evaluateNow(): Int = alerts.evaluateAll(kotlin.time.Clock.System.now().toEpochMilliseconds())
}
