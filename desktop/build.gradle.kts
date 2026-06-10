import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.flatbuffers:flatbuffers-java:24.3.25")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.hyperion.grabber.MainKt"
        nativeDistributions {
            // AppImage is rejected at configuration time on macOS, which broke
            // every Gradle invocation (even `test`) for local development on
            // Mac. CI only packages Msi/Deb; Dmg covers local Mac builds.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "HyperionGrabber"
            packageVersion = "1.9.0"
            description = "Hyperion.ng screen grabber"
            windows {
                menuGroup = "Hyperion Grabber"
                upgradeUuid = "8A9E2B4C-1D3F-4A5E-8B6C-7D8E9F0A1B2C"
            }
            linux {
                packageName = "hyperion-grabber"
            }
        }
    }
}
