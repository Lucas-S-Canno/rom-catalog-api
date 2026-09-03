val kotlinVersion = "2.1.0"
val ktorVersion = "3.0.3"
val exposedVersion = "0.57.0"
val flywayVersion = "10.20.1"
val hikariVersion = "6.2.1"
val postgresVersion = "42.7.4"
val minioVersion = "8.5.14"
val logbackVersion = "1.5.12"
val testcontainersVersion = "1.21.3"
val mockkVersion = "1.13.13"
val junitVersion = "5.11.3"

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("io.ktor.plugin") version "3.0.3"
    jacoco
}

group = "com.lucascanno"
version = "0.1.0"

application {
    mainClass.set("com.lucascanno.romcatalog.ApplicationKt")
}

// The Ktor fat jar is a shadow jar. Without merging service files, shadow keeps
// only ONE `META-INF/services/org.flywaydb.core.extensibility.Plugin` (postgres),
// clobbering flyway-core's — which breaks classpath scanning of `db/migration`.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    mergeServiceFiles()
}

repositories {
    mavenCentral()
}

// Testcontainers 1.21.x ships docker-java 3.4.x, which cannot negotiate the API
// with Docker Engine 29 (its `/info` probe returns HTTP 400). Force a newer
// docker-java that speaks API >= 1.44.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.github.docker-java") {
            useVersion("3.5.3")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")

    // Persistence
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Object storage
    implementation("io.minio:minio:$minioVersion")

    // Password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Tests
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:minio:$testcontainersVersion")
}

// ── Test tasks ────────────────────────────────────────────────────────────────
// `test`     -> everything (unit + integration/route). This is the CI gate.
// `testUnit` -> only fast tests (no Testcontainers). Handy for the local loop.
// docker-java (via Testcontainers) falls back to Docker API v1.24, which Docker
// Engine 29 refuses (minimum v1.44). Pin the negotiated version. Overridable with
// -Dapi.version=... and harmless on any engine that supports >= 1.44.
fun Test.configureDockerForTestcontainers() {
    if (System.getProperty("api.version") == null) {
        systemProperty("api.version", "1.44")
    }
    // Windows + Docker Desktop: point at the active engine pipe unless told otherwise.
    if (System.getProperty("os.name").orEmpty().startsWith("Windows") &&
        System.getenv("DOCKER_HOST").isNullOrBlank()
    ) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
    }
}

tasks.test {
    useJUnitPlatform()
    configureDockerForTestcontainers()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("testUnit") {
    description = "Runs only fast unit tests (excludes @Tag(\"it\"))."
    group = "verification"
    useJUnitPlatform {
        excludeTags("it")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Manual token minting for local/homelab use:
//   ./gradlew -q issueToken --args="--scope admin --ttl-days 365"
// Signs with JWT_SECRET from the environment (falls back to the insecure dev default).
tasks.register<JavaExec>("issueToken") {
    group = "application"
    description = "Print a signed JWT. Args: --scope <user|admin> [--ttl-days N]"
    mainClass.set("com.lucascanno.romcatalog.auth.TokenIssuerCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

// Bulk ingestion from a directory, straight into Postgres + MinIO:
//   ./gradlew -q ingest --args="--dir /path/to/roms [--dry-run]"
tasks.register<JavaExec>("ingest") {
    group = "application"
    description = "Scan a directory and ingest ROMs. Args: --dir <path> [--dry-run]"
    mainClass.set("com.lucascanno.romcatalog.ingest.IngestCliKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
