plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The scheduling engine is deliberately a pure-Kotlin JVM module.
// It must never gain an Android or UI dependency: all priority scoring,
// slot allocation and reshuffling logic has to stay unit-testable without
// an emulator or a device.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
