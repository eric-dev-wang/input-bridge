plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))
    implementation(project(":core:framing"))
    testImplementation(libs.junit)
}
