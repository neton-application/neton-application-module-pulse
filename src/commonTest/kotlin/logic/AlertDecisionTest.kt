package logic

import model.AlertType
import model.PulseAlertRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every "do not fire" here is a false alarm prevented, and a false alarm costs more than a missed
 * one — it teaches whoever reads alerts to stop reading them. So the guards are tested as
 * carefully as the firing itself.
 */
class AlertDecisionTest {

    private fun rateRule(
        threshold: Double = 99.0,
        minDevices: Long = 10,
        cooldownMs: Long = 3_600_000,
        lastFiredAt: Long? = null,
        status: Int = 1,
    ) = PulseAlertRule(
        id = 1, name = "崩溃率", type = AlertType.CRASH_FREE_RATE,
        threshold = threshold, minDevices = minDevices, cooldownMs = cooldownMs,
        lastFiredAt = lastFiredAt, status = status,
    )

    private fun impactRule(threshold: Double = 10.0, lastFiredAt: Long? = null) = PulseAlertRule(
        id = 2, name = "大面积崩溃", type = AlertType.NEW_ISSUE_IMPACT,
        threshold = threshold, lastFiredAt = lastFiredAt,
    )

    @Test
    fun firesWhenTheRateIsBelowThresholdWithEnoughDevices() {
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(), 141, "1.4.1", observed = 97.0, devices = 500, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Fire, "expected a fire, got $out")
        assertTrue(out.title.contains("1.4.1"), out.title)
        assertTrue(out.title.contains("97"), out.title)
    }

    @Test
    fun aSmallSampleNeverFires() {
        // Three testers and one crash is 66% crash-free. Firing on that means an alert on every
        // release until nobody reads them.
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(minDevices = 10), 141, "1.4.1", observed = 66.6, devices = 3, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Skip)
        assertTrue(out.reason.contains("样本不足"), out.reason)
    }

    @Test
    fun aHealthyBuildDoesNotFire() {
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(threshold = 99.0), 141, null, observed = 99.4, devices = 800, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Skip)
        assertTrue(out.reason.contains("未跌破"), out.reason)
    }

    @Test
    fun exactlyAtTheThresholdIsNotABreach() {
        // The threshold is a floor the build must stay at or above; firing at equality would make
        // a rule of "99%" mean "below 99.0000001%", which is not what anyone typed.
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(threshold = 99.0), 1, null, observed = 99.0, devices = 100, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Skip)
    }

    @Test
    fun cooldownSuppressesRepeatsButNotTheFirstFiring() {
        val rule = rateRule(cooldownMs = 1000, lastFiredAt = 10_000)
        val tooSoon = AlertDecision.evaluateCrashFreeRate(rule, 1, null, 50.0, 100, nowMs = 10_500)
        assertTrue(tooSoon is AlertDecision.Outcome.Skip)
        assertTrue(tooSoon.reason.contains("冷却"), tooSoon.reason)

        val later = AlertDecision.evaluateCrashFreeRate(rule, 1, null, 50.0, 100, nowMs = 11_500)
        assertTrue(later is AlertDecision.Outcome.Fire)
    }

    @Test
    fun aRuleThatHasNeverFiredIsNotInCooldown() {
        // Treating "never fired" as "just fired" would silence a newly created rule for its first
        // cooldown window — exactly when someone is watching to see whether it works.
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(lastFiredAt = null), 1, null, 50.0, 100, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Fire)
    }

    @Test
    fun disabledRulesNeverFire() {
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(status = 0), 1, null, 0.0, 1000, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Skip)
    }

    @Test
    fun aRuleOnlyAnswersForItsOwnType() {
        val out = AlertDecision.evaluateNewIssueImpact(rateRule(), "SIGSEGV", "boom", 500, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Skip)
        assertTrue(out.reason.contains("类型"), out.reason)
    }

    @Test
    fun impactFiresOnceTheDeviceCountReachesTheThreshold() {
        val below = AlertDecision.evaluateNewIssueImpact(impactRule(threshold = 10.0), "SIGSEGV", "boom", 9, 0)
        assertTrue(below is AlertDecision.Outcome.Skip)

        val at = AlertDecision.evaluateNewIssueImpact(impactRule(threshold = 10.0), "SIGSEGV", "boom", 10, 0)
        assertTrue(at is AlertDecision.Outcome.Fire, "reaching the threshold must fire")
        assertEquals(10.0, at.observed)
        assertTrue(at.title.contains("10 台设备"), at.title)
    }

    @Test
    fun theAlertTextNeverClaimsZeroDevicesCrashed() {
        // A rate below 100% with "0 台设备发生过崩溃" reads as a broken alert. Rounding up to at
        // least one keeps the sentence honest for very large fleets with few crashes.
        val out = AlertDecision.evaluateCrashFreeRate(rateRule(threshold = 100.0), 1, null, observed = 99.99, devices = 1000, nowMs = 0)
        assertTrue(out is AlertDecision.Outcome.Fire)
        assertTrue(!out.detail.contains("0 台设备发生过崩溃"), out.detail)
    }
}
