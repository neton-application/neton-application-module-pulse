package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.AlertKind
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
        val runtime = rows.filter { it.kind == "Runtime" }
        if (runtime.isEmpty()) return

        val rules = PulseModuleRuleTable.query { }.list()
        if (rules.isEmpty()) return

        evaluateInventories(runtime.filter { it.name == "module_inventory" }, rules, nowMs)
        evaluateBehaviours(runtime, rules, nowMs)
    }

    /**
     * Sensitive API calls the monitor attributed to a module.
     *
     * Judged against the same rules as the structural findings, because the question is the same
     * one — is this module worth knowing about — and maintaining two rule sets would mean an
     * operator who blocked an SDK structurally still has to remember to block its behaviour.
     *
     * Events not produced by the monitor are ignored: the inventory events carry no api_class, and
     * a host calling recordBehavior by hand is free-form and has no module to judge.
     */
    private suspend fun evaluateBehaviours(rows: List<PulseEvent>, rules: List<PulseModuleRule>, nowMs: Long) {
        for (row in rows) {
            val apiClass = attribute(row.attributes, "api_class") ?: continue
            val moduleName = row.sourceModule?.takeIf { it.isNotBlank() } ?: continue
            val appId = appIdFor(row.projectId)
            val rule = ModuleMatcher.bestMatch(moduleName, rules, appId) ?: continue
            val calls = attribute(row.attributes, "call_count")?.toLongOrNull() ?: 1L
            recordAlert(
                row = row, moduleName = moduleName, rule = rule, nowMs = nowMs,
                kind = AlertKind.BEHAVIOR,
                api = row.name,
                apiClass = apiClass,
                calls = calls,
                detail = attribute(row.attributes, "detail"),
            )
        }
    }

    private suspend fun evaluateInventories(inventories: List<PulseEvent>, rules: List<PulseModuleRule>, nowMs: Long) {
        for (row in inventories) {
            val reported = ModuleMatcher.parseReportedModules(attribute(row.attributes, "bundled_modules"))
            if (reported.isEmpty()) continue
            val appId = appIdFor(row.projectId)
            for (moduleName in reported) {
                val rule = ModuleMatcher.bestMatch(moduleName, rules, appId) ?: continue
                recordAlert(row, moduleName, rule, nowMs, kind = AlertKind.MODULE)
            }
        }
    }

    private suspend fun recordAlert(
        row: PulseEvent,
        moduleName: String,
        rule: PulseModuleRule,
        nowMs: Long,
        kind: String,
        api: String? = null,
        apiClass: String? = null,
        calls: Long = 0,
        detail: String? = null,
    ) {
        val projectId = row.projectId ?: ""
        // Keyed on the API too: "SDWebImage ships in this app", "SDWebImage read the clipboard"
        // and "SDWebImage read the advertising id" are three separate findings, and folding them
        // into one row would let the newest overwrite whatever came before.
        val existing = PulseModuleAlertTable.query {
            where {
                and(
                    PulseModuleAlert::projectId eq projectId,
                    PulseModuleAlert::moduleName eq moduleName,
                    PulseModuleAlert::kind eq kind,
                )
            }
        }.list().firstOrNull { it.api == api && it.detail == detail }

        val alertId: Long
        if (existing == null) {
            alertId = PulseModuleAlertTable.insert(
                PulseModuleAlert(
                    projectId = projectId, moduleName = moduleName, ruleId = rule.id,
                    kind = kind, api = api, callCount = calls, detail = detail,
                    severity = rule.severity,
                    label = if (apiClass != null) "${rule.label} · $apiClass" else rule.label,
                    note = rule.note,
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
                    ruleId = rule.id, severity = rule.severity, note = rule.note,
                    label = if (apiClass != null) "${rule.label} · $apiClass" else rule.label,
                    lastSeenAt = nowMs, occurrences = existing.occurrences + 1,
                    callCount = existing.callCount + calls,
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

    internal fun attribute(attributes: String, key: String): String? = try {
        json.decodeFromString(JsonObject.serializer(), attributes)[key]?.jsonPrimitive?.content
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
