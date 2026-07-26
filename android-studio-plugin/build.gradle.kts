import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateSharedSecretResourceTask : DefaultTask() {
    @get:Input
    abstract val sharedSecret: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sharedSecret.get())
        }
    }
}

plugins {
    alias(libs.plugins.inputbridge.android.studio.plugin)
    id("org.jetbrains.intellij.platform")
}

group = "com.ericdevwang.inputbridge.plugin"
version = rootProject.version

val localAndroidStudioPath = providers.gradleProperty("androidStudioPath")
val intellijIdeaVersion = "2026.1.1"
val androidPluginVersion = "261.23567.138"
val inputBridgeSharedSecret = rootProject.extra["inputBridgeSharedSecret"] as String
val sharedSecretResourceDir = layout.buildDirectory.dir("generated/resources/sharedSecret")
val generateSharedSecretResource = tasks.register<GenerateSharedSecretResourceTask>(
    "generateSharedSecretResource",
) {
    sharedSecret.set(inputBridgeSharedSecret)
    outputFile.set(sharedSecretResourceDir.map { it.file("input-bridge-shared-secret.txt") })
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
    implementation(projects.protocol)
    implementation(projects.core.connectionClient)

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
