import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

// The rules every program of this repository shares, in one place. A build-logic convention plugin
// would do the same, at the price of a Kotlin compiler run for some forty lines; one block is enough.
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
}

// The plugins' jars are locked as well, in buildscript-gradle.lockfile.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // The Spring Boot plugin brings commons-lang3 3.16.0 onto the build's own classpath,
            // which OSV lists for CVE-2025-48924 (fixed in 3.18.0). Build time only, never in an
            // image; raised to the current release, checked on Maven Central and OSV on 12 November
            // 2026. A buildscript block cannot read the version catalog, so the version is here.
            classpath("org.apache.commons:commons-lang3:3.22.0")
        }
    }
}

val googleJavaFormatVersion = libs.versions.googleJavaFormat.get()
val jacocoVersion = libs.versions.jacoco.get()
val springBootBom = libs.spring.boot.bom
val springCloudBom = libs.spring.cloud.bom
val junitLauncher = libs.junit.platform.launcher

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    group = "io.plainround"
    version = "1.0.0"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // Transitive versions are written to each program's gradle.lockfile, so two builds can never
    // resolve different jars.
    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        // Spring Boot's and Spring Cloud's bills of materials align every Spring, Jackson, logging and
        // test library; the Spring Cloud train 2025.1.x is the one that supports Boot 4.1.x.
        "implementation"(platform(springBootBom))
        "implementation"(platform(springCloudBom))
        "testImplementation"(platform(springBootBom))
        "testRuntimeOnly"(junitLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    extensions.configure<SpotlessExtension> {
        java {
            googleJavaFormat(googleJavaFormatVersion)
            removeUnusedImports()
            endWithNewline()
        }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = jacocoVersion
    }

    // The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a
    // reader without Docker can still build and test everything else.
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("stack")
        }
        testLogging {
            events("failed", "skipped")
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    // The XML report gives the exact covered and missed line counts that the README quotes.
    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("test"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    // The floor for each program; the main methods are the untested remainder.
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestCoverageVerification"))
    }
}
