package logic

import model.AlertType
import model.PulseAlertRule

/**
 * Whether a rule should fire right now, and why not when it should not.
 *
 * Pure and separate from the evaluation that reads the database, because this is the part that
 * decides whether people keep reading alerts. Every "no" here is a false alarm that did not get
 * sent, and a false alarm costs more than a missed one: it teaches the reader to ignore the next.
 */
object AlertDecision {

    sealed interface Outcome {
        data class Fire(val observed: Double, val title: String, val detail: String) : Outcome
        /** Not firing, with the reason — surfaced in the console so a silent rule is explicable. */
        data class Skip(val reason: String) : Outcome
    }

    /**
     * @param devices sample size behind [observed]; [PulseAlertRule.minDevices] is checked against it
     * @param nowMs used with [PulseAlertRule.lastFiredAt] for the cooldown
     */
    fun evaluateCrashFreeRate(
        rule: PulseAlertRule,
        buildNumber: Long,
        appVersion: String?,
        observed: Double,
        devices: Long,
        nowMs: Long,
    ): Outcome {
        if (rule.status != 1) return Outcome.Skip("规则已停用")
        if (rule.type != AlertType.CRASH_FREE_RATE) return Outcome.Skip("规则类型不匹配")
        // Sample size first: a build with three testers is 33% crash-free after one crash, and
        // firing on that would mean an alert on every release until people stop looking.
        if (devices < rule.minDevices) {
            return Outcome.Skip("样本不足：$devices 台设备，低于门槛 ${rule.minDevices}")
        }
        if (observed >= rule.threshold) {
            return Outcome.Skip("未跌破阈值：${fmt(observed)}% ≥ ${fmt(rule.threshold)}%")
        }
        if (inCooldown(rule, nowMs)) return Outcome.Skip("冷却中")

        val label = appVersion?.let { "$it（构建 $buildNumber）" } ?: "构建 $buildNumber"
        return Outcome.Fire(
            observed = observed,
            title = "$label 无崩溃设备率 ${fmt(observed)}%，低于 ${fmt(rule.threshold)}%",
            detail = "该构建覆盖 $devices 台设备，其中 ${crashed(devices, observed)} 台发生过崩溃。",
        )
    }

    fun evaluateNewIssueImpact(
        rule: PulseAlertRule,
        issueTitle: String,
        issueMessage: String,
        affectedDevices: Long,
        nowMs: Long,
    ): Outcome {
        if (rule.status != 1) return Outcome.Skip("规则已停用")
        if (rule.type != AlertType.NEW_ISSUE_IMPACT) return Outcome.Skip("规则类型不匹配")
        if (affectedDevices < rule.threshold) {
            return Outcome.Skip("影响面未达阈值：$affectedDevices < ${fmt(rule.threshold)}")
        }
        if (inCooldown(rule, nowMs)) return Outcome.Skip("冷却中")

        return Outcome.Fire(
            observed = affectedDevices.toDouble(),
            title = "崩溃「$issueTitle」已影响 $affectedDevices 台设备",
            detail = issueMessage.ifBlank { "（无附加信息）" },
        )
    }

    /**
     * A rule that has never fired is not in cooldown. Treating a null last-fired as "just fired"
     * would silence every newly created rule for its first cooldown window, which is exactly when
     * someone is watching to see whether it works.
     */
    private fun inCooldown(rule: PulseAlertRule, nowMs: Long): Boolean {
        val last = rule.lastFiredAt ?: return false
        return nowMs - last < rule.cooldownMs
    }

    /** Devices implied to have crashed, for the alert text. */
    private fun crashed(devices: Long, crashFreeRate: Double): Long {
        val ratio = ((100.0 - crashFreeRate) / 100.0) * devices
        // Rounded to nearest rather than truncated: reporting "0 crashed" alongside a rate below
        // 100% reads as a bug in the alert, not as a small number.
        return kotlin.math.round(ratio).toLong().coerceAtLeast(1)
    }

    private fun fmt(v: Double): String {
        val scaled = kotlin.math.round(v * 100) / 100.0
        return if (scaled == kotlin.math.floor(scaled)) scaled.toLong().toString() else scaled.toString()
    }
}
