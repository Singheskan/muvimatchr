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
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")  // Thymeleaf template engine
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

	testImplementation("org.springframework.boot:spring-boot-testcontainers")  // Spring's @ServiceConnection Testcontainers integration
	testImplementation("org.testcontainers:testcontainers-postgresql")  // Testcontainers Postgres module (2.0.x artifact id)
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")  // Testcontainers JUnit 5 integration
}


kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
