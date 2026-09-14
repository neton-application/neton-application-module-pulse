package controller.admin.pulse

import dto.PageResponse
import kotlinx.serialization.Serializable
import logic.PulseAuditLogic
import model.AuditSeverity
import model.PulseModuleAlert
import model.PulseModuleRule
import model.PulseModuleSighting
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
 * Third-party module auditing: the rules, and the alerts they produce.
 *
 * Two resources rather than one page, because they are used by different people at different
 * times: someone curates the rules occasionally, and someone reviews the alerts routinely.
 */
@Controller("/pulse/audit")
class PulseAuditController(private val audit: PulseAuditLogic) {

    @Serializable
    data class SaveRuleRequest(
        val id: Long = 0,
        val pattern: String,
        val label: String = "",
        val severity: String = AuditSeverity.INFO,
        val note: String? = null,
        val appId: Long? = null,
        val status: Int = 1,
    ) {
        fun toModel() = PulseModuleRule(
            id = id, pattern = pattern, label = label, severity = severity,
            note = note?.takeIf { it.isNotBlank() },
            // 0 from a form select means "global"; the column is nullable for the same meaning.
            appId = appId?.takeIf { it > 0 }, status = status,
        )
    }

    @Serializable
    data class AlertStatusRequest(val id: Long, val status: Int)

    @Get("/severities")
    @Permission("pulse:audit:list")
    suspend fun severities(): List<String> = AuditSeverity.ALL

    // ---- rules

    @Get("/rule/page")
    @Permission("pulse:audit:list")
    suspend fun rulePage(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query appId: Long? = null,
    ): PageResponse<PulseModuleRule> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = audit.listRules(appId?.takeIf { it > 0 })
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
    }

    @Get("/rule/get/{id}")
    @Permission("pulse:audit:list")
    suspend fun getRule(@PathVariable id: Long): PulseModuleRule? = audit.getRule(id)

    @Post("/rule/create")
    @Permission("pulse:audit:save")
    suspend fun createRule(@Body request: SaveRuleRequest): Long = audit.saveRule(request.toModel())

    @Put("/rule/update")
    @Permission("pulse:audit:save")
    suspend fun updateRule(@Body request: SaveRuleRequest) { audit.saveRule(request.toModel()) }

    @Delete("/rule/delete/{id}")
    @Permission("pulse:audit:save")
    suspend fun deleteRule(@PathVariable id: Long) = audit.deleteRule(id)

    // ---- alerts

    @Get("/alert/page")
    @Permission("pulse:audit:list")
    suspend fun alertPage(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query status: Int? = null,
        @Query severity: String? = null,
    ): PageResponse<PulseModuleAlert> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = audit.listAlerts(status, severity)
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
    }

    /** The devices a module was actually seen on — the evidence behind the device count. */
    @Get("/alert/devices/{id}")
    @Permission("pulse:audit:list")
    suspend fun alertDevices(@PathVariable id: Long, @Query limit: Int = 100): List<PulseModuleSighting> =
        audit.devicesFor(id, limit.coerceIn(1, 500))

    /**
     * Acknowledge or ignore an alert. Separate permission from curating rules: triaging what has
     * already been found is routine work, changing what counts as findable is not.
     */
    @Put("/alert/status")
    @Permission("pulse:audit:triage")
    suspend fun setAlertStatus(@Body request: AlertStatusRequest) = audit.setAlertStatus(request.id, request.status)
}
