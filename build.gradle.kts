plugins {
	kotlin("jvm") version "2.3.20"
	kotlin("plugin.spring") version "2.3.20"
	kotlin("plugin.jpa") version "2.3.20"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")  // Spring MVC for web development
	implementation("org.springframework.boot:spring-boot-starter-validation")  // Jakarta Bean Validation (Hibernate Validator) — not transitively pulled in by spring-boot-starter-web since Spring Boot 2.3
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")  // Kotlin module for Jackson (JSON parsing)
	implementation("org.jetbrains.kotlin:kotlin-reflect")  // Kotlin reflection support

	developmentOnly("org.springframework.boot:spring-boot-devtools")  // Hot reload for development

	testImplementation("org.springframework.boot:spring-boot-starter-test")  // Testing framework
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")  // MockMvc test autoconfiguration (Boot 4 modularized this out of spring-boot-test-autoconfigure)
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")  // JUnit 5 support for Kotlin
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")  // JUnit 5 runtime support

	implementation("org.springframework.boot:spring-boot-starter-websocket")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")  // Spring Data JPA / Hibernate ORM
	implementation("org.springframework.boot:spring-boot-starter-flyway")  // Flyway auto-configuration starter (Boot 4+)
	implementation("org.flywaydb:flyway-database-postgresql")  // Flyway PostgreSQL dialect module
	implementation("org.postgresql:postgresql")  // PostgreSQL JDBC driver

	implementation("org.springframework.boot:spring-boot-starter-webclient")  // Outbound-only reactive HTTP client (WebClient.Builder auto-config) for calling TMDB — narrower than -webflux, no reactive server
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")  // Bridges WebClient's reactive Mono into suspend functions (awaitSingle)

	testImplementation("org.springframework.boot:spring-boot-testcontainers")  // Spring's @ServiceConnection Testcontainers integration
	testImplementation("org.testcontainers:testcontainers-postgresql")  // Testcontainers Postgres module (2.0.x artifact id)
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")  // Testcontainers JUnit 5 integration
	testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")  // Fake HTTP server for testing MovieCatalogClient without hitting real TMDB
}


kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// D-01: single deployable JAR. These two tasks build the frontend/ Vite React+TS SPA and copy its
// output into the resources the boot jar packages, so one artifact serves both the REST/WebSocket
// API and the SPA from one origin -- no CORS config, no second hosting target. Both are gated on
// -PskipFrontendBuild so a backend-only test loop can skip the npm round-trip.
val npmInstall = tasks.register<Exec>("npmInstall") {
	workingDir = file("frontend")
	commandLine("npm", "ci")
	inputs.file("frontend/package.json")
	inputs.file("frontend/package-lock.json")
	outputs.dir("frontend/node_modules")
	onlyIf { !project.hasProperty("skipFrontendBuild") }
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
	dependsOn(npmInstall)
	workingDir = file("frontend")
	commandLine("npm", "run", "build")
	inputs.dir("frontend/src")
	inputs.file("frontend/index.html")
	inputs.file("frontend/vite.config.ts")
	outputs.dir("frontend/dist")
	onlyIf { !project.hasProperty("skipFrontendBuild") }
}

// Copies frontend/dist into this task's OWN output (build/resources/main/static), never into
// src/main/resources/static -- that would put Vite build output under version control.
tasks.named<ProcessResources>("processResources") {
	dependsOn(buildFrontend)
	from("frontend/dist") {
		into("static")
	}
}
