package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.AlertStatus
import model.AuditSeverity
import model.PulseApp
import model.PulseEvent
import model.PulseModuleAlert
import model.PulseModuleRule
import model.PulseModuleSighting
import neton.core.http.BadRequestException
import neton.database.dsl.*
import neton.logging.Logger
import table.PulseAppTable
import table.PulseModuleAlertTable
import table.PulseModuleRuleTable
import table.PulseModuleSightingTable

/**
 * Third-party module auditing.
 *
 * The client reports which images shipped inside the app and states nothing about them. This is
 * where they are judged, and judging here rather than on the client is deliberate: a shipped binary
 * should not contain a list of what its vendor considers suspicious, rules have to be changeable
 * without a release, and the same SDK can be entirely fine in one product and not in another.
 *
 * Alerts aggregate per (project, module). A module on ten thousand devices is one row with two
 * counts — how often and how many devices — not ten thousand rows.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-audit")
class PulseAuditLogic(private val log: Logger) {

    // ---- rules

    suspend fun listRules(appId: Long? = null): List<PulseModuleRule> =
        PulseModuleRuleTable.query {
            where { whenPresent(appId) { PulseModuleRule::appId eq it } }
            orderBy(PulseModuleRule::id.desc())
        }.list()

    suspend fun getRule(id: Long): PulseModuleRule? = PulseModuleRuleTable.get(id)

    suspend fun saveRule(rule: PulseModuleRule): Long {
        if (rule.pattern.isBlank()) throw BadRequestException("匹配模式不能为空")
        if (!AuditSeverity.isValid(rule.severity)) {
            throw BadRequestException("未知级别 '${rule.severity}'，可选：${AuditSeverity.ALL.joinToString("、")}")
        }
        return if (rule.id == 0L) PulseModuleRuleTable.insert(rule).id
        else { PulseModuleRuleTable.update(rule); rule.id }
    }

    suspend fun deleteRule(id: Long) = PulseModuleRuleTable.destroy(id)

    // ---- alerts

    suspend fun listAlerts(status: Int? = null, severity: String? = null): List<PulseModuleAlert> =
        PulseModuleAlertTable.query {
            where {
                and(
                    whenPresent(status) { PulseModuleAlert::status eq it },
                    whenPresent(severity?.takeIf { it.isNotBlank() }) { PulseModuleAlert::severity eq it },
                )
            }
            // Most severe first, then most recent: the console's first screen should be the things
            // that matter, not the things that happen to be newest.
            orderBy(PulseModuleAlert::lastSeenAt.desc())
        }.list().sortedWith(
            compareByDescending<PulseModuleAlert> { AuditSeverity.rank(it.severity) }
                .thenByDescending { it.lastSeenAt },
        )

    suspend fun setAlertStatus(id: Long, status: Int) {
        val existing = PulseModuleAlertTable.get(id) ?: throw BadRequestException("告警不存在：$id")
        PulseModuleAlertTable.update(existing.copy(status = status))
    }

    suspend fun devicesFor(alertId: Long, limit: Int = 100): List<PulseModuleSighting> =
        PulseModuleSightingTable.query {
            where { PulseModuleSighting::alertId eq alertId }
            orderBy(PulseModuleSighting::lastSeenAt.desc())
            limitOffset(limit, 0)
        }.list()

    // ---- evaluation, called from the ingest path

    /**
     * Evaluate the inventory events in a persisted batch.
     *
     * Runs after the events are written rather than instead of writing them: the raw event is the
     * record, the alert is a derived view of it, and a failure here must not cost the event.
     */
    suspend fun evaluate(rows: List<PulseEvent>, nowMs: Long) {
        val inventories = rows.filter { it.kind == "Runtime" && it.name == "module_inventory" }
        if (inventories.isEmpty()) return

        val rules = PulseModuleRuleTable.query { }.list()
        if (rules.isEmpty()) return

        for (row in inventories) {
            val reported = ModuleMatcher.parseReportedModules(attribute(row.attributes, "bundled_modules"))
            if (reported.isEmpty()) continue
            val appId = appIdFor(row.projectId)
            for (moduleName in reported) {
                val rule = ModuleMatcher.bestMatch(moduleName, rules, appId) ?: continue
                recordAlert(row, moduleName, rule, nowMs)
            }
        }
    }

    private suspend fun recordAlert(row: PulseEvent, moduleName: String, rule: PulseModuleRule, nowMs: Long) {
        val projectId = row.projectId ?: ""
        val existing = PulseModuleAlertTable.query {
            where { and(PulseModuleAlert::projectId eq projectId, PulseModuleAlert::moduleName eq moduleName) }
        }.list().firstOrNull()

        val alertId: Long
        if (existing == null) {
            alertId = PulseModuleAlertTable.insert(
                PulseModuleAlert(
                    projectId = projectId, moduleName = moduleName, ruleId = rule.id,
                    severity = rule.severity, label = rule.label, note = rule.note,
                    firstSeenAt = nowMs, lastSeenAt = nowMs, occurrences = 1, deviceCount = 0,
                    status = AlertStatus.OPEN,
                ),
            ).id
            log.info("pulse audit raised ${rule.severity} alert for module '$moduleName' (project=$projectId)")
        } else {
            alertId = existing.id
            PulseModuleAlertTable.update(
                existing.copy(
                    // The rule may have been re-graded since this alert was raised; the alert
                    // should reflect the current judgement, not the one at first sighting.
                    ruleId = rule.id, severity = rule.severity, label = rule.label, note = rule.note,
                    lastSeenAt = nowMs, occurrences = existing.occurrences + 1,
                ),
            )
        }

        val deviceId = row.deviceId ?: return   // no device id, nothing honest to count
        val seen = PulseModuleSightingTable.query {
            where { and(PulseModuleSighting::alertId eq alertId, PulseModuleSighting::deviceId eq deviceId) }
        }.list().firstOrNull()

        if (seen == null) {
            PulseModuleSightingTable.insert(
                PulseModuleSighting(alertId = alertId, deviceId = deviceId, firstSeenAt = nowMs, lastSeenAt = nowMs),
            )
            // Recounted from the sightings rather than incremented, so a retried batch cannot
            // inflate it. The whole ingest path is at-least-once.
            val count = PulseModuleSightingTable.query {
                where { PulseModuleSighting::alertId eq alertId }
            }.count()
            PulseModuleAlertTable.get(alertId)?.let {
                PulseModuleAlertTable.update(it.copy(deviceCount = count))
            }
        } else {
            PulseModuleSightingTable.update(seen.copy(lastSeenAt = nowMs))
        }
    }

    private suspend fun appIdFor(projectId: String?): Long? {
        val key = projectId?.takeIf { it.isNotBlank() } ?: return null
        return PulseAppTable.query { where { PulseApp::appKey eq key } }.list().firstOrNull()?.id
    }

    private fun attribute(attributes: String, key: String): String? = try {
        json.decodeFromString(JsonObject.serializer(), attributes)[key]?.jsonPrimitive?.content
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
