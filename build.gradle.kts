plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.gkcontas"
version = "0.1.0-SNAPSHOT"
description = "Web scraper and crawler with SSRF protection, robots.txt compliance and virtual threads"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.jsoup:jsoup:1.21.1")
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The standalone build shades Jetty, Jackson and Guava. The plain artifact drags its
    // own copies onto the test classpath and they collide with Spring Boot's.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.awaitility:awaitility")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
