plugins {
    alias(libs.plugins.inputbridge.jvm.library)
}

dependencies {
    api(project(":protocol"))
    implementation(project(":core:crypto"))
    implementation(project(":core:framing"))
    testImplementation(project(":core:connection-server"))
}
