package model

/**
 * Business domain an event [kind] belongs to. Mirrors the three views created by
 * `sql/postgresql/V003__event_domain_views.sql` — analytics_events / apm_events / security_events.
 * SQL consumers (BI, ad-hoc queries) go through the views; the application goes through here.
 *
 * The two definitions must agree, so change them together: [EventDomainTest] pins the mapping on
 * this side, and the migration header points back at this file.
 *
 * A kind that is not listed belongs to no domain and is deliberately invisible to all three views:
 * adding a kind should force a decision about where it lands, not silently default somewhere.
 */
enum class EventDomain(val id: String, val kinds: Set<String>) {
    /** Product analytics: user behaviour. */
    ANALYTICS("analytics", setOf("Analytics")),

    /** Quality monitoring: crashes, errors, logs, network, performance, breadcrumbs. */
    APM("apm", setOf("Crash", "Error", "Log", "Network", "Performance", "Breadcrumb")),

    /** Runtime security: third-party module loads, sensitive-behaviour attribution. */
    SECURITY("security", setOf("Runtime"));

    companion object {
        /** The domain [kind] belongs to, or null when it is not classified. */
        fun of(kind: String): EventDomain? = entries.firstOrNull { kind in it.kinds }
    }
}
