plugins {
    // Resolves the Java 21 toolchain automatically when the machine does not have it
    // installed, so `./gradlew build` works on a clean checkout.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "crawler-api"
