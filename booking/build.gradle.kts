// The booking service of Module 11 with a load shedder in front: the in-flight limit from the
// JDK-only limits, and the actuator for the shed counter and the in-flight gauge.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":limits"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.result4j)
    testImplementation(libs.spring.boot.starter.test)
}
