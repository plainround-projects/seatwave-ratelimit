// The lab's token issuer stand-in: it signs short-lived tokens and publishes its public keys.
// Module 38 replaces it with a real login service; nothing here checks who the fan is.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.nimbus.jose.jwt)
    testImplementation(libs.spring.boot.starter.test)
}

// One command per program: ./gradlew :issuer:keys --args="generate build/keys/issuer-keys.json"
tasks.register<JavaExec>("keys") {
    group = "application"
    description = "Generates or rotates the issuer's signing keys in an ignored folder."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.ratelimit.issuer.KeyRingCli"
    workingDir = rootDir
}
