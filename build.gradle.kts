plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.3.3"
	id("io.spring.dependency-management") version "1.1.6"
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
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")  // Kotlin module for Jackson (JSON parsing)
	implementation("org.jetbrains.kotlin:kotlin-reflect")  // Kotlin reflection support

	developmentOnly("org.springframework.boot:spring-boot-devtools")  // Hot reload for development

	testImplementation("org.springframework.boot:spring-boot-starter-test")  // Testing framework
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")  // JUnit 5 support for Kotlin
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")  // JUnit 5 runtime support

	implementation("org.springframework.boot:spring-boot-starter-websocket")
}


kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
