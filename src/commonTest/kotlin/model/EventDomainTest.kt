package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the kind → domain mapping. The same classification exists in SQL, in
 * `sql/postgresql/V003__event_domain_views.sql`, because BI and ad-hoc queries go through the
 * views while the application goes through [EventDomain]. Nothing can check the two against each
 * other without a database, so this test at least makes a one-sided change loud: if you edit
 * [EventDomain] and forget the migration, this fails and reminds you the views exist.
 */
class EventDomainTest {

    @Test
    fun everyKnownKindLandsInExactlyOneDomain() {
        val expected = mapOf(
            "Analytics" to EventDomain.ANALYTICS,
            "Crash" to EventDomain.APM,
            "Error" to EventDomain.APM,
            "Log" to EventDomain.APM,
            "Network" to EventDomain.APM,
            "Performance" to EventDomain.APM,
            "Breadcrumb" to EventDomain.APM,
            "Runtime" to EventDomain.SECURITY,
        )
        for ((kind, domain) in expected) assertEquals(domain, EventDomain.of(kind), "kind=$kind")

        // No kind may be claimed by two domains.
        val all = EventDomain.entries.flatMap { it.kinds }
        assertEquals(all.size, all.toSet().size, "a kind is classified into more than one domain")
        assertEquals(expected.keys, all.toSet(), "EventDomain and this test disagree; V003 too?")
    }

    @Test
    fun anUnclassifiedKindBelongsNowhere() {
        // Matches the views: an unlisted kind appears in none of them, so a new kind forces a
        // deliberate decision instead of silently defaulting into a domain.
        assertNull(EventDomain.of("SomeFutureKind"))
    }
}
