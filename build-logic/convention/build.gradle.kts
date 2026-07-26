import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.ericdevwang.inputbridge.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.detekt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "com.ericdevwang.inputbridge.android.application"
            implementationClass =
                "com.ericdevwang.inputbridge.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "com.ericdevwang.inputbridge.android.library"
            implementationClass =
                "com.ericdevwang.inputbridge.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("koin") {
            id = "com.ericdevwang.inputbridge.koin"
            implementationClass =
                "com.ericdevwang.inputbridge.buildlogic.KoinConventionPlugin"
        }
        register("jvmLibrary") {
            id = "com.ericdevwang.inputbridge.jvm.library"
            implementationClass =
                "com.ericdevwang.inputbridge.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidStudioPlugin") {
            id = "com.ericdevwang.inputbridge.android.studio.plugin"
            implementationClass =
                "com.ericdevwang.inputbridge.buildlogic.AndroidStudioPluginConventionPlugin"
        }
    }
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}
