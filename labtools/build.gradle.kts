// The JDK-only teaching programs: a token walked through every validation step, the cost of one
// check, a seat screen fetched sequentially against fanned out, and Lab 1's open-loop client.
// Section 8 of the module also runs them with javac and java alone; these tasks run the same classes.

dependencies {
    testImplementation(libs.spring.boot.starter.test)
}

val entryPoints = mapOf(
    "tokenWalk" to "io.plainround.seatwave.ratelimit.labtools.TokenWalk",
    "tokenCost" to "io.plainround.seatwave.ratelimit.labtools.TokenCheckCost",
    "fanOut" to "io.plainround.seatwave.ratelimit.labtools.FanOutDemo",
    "latencyProbe" to "io.plainround.seatwave.ratelimit.labtools.LatencyProbe",
    "holdTraffic" to "io.plainround.seatwave.ratelimit.labtools.HoldTraffic",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
    }
}

// The coverage floor counts the classes that hold the rules (the token check, the minting, the
// JSON reader and the verdicts). The four entry points and the loopback stand-ins only time and
// print; they are run by the tasks above and their output is in the module, not asserted here.
val printingOnly = listOf(
    "**/TokenWalk*", "**/TokenCheckCost*", "**/FanOutDemo*", "**/LatencyProbe*", "**/HoldTraffic*",
    "**/ScreenFetcher*", "**/ScreenResult*", "**/StubServices*",
)

tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(
        classDirectories.files.map { folder -> fileTree(folder) { exclude(printingOnly) } },
    )
}
