plugins {
    // Auto-provisions the JDK 21 toolchain (build.gradle.kts jvmToolchain) on
    // machines that don't have it installed — CI runners happen to ship one,
    // local machines often don't.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "hyperion-grabber-desktop"
