plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))
    implementation(project(":core:crypto"))
    implementation(project(":core:framing"))
    testImplementation(libs.junit)
    testImplementation(project(":core:connection-client"))
}
