// The edge gateway of Module 11 with the rate limiter added: Spring Data Redis (with Lettuce, the
// client Spring Boot manages) for the shared bucket, and the JDK-only limits for the fallback.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":limits"))
    implementation(libs.spring.cloud.starter.gateway.server.webmvc)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.test)
}

// The tests tagged "stack" talk to the running Compose stack on its published 127.0.0.1 ports:
// Module 11's StackTest and this module's ThreeInstanceLimitTest. scripts/stack.sh test starts the
// stack, runs this task and stops the stack on every path; ./gradlew check never runs them.
tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // Lab 2 runs the store-failure case with -Dseatwave.redis=stopped; the test JVM needs to see it.
    systemProperty("seatwave.redis", providers.systemProperty("seatwave.redis").getOrElse("running"))
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        // The three-instance test prints its counts; show them on the console too.
        showStandardStreams = true
    }
}
