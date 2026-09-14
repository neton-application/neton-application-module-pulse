package logic

import model.AuditSeverity
import model.PulseModuleRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The matcher decides what the audit reports, so an over-matching rule floods the console and an
 * under-matching one hides exactly the SDK someone was trying to find. Both failure modes are
 * covered here.
 */
class ModuleMatcherTest {

    private fun rule(pattern: String, severity: String = AuditSeverity.INFO, appId: Long? = null, status: Int = 1) =
        PulseModuleRule(id = 1, pattern = pattern, label = pattern, severity = severity, appId = appId, status = status)

    @Test
    fun exactPatternMatchesOnlyThatName() {
        assertTrue(ModuleMatcher.matches("SDWebImage", "SDWebImage"))
        assertFalse(ModuleMatcher.matches("SDWebImageExtra", "SDWebImage"))
        assertFalse(ModuleMatcher.matches("MySDWebImage", "SDWebImage"))
    }

    @Test
    fun wildcardsAnchorWhereTheyAre() {
        assertTrue(ModuleMatcher.matches("WeChatSDK", "WeChat*"))
        assertFalse(ModuleMatcher.matches("MyWeChat", "WeChat*"))

        assertTrue(ModuleMatcher.matches("MyWeChat", "*WeChat"))
        assertFalse(ModuleMatcher.matches("WeChatSDK", "*WeChat"))

        assertTrue(ModuleMatcher.matches("GoogleAdsKit", "*Ads*"))
        assertTrue(ModuleMatcher.matches("Ads", "*Ads*"))
        assertFalse(ModuleMatcher.matches("Analytics", "*Ads*"))
    }

    @Test
    fun matchingIgnoresCase() {
        // Framework names are inconsistently capitalised across vendors, and an audit that misses
        // a module because someone typed "sdwebimage" is worse than useless.
        assertTrue(ModuleMatcher.matches("SDWebImage", "sdwebimage"))
        assertTrue(ModuleMatcher.matches("sdwebimage", "SDWebImage"))
    }

    @Test
    fun emptyAndDegeneratePatternsDoNotMatchEverything() {
        assertFalse(ModuleMatcher.matches("Anything", ""))
        assertFalse(ModuleMatcher.matches("Anything", "   "))
        // '**' has no core left; treated as no pattern rather than as match-all, because an
        // accidental one would silently alert on every module in every app.
        assertFalse(ModuleMatcher.matches("Anything", "**"))
        // A deliberate single '*' is the documented way to match everything.
        assertTrue(ModuleMatcher.matches("Anything", "*"))
    }

    @Test
    fun aMiddleWildcardIsLiteralSoASurpriseUnderMatches() {
        // Not supported, and deliberately not an error: the safe direction for an unsupported
        // pattern is to match less, not more.
        assertFalse(ModuleMatcher.matches("GoogleAdsKit", "Google*Kit"))
    }

    @Test
    fun theMostSevereRuleWins() {
        val rules = listOf(
            rule("SDWeb*", AuditSeverity.INFO),
            rule("SDWebImage", AuditSeverity.CRITICAL),
        )
        assertEquals(AuditSeverity.CRITICAL, ModuleMatcher.bestMatch("SDWebImage", rules, appId = null)?.severity)
    }

    @Test
    fun anAppScopedRuleBeatsAGlobalOneEvenIfLessSevere() {
        // Whoever scoped a rule to one app knew that app's context; a blanket rule did not.
        val rules = listOf(
            rule("SDWebImage", AuditSeverity.CRITICAL, appId = null),
            rule("SDWebImage", AuditSeverity.INFO, appId = 7),
        )
        val best = ModuleMatcher.bestMatch("SDWebImage", rules, appId = 7)
        assertEquals(AuditSeverity.INFO, best?.severity)
        assertEquals(7L, best?.appId)
    }

    @Test
    fun aRuleForAnotherAppDoesNotApply() {
        val rules = listOf(rule("SDWebImage", AuditSeverity.CRITICAL, appId = 99))
        assertNull(ModuleMatcher.bestMatch("SDWebImage", rules, appId = 7))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rules = listOf(rule("SDWebImage", AuditSeverity.CRITICAL, status = 0))
        assertNull(ModuleMatcher.bestMatch("SDWebImage", rules, appId = null))
    }

    @Test
    fun amongEqualsTheMoreSpecificPatternWins() {
        val rules = listOf(
            rule("*Image*", AuditSeverity.WARN),
            rule("SDWebImage*", AuditSeverity.WARN),
        )
        assertEquals("SDWebImage*", ModuleMatcher.bestMatch("SDWebImage", rules, appId = null)?.pattern)
    }

    @Test
    fun reportedModuleListIsParsedDefensively() {
        assertEquals(
            listOf("A", "B", "C"),
            ModuleMatcher.parseReportedModules(" A , B ,, C , A "),
        )
        assertEquals(emptyList(), ModuleMatcher.parseReportedModules(null))
        assertEquals(emptyList(), ModuleMatcher.parseReportedModules("  "))
    }
}
