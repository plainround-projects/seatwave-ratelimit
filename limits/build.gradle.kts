// The limiting algorithms and the in-flight limit, with the JDK alone, and the three programs that
// show them working. The gateway uses TokenBucket for its local fallback; booking uses InFlightLimit.

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.register<JavaExec>("algorithms") {
    group = "application"
    description = "Runs the four algorithms on fixed timelines: a burst, the boundary burst, a bot and two fans."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.ratelimit.limits.AlgorithmDemo"
}

tasks.register<JavaExec>("sharedLimit") {
    group = "application"
    description = "Sends one fan's holds through three instances against three ways of keeping the bucket."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.ratelimit.limits.SharedLimitDemo"
}

tasks.register<JavaExec>("shed") {
    group = "application"
    description = "Overloads a 64-worker stand-in twice: queueing every hold, then shedding beyond 64 in flight."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.ratelimit.limits.ShedDemo"
}

// The coverage floor counts the classes that hold the rules: the four algorithms, the decision and
// the in-flight limit. The three programs only print timelines; they are run by the tasks above and
// their output is in the module, not asserted here.
val printingOnly = listOf("**/AlgorithmDemo*", "**/SharedLimitDemo*", "**/ShedDemo*")

tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(
        classDirectories.files.map { folder -> fileTree(folder) { exclude(printingOnly) } },
    )
}
