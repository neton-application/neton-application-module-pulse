package logic

import model.AuditSeverity
import model.PulseModuleRule

/**
 * Matches a bundled module name against audit rules.
 *
 * Pure and separate from the persistence around it, because this is the part whose behaviour is
 * worth pinning down: a rule that silently matches nothing, or matches everything, is a rule that
 * makes the audit lie.
 */
object ModuleMatcher {

    /**
     * Glob with `*` only, and only as a leading and/or trailing wildcard. Not a regular expression:
     * these patterns are typed by operators into a form field, and a regex there is a way to hang
     * the ingest path on a pathological backtrack. `*` in the middle is treated literally rather
     * than rejected, so a surprising pattern under-matches instead of over-matching.
     *
     * Matching ignores case: framework names are inconsistently capitalised across SDK vendors.
     */
    fun matches(moduleName: String, pattern: String): Boolean {
        val name = moduleName.lowercase()
        val p = pattern.trim().lowercase()
        if (p.isEmpty()) return false
        if (p == "*") return true

        val leading = p.startsWith("*")
        val trailing = p.endsWith("*")
        val core = p.trim('*')
        if (core.isEmpty()) return false

        return when {
            leading && trailing -> name.contains(core)
            leading -> name.endsWith(core)
            trailing -> name.startsWith(core)
            else -> name == core
        }
    }

    /**
     * The rule that should decide this module's alert, or null if none apply.
     *
     * When several rules match, the most severe wins, and among equals the more specific pattern
     * (the longer one) wins. An app-scoped rule always beats a global one: it was written by
     * someone who knew this particular app's context.
     */
    fun bestMatch(moduleName: String, rules: List<PulseModuleRule>, appId: Long?): PulseModuleRule? =
        rules.asSequence()
            .filter { it.status == 1 }
            .filter { it.appId == null || it.appId == appId }
            .filter { matches(moduleName, it.pattern) }
            .sortedWith(
                compareByDescending<PulseModuleRule> { if (it.appId != null) 1 else 0 }
                    .thenByDescending { AuditSeverity.rank(it.severity) }
                    .thenByDescending { it.pattern.trim('*').length },
            )
            .firstOrNull()

    /** The comma-separated list the client reports; blank entries dropped. */
    fun parseReportedModules(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
}
