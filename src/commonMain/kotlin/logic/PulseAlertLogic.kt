package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import model.AlertType
import model.DeliveryStatus
import model.PulseAlertRecord
import model.PulseAlertRule
import model.PulseCrashIssue
import neton.core.http.BadRequestException
import neton.database.dsl.*
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.logging.Logger
import table.PulseAlertRecordTable
import table.PulseAlertRuleTable
import table.PulseCrashIssueTable

/** The JSON a webhook receives. Flat and self-describing: a receiver should need no schema. */
@Serializable
data class AlertPayload(
    val rule: String,
    val type: String,
    val title: String,
    val detail: String,
    val observed: Double,
    val threshold: Double,
    val projectId: String? = null,
    val firedAt: Long,
)

/**
 * Alert rules, their evaluation, and delivery.
 *
 * Evaluation is scheduled rather than driven by ingest. A rate-based rule has to be able to fire
 * while nothing is arriving — "the new build stopped reporting" and "the new build is fine" look
 * identical to an ingest-triggered check — and a rule evaluated on every batch would hammer the
 * database for no added timeliness.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-alert")
class PulseAlertLogic(
    private val versions: PulseVersionLogic,
    private val log: Logger,
) {

    /**
     * Set by the runtime bootstrap. Alerts are still recorded when it is absent — an alert with
     * nowhere to go is a degraded alert, not a reason to stop evaluating.
     */
    var httpClient: HttpClient? = null

    // ---- rules

    suspend fun listRules(): List<PulseAlertRule> =
        PulseAlertRuleTable.query { orderBy(PulseAlertRule::id.desc()) }.list()

    suspend fun getRule(id: Long): PulseAlertRule? = PulseAlertRuleTable.get(id)

    suspend fun saveRule(rule: PulseAlertRule): Long {
        if (rule.name.isBlank()) throw BadRequestException("规则名称不能为空")
        if (!AlertType.isValid(rule.type)) {
            throw BadRequestException("未知规则类型 '${rule.type}'，可选：${AlertType.ALL.joinToString("、")}")
        }
        if (rule.type == AlertType.CRASH_FREE_RATE && (rule.threshold <= 0 || rule.threshold > 100)) {
            throw BadRequestException("无崩溃率阈值应在 0 到 100 之间")
        }
        if (rule.type == AlertType.NEW_ISSUE_IMPACT && rule.threshold < 1) {
            throw BadRequestException("影响设备数阈值至少为 1")
        }
        if (rule.minDevices < 1) throw BadRequestException("最小样本量至少为 1")
        if (rule.cooldownMs < 0) throw BadRequestException("冷却时间不能为负")
        return if (rule.id == 0L) PulseAlertRuleTable.insert(rule).id
        else { PulseAlertRuleTable.update(rule); rule.id }
    }

    suspend fun deleteRule(id: Long) = PulseAlertRuleTable.destroy(id)

    suspend fun records(limit: Int = 100): List<PulseAlertRecord> =
        PulseAlertRecordTable.query {
            orderBy(PulseAlertRecord::firedAt.desc())
            limitOffset(limit, 0)
        }.list()

    // ---- evaluation

    /**
     * Check every enabled rule once. Returns how many fired.
     *
     * A failure on one rule does not stop the others: alerting is exactly the feature that must
     * keep working when something else is broken.
     */
    suspend fun evaluateAll(nowMs: Long): Int {
        var fired = 0
        for (rule in listRules()) {
            if (rule.status != 1) continue
            try {
                fired += when (rule.type) {
                    AlertType.CRASH_FREE_RATE -> evaluateCrashFreeRate(rule, nowMs)
                    AlertType.NEW_ISSUE_IMPACT -> evaluateNewIssueImpact(rule, nowMs)
                    else -> 0
                }
            } catch (t: Throwable) {
                log.warn("pulse alert rule '${rule.name}' failed to evaluate: ${t.message}")
            }
        }
        return fired
    }

    /**
     * Only the newest build is checked. Older builds have already been judged, and a build that is
     * no longer shipping cannot be acted on — alerting on it would be noise about the past.
     */
    private suspend fun evaluateCrashFreeRate(rule: PulseAlertRule, nowMs: Long): Int {
        val newest = versions.builds(rule.projectId).maxByOrNull { it.buildNumber } ?: return 0
        val outcome = AlertDecision.evaluateCrashFreeRate(
            rule = rule,
            buildNumber = newest.buildNumber,
            appVersion = newest.appVersion,
            observed = newest.crashFreeDeviceRate,
            devices = newest.devices,
            nowMs = nowMs,
        )
        return fireIfNeeded(rule, outcome, nowMs)
    }

    /**
     * Open issues only. A resolved or ignored issue reaching a device threshold is not news —
     * someone already decided what to do about it.
     */
    private suspend fun evaluateNewIssueImpact(rule: PulseAlertRule, nowMs: Long): Int {
        val issues = PulseCrashIssueTable.query {
            where {
                and(
                    PulseCrashIssue::status eq model.CrashStatus.OPEN,
                    whenPresent(rule.projectId?.takeIf { it.isNotBlank() }) { PulseCrashIssue::projectId eq it },
                )
            }
        }.list()
        // Worst first, and stop after one: the cooldown would suppress the rest anyway, and firing
        // for the biggest issue is more useful than firing for whichever was inserted first.
        val worst = issues.maxByOrNull { it.deviceCount } ?: return 0
        val outcome = AlertDecision.evaluateNewIssueImpact(
            rule = rule,
            issueTitle = worst.title,
            issueMessage = worst.message,
            affectedDevices = worst.deviceCount,
            nowMs = nowMs,
        )
        return fireIfNeeded(rule, outcome, nowMs)
    }

    private suspend fun fireIfNeeded(rule: PulseAlertRule, outcome: AlertDecision.Outcome, nowMs: Long): Int {
        if (outcome !is AlertDecision.Outcome.Fire) return 0

        val record = PulseAlertRecordTable.insert(
            PulseAlertRecord(
                ruleId = rule.id, ruleName = rule.name, type = rule.type, projectId = rule.projectId,
                title = outcome.title, detail = outcome.detail,
                observed = outcome.observed, threshold = rule.threshold,
                deliveryStatus = DeliveryStatus.PENDING, firedAt = nowMs,
            ),
        )
        // Stamped before delivery is attempted. If the webhook is slow or down, the cooldown must
        // still hold — otherwise a broken receiver turns into a retry storm on the next tick.
        PulseAlertRuleTable.update(rule.copy(lastFiredAt = nowMs))

        deliver(rule, record, outcome, nowMs)
        log.info("pulse alert fired: ${outcome.title}")
        return 1
    }

    private suspend fun deliver(
        rule: PulseAlertRule,
        record: PulseAlertRecord,
        outcome: AlertDecision.Outcome.Fire,
        nowMs: Long,
    ) {
        val url = rule.webhookUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            PulseAlertRecordTable.update(record.copy(deliveryStatus = DeliveryStatus.NOT_CONFIGURED))
            return
        }
        val client = httpClient
        if (client == null) {
            PulseAlertRecordTable.update(
                record.copy(
                    deliveryStatus = DeliveryStatus.FAILED,
                    deliveryError = "没有可用的 HTTP 客户端（应用未绑定 HttpClient）",
                ),
            )
            return
        }

        val payload = AlertPayload(
            rule = rule.name, type = rule.type, title = outcome.title, detail = outcome.detail,
            observed = outcome.observed, threshold = rule.threshold,
            projectId = rule.projectId, firedAt = nowMs,
        )
        try {
            val response = client.request(
                HttpClientRequest(
                    method = HttpClientMethod.Post,
                    url = url,
                    // Content type travels with the body in this client, so no header map is needed.
                    body = HttpClientBody.Text(
                        json.encodeToString(AlertPayload.serializer(), payload),
                        contentType = "application/json",
                    ),
                ),
            )
            val ok = response.statusCode in 200..299
            PulseAlertRecordTable.update(
                record.copy(
                    deliveryStatus = if (ok) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED,
                    deliveryError = if (ok) null else "HTTP ${response.statusCode}",
                ),
            )
        } catch (t: Throwable) {
            // Recorded, not rethrown: the alert already happened, and losing the record because
            // the receiver was unreachable is the one outcome with no upside.
            PulseAlertRecordTable.update(
                record.copy(deliveryStatus = DeliveryStatus.FAILED, deliveryError = (t.message ?: t.toString()).take(500)),
            )
            log.warn("pulse alert webhook failed: ${t.message}")
        }
    }

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
