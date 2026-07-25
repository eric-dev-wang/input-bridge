plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform")
}

group = "com.ericdevwang.inputbridge.plugin"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

val localAndroidStudioPath = providers.gradleProperty("androidStudioPath")
val intellijIdeaVersion = "2026.1.1"
val androidPluginVersion = "261.23567.138"
val inputBridgeSharedSecret = rootProject.extra["inputBridgeSharedSecret"] as String
val sharedSecretResourceDir = layout.buildDirectory.dir("generated/resources/sharedSecret")
val generateSharedSecretResource = tasks.register("generateSharedSecretResource") {
    val outputFile = sharedSecretResourceDir.map { it.file("input-bridge-shared-secret.txt") }
    inputs.property("inputBridgeSharedSecret", inputBridgeSharedSecret)
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(inputBridgeSharedSecret)
        }
    }
}

sourceSets {
    named("main") {
        resources.srcDir(sharedSecretResourceDir)
    }
}

tasks.named("processResources") {
    dependsOn(generateSharedSecretResource)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":core:connection-client"))

    intellijPlatform {
        if (localAndroidStudioPath.isPresent) {
            local(localAndroidStudioPath.get())
            bundledPlugin("org.jetbrains.android")
        } else {
            intellijIdea(intellijIdeaVersion)
            plugin("org.jetbrains.android:$androidPluginVersion")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }

    pluginVerification {
        ides {
            if (localAndroidStudioPath.isPresent) {
                local(file(localAndroidStudioPath.get()))
            } else {
                current()
            }
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}
