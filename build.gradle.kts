plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "com.netonstream.app"
version = "1.0.0"

repositories { mavenCentral() }

kotlin {
    // No mingw: the msgtrans/neton-io transport has no Windows target yet. Server runs on
    // macOS (dev) and Linux.
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        val coreInterop = file("$projectDir/../../Neton/neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("com.netonstream.app:module-system")
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-redis")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-security")
                // Outbound HTTP for alert webhooks; the engine is chosen by the application.
                implementation("com.netonstream:neton-http")
                // Scheduled alert evaluation (@Job).
                implementation("com.netonstream:neton-jobs")
                // The msgtrans long-connection ingest transport (sibling composite builds).
                implementation("com.netonstream:msgtrans:0.1.0")
                implementation("com.netonstream:neton-io:0.1.0")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
}

ksp { arg("neton.moduleId", "pulse") }

afterEvaluate {
    val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir(kspOut) }
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss ->
            val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
            if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
        }
    }
}
tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach { dependsOn("kspKotlinMacosArm64") }
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64)")) }.configureEach { dependsOn("kspKotlinMacosArm64") }
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64)")) }.configureEach { dependsOn("kspKotlinMacosArm64") }

extra["neton.migration.moduleId"] = "pulse"
extra["neton.migration.dialects"] = listOf("postgresql")
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../../Neton/neton/scripts/embed-migration-resources.gradle.kts")
