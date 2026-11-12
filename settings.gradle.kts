// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// The plugins' own dependencies are locked too, in settings-gradle.lockfile.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

rootProject.name = "seatwave-ratelimit"

// Module 11's six programs, plus "limits": the JDK-only algorithms that the gateway and booking use
// and the three programs that show them.
include("gateway", "catalog", "booking", "mobile-bff", "issuer", "labtools", "limits")
