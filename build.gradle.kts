plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("com.google.cloud.tools.jib") version "3.5.4"
    id("org.jooq.jooq-codegen-gradle") version "3.21.7" // keep in step with Spring Boot's managed jOOQ
}

group = "cloud.jjoon"
version = "1.0.0" // semantic version; image tag is <version>-<git sha> (Jenkinsfile)
description = "backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    jooqCodegen("org.jooq:jooq-meta-extensions:3.21.7")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// jOOQ classes are generated from the Flyway scripts; the build needs no database (DEC-ARCH-002).
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property { key = "scripts"; value = "src/main/resources/db/migration/*.sql" }
                    property { key = "sort"; value = "flyway" }
                    property { key = "defaultNameCase"; value = "lower" }
                    property { key = "parseIgnoreComments"; value = "true" }
                }
                forcedTypes {
                    forcedType {
                        name = "INSTANT"
                        includeTypes = "(?i)TIMESTAMP.*WITH.*TIME.*ZONE.*"
                    }
                }
            }
            target {
                packageName = "cloud.jjoon.workout.jooq"
            }
        }
    }
}

sourceSets.main {
    java.srcDir(tasks.jooqCodegen)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Image name and registry credentials come from CI (Jenkinsfile): ./gradlew jib -Djib.to.image=...
jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            platform {
                architecture = "arm64" // deploy server runs Colima on Apple Silicon
                os = "linux"
            }
        }
    }
    to {
        auth {
            username = System.getenv("GHCR_USER")
            password = System.getenv("GHCR_TOKEN")
        }
    }
    container {
        user = "501:20" // non-root
        ports = listOf("8080")
    }
}
