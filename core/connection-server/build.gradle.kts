plugins {
    alias(libs.plugins.inputbridge.jvm.library)
}

dependencies {
    api(projects.protocol)
    implementation(projects.core.crypto)
    implementation(projects.core.framing)
    testImplementation(projects.core.connectionClient)
}
