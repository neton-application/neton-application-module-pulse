package model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PulsePackagePolicyTest {
    private val app = PulseApp(
        name = "积分商城",
        platform = PulsePlatform.IOS,
        appKey = "pulse_test",
        packageName = "com.example.primary",
    )

    @Test
    fun strictAcceptsOnlyThePrimaryPackage() {
        assertTrue(PulsePackagePolicy.accepts(app, "com.example.primary"))
        assertFalse(PulsePackagePolicy.accepts(app, "com.example.resigned"))
        assertFalse(PulsePackagePolicy.accepts(app, null))
    }

    @Test
    fun allowlistAcceptsPrimaryAndConfiguredResigningPackages() {
        val configured = app.copy(
            packagePolicy = PulsePackagePolicy.ALLOWLIST,
            allowedPackages = "com.example.channel.a, com.example.channel.b\ncom.example.channel.c",
        )
        assertTrue(PulsePackagePolicy.accepts(configured, "com.example.primary"))
        assertTrue(PulsePackagePolicy.accepts(configured, "com.example.channel.b"))
        assertTrue(PulsePackagePolicy.accepts(configured, "com.example.channel.c"))
        assertFalse(PulsePackagePolicy.accepts(configured, "com.example.unknown"))
    }

    @Test
    fun anySupportsLegacyAndArbitraryInternalPackages() {
        val configured = app.copy(packagePolicy = PulsePackagePolicy.ANY)
        assertTrue(PulsePackagePolicy.accepts(configured, "com.partner.resigned"))
        assertTrue(PulsePackagePolicy.accepts(configured, null))
    }
}
